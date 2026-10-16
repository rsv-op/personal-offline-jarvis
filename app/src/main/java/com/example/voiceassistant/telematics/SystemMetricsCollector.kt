package com.example.voiceassistant.telematics

/**
 * Snapshot of all telematics the app can read without privileged permissions.
 *
 * Every field is a "best effort" read; a phone that returns 0 for one of them
 * still gets a coherent snapshot. The point is to answer "how much RAM is left,
 * and is the system about to start killing things" — not to be exhaustive.
 */
data class TelematicsSnapshot(
    val memory: MemorySection,
    val storage: StorageSection,
    val cpu: CpuSection,
    val battery: BatterySection,
    val thermal: ThermalSection,
    val process: ProcessSection
) {
    data class MemorySection(
        val totalBytes: Long,
        val availableBytes: Long,
        val thresholdBytes: Long,
        val isLowMemory: Boolean,
        val appPssKb: Long,
        val appPrivateDirtyKb: Long,
        val nativeHeapBytes: Long,
        val gcCount: Long,
        val gcTimeMs: Long
    ) {
        val availableFraction: Float
            get() = if (totalBytes == 0L) 0f else (availableBytes.toDouble() / totalBytes).toFloat()
    }

    data class StorageSection(
        val internalTotalBytes: Long,
        val internalFreeBytes: Long,
        val appFilesBytes: Long
    )

    data class CpuSection(
        val overallPercent: Float,
        val appPercent: Float,
        val perCoreFrequenciesKHz: List<Long>,
        val threadCount: Long,
        val uptimeMs: Long,
        val awakeMs: Long
    )

    data class BatterySection(
        val levelPercent: Int,
        val isCharging: Boolean,
        val plugType: String,
        val temperatureC: Float,
        val voltageMv: Int,
        val currentNowMicroA: Long,
        val health: String
    )

    data class ThermalSection(
        /** One of NONE / LIGHT / MODERATE / SEVERE / CRITICAL / EMERGENCY / SHUTDOWN. */
        val status: String,
        /** Numeric level (0..7) corresponding to the status. */
        val level: Int
    )

    data class ProcessSection(
        val pid: Long,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val targetSdk: Int,
        val minSdk: Int
    )
}

/** A read-only view of system metrics. Extracted as an interface so the screen is testable. */
interface SystemMetricsCollector {
    fun snapshot(): TelematicsSnapshot
}
