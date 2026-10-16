package com.example.voiceassistant.telematics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.example.voiceassistant.memory.DeviceMemory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [SystemMetricsCollector] backed by Android APIs and the Linux
 * pseudo-filesystems JARVIS is allowed to read. All reads are best-effort: a missing
 * file or an unreadable integer leaves the field at 0 rather than throwing, so a
 * quirky device produces a degraded snapshot instead of a crash.
 */
@Singleton
class AndroidSystemMetricsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceMemory: DeviceMemory
) : SystemMetricsCollector {

    override fun snapshot(): TelematicsSnapshot {
        return TelematicsSnapshot(
            memory = readMemory(),
            storage = readStorage(),
            cpu = readCpu(),
            battery = readBattery(),
            thermal = readThermal(),
            process = readProcess()
        )
    }

    private fun readMemory(): TelematicsSnapshot.MemorySection {
        val mi = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(mi)
        val appInfo = readAppMemoryInfo()
        return TelematicsSnapshot.MemorySection(
            totalBytes = deviceMemory.totalMemoryBytes.takeIf { it > 0 } ?: mi.totalMem,
            availableBytes = deviceMemory.availableMemoryBytes.takeIf { it > 0 } ?: mi.availMem,
            thresholdBytes = mi.threshold,
            isLowMemory = mi.lowMemory,
            appPssKb = appInfo.first,
            appPrivateDirtyKb = appInfo.second,
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize()
        )
    }

    private fun readAppMemoryInfo(): Pair<Long, Long> {
        return try {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            memInfo.totalPss.toLong() to memInfo.totalPrivateDirty.toLong()
        } catch (_: Throwable) {
            0L to 0L
        }
    }

    private fun readStorage(): TelematicsSnapshot.StorageSection {
        return try {
            val stat = android.os.StatFs(context.filesDir.absolutePath)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val appBytes = folderSize(context.filesDir)
            TelematicsSnapshot.StorageSection(
                internalTotalBytes = totalBytes,
                internalFreeBytes = freeBytes,
                appFilesBytes = appBytes
            )
        } catch (_: Throwable) {
            TelematicsSnapshot.StorageSection(0L, 0L, 0L)
        }
    }

    private fun folderSize(folder: File): Long {
        if (!folder.isDirectory) return 0L
        var total = 0L
        folder.walkTopDown().filter(File::isFile).forEach { total += it.length() }
        return total
    }

    private fun readCpu(): TelematicsSnapshot.CpuSection {
        val (overallPercent, frequencies) = readOverallCpuAndFrequencies()
        val (appPercent, threads) = readAppCpuAndThreads()
        return TelematicsSnapshot.CpuSection(
            overallPercent = overallPercent,
            appPercent = appPercent,
            perCoreFrequenciesKHz = frequencies,
            threadCount = threads,
            uptimeMs = SystemClock.elapsedRealtime(),
            awakeMs = SystemClock.uptimeMillis()
        )
    }

    private fun readOverallCpuAndFrequencies(): Pair<Float, List<Long>> {
        val now = readProcStatDetailed() ?: return 0f to emptyList()
        val previous = lastOverallCpu
        val overall = if (previous != null && now.total > previous.total) {
            ((now.busy - previous.busy).toDouble() / (now.total - previous.total).toDouble())
                .coerceIn(0.0, 1.0).toFloat()
        } else 0f
        lastOverallCpu = now
        val frequencies = readPerCoreFrequencies()
        return (overall * 100f) to frequencies
    }

    @Volatile private var lastOverallCpu: ProcParsers.CpuTimes? = null
    @Volatile private var lastAppJiffies: Long = 0L
    @Volatile private var lastSampleElapsed: Long = 0L

    private fun readProcStatDetailed(): ProcParsers.CpuTimes? = try {
        val text = File("/proc/stat").readText()
        ProcParsers.parseProcStatDetailed(text)
    } catch (_: Throwable) {
        null
    }

    private fun readAppCpuAndThreads(): Pair<Float, Long> {
        val pid = android.os.Process.myPid()
        val statText = try {
            File("/proc/$pid/stat").readText()
        } catch (_: Throwable) {
            return 0f to 0L
        }
        val parsed = ProcParsers.parseProcessStat(statText) ?: return 0f to 0L
        val now = SystemClock.elapsedRealtime()
        val lastJiffies = lastAppJiffies
        val lastElapsed = lastSampleElapsed
        val cpuClockTicksPerSecond = 100L // Android default
        val appPercent = if (lastElapsed > 0 && now > lastElapsed && lastJiffies > 0) {
            val deltaJiffies = (parsed.totalJiffies() - lastJiffies).coerceAtLeast(0)
            val deltaMs = now - lastElapsed
            // CPU% = (deltaJiffies / clock) / deltaMs
            val percent = (deltaJiffies.toDouble() / cpuClockTicksPerSecond.toDouble()) /
                (deltaMs.toDouble() / 1000.0)
            (percent * 100.0).coerceIn(0.0, 100.0).toFloat()
        } else 0f
        lastAppJiffies = parsed.totalJiffies()
        lastSampleElapsed = now
        return appPercent to parsed.numThreads
    }

    private fun readPerCoreFrequencies(): List<Long> {
        val sys = File("/sys/devices/system/cpu")
        if (!sys.isDirectory) return emptyList()
        val cores = sys.listFiles { f -> f.name.matches(Regex("cpu\\d+")) } ?: return emptyList()
        return cores.mapNotNull { core ->
            try {
                val freqFile = File(core, "cpufreq/scaling_cur_freq")
                if (freqFile.isFile) {
                    ProcParsers.parseSingleInteger(freqFile.readText())
                } else null
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun readBattery(): TelematicsSnapshot.BatterySection {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent: Intent? = context.registerReceiver(null, filter)
        if (intent == null) {
            return TelematicsSnapshot.BatterySection(
                levelPercent = -1,
                isCharging = false,
                plugType = "unknown",
                temperatureC = 0f,
                voltageMv = 0,
                currentNowMicroA = 0L,
                health = "unknown"
            )
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) ((level * 100) / scale) else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val plugName = when (plug) {
            0 -> "battery"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "other"
        }
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val healthId = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val healthName = when (healthId) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over-voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
        // Current draw (signed: +charging, -discharging) needs API 21+ and is not always
        // reported by the platform. We use BatteryManager (sticky property), not the intent
        // extra, because the property is refreshed on demand.
        val currentMa = try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()
        } catch (_: Throwable) {
            0L
        }
        return TelematicsSnapshot.BatterySection(
            levelPercent = percent,
            isCharging = charging,
            plugType = plugName,
            temperatureC = tempTenths / 10f,
            voltageMv = voltage,
            currentNowMicroA = currentMa,
            health = healthName
        )
    }

    private fun readThermal(): TelematicsSnapshot.ThermalSection {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return TelematicsSnapshot.ThermalSection("UNAVAILABLE", -1)
        }
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val status = pm.currentThermalStatus
            val name = when (status) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN"
            }
            TelematicsSnapshot.ThermalSection(name, status)
        } catch (_: Throwable) {
            TelematicsSnapshot.ThermalSection("UNAVAILABLE", -1)
        }
    }

    private fun readProcess(): TelematicsSnapshot.ProcessSection {
        val pid = android.os.Process.myPid().toLong()
        val pm = context.packageManager
        val info: PackageInfo? = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        } catch (_: Throwable) {
            null
        }
        return TelematicsSnapshot.ProcessSection(
            pid = pid,
            packageName = context.packageName,
            versionName = info?.versionName ?: "?",
            versionCode = if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else 0L,
            targetSdk = context.applicationInfo.targetSdkVersion,
            minSdk = info?.applicationInfo?.minSdkVersion ?: 0
        )
    }
}
