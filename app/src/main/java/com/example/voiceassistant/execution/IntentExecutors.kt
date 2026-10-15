package com.example.voiceassistant.execution

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.StatFs
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import com.example.voiceassistant.nlp.AppIntent
import com.example.voiceassistant.nlp.DurationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Executors perform the one device action they name and return the sentence JARVIS
 * should speak. They are only ever called after the confirmation flow (or, for
 * read-only intents such as [AppIntent.StatusReport], straight away).
 */
interface IntentExecutor<T : AppIntent> {
    suspend fun execute(context: Context, intent: T): String
}

class FlashlightExecutor : IntentExecutor<AppIntent.ToggleFlashlight> {
    override suspend fun execute(context: Context, intent: AppIntent.ToggleFlashlight): String =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("The camera permission is required for the flashlight")
            }

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: throw IllegalStateException("This device has no flashlight")

            cameraManager.setTorchMode(cameraId, intent.isOn)
            if (intent.isOn) "Flashlight on." else "Flashlight off."
        }
}

class AlarmExecutor : IntentExecutor<AppIntent.SetAlarm> {
    override suspend fun execute(context: Context, intent: AppIntent.SetAlarm): String =
        withContext(Dispatchers.Main) {
            val time = intent.time ?: throw IllegalArgumentException("A time is required")
            val (hourOfDay, minute) = parseTime(time)
            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                putExtra(AlarmClock.EXTRA_HOUR, hourOfDay)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (alarmIntent.resolveActivity(context.packageManager) == null) {
                throw IllegalStateException("No alarm application is installed")
            }
            context.startActivity(alarmIntent)
            "Alarm set for ${format(hourOfDay, minute)}."
        }

    companion object {
        fun parseTime(time: String): Pair<Int, Int> {
            val parts = time.split(Regex("[: ]")).filter(String::isNotBlank)
            val hour = parts.getOrNull(0)?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid alarm time: $time")
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val meridiem = parts.getOrNull(2)
            val hourOfDay = when {
                meridiem.equals("pm", ignoreCase = true) && hour in 1..11 -> hour + 12
                meridiem.equals("am", ignoreCase = true) && hour == 12 -> 0
                else -> hour
            }
            require(hourOfDay in 0..23 && minute in 0..59) { "Invalid alarm time: $time" }
            return hourOfDay to minute
        }

        fun format(hourOfDay: Int, minute: Int): String =
            String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
    }
}

class TimerExecutor : IntentExecutor<AppIntent.SetTimer> {
    override suspend fun execute(context: Context, intent: AppIntent.SetTimer): String =
        withContext(Dispatchers.Main) {
            val seconds = intent.durationSeconds
                ?: throw IllegalArgumentException("A duration is required")
            require(seconds in 1..MAX_TIMER_SECONDS) { "Timers are limited to 24 hours" }
            val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS timer")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (timerIntent.resolveActivity(context.packageManager) == null) {
                throw IllegalStateException("No clock application is installed")
            }
            context.startActivity(timerIntent)
            "Timer started for ${DurationParser.describe(seconds)}."
        }

    private companion object {
        const val MAX_TIMER_SECONDS = 24 * 60 * 60
    }
}

/**
 * Read-only status: battery, storage and what the assistant itself is running.
 * Nothing here changes device state, which is why it needs no confirmation.
 */
class StatusExecutor(
    private val assistantStatus: suspend () -> String
) : IntentExecutor<AppIntent.StatusReport> {
    override suspend fun execute(context: Context, intent: AppIntent.StatusReport): String {
        val battery = batterySummary(context)
        val storage = storageSummary(context)
        val assistant = assistantStatus()
        return listOf(battery, storage, assistant).filter(String::isNotBlank).joinToString(" ")
    }

    private fun batterySummary(context: Context): String = try {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = manager.isCharging
        when {
            level <= 0 -> ""
            charging -> "Battery is $level percent and charging."
            else -> "Battery is $level percent."
        }
    } catch (_: Exception) {
        ""
    }

    private fun storageSummary(context: Context): String = try {
        val stats = StatFs(context.filesDir.absolutePath)
        val freeGigabytes = stats.availableBytes / (1024.0 * 1024.0 * 1024.0)
        String.format(Locale.US, "%.1f gigabytes of storage are free.", freeGigabytes)
    } catch (_: Exception) {
        ""
    }
}
