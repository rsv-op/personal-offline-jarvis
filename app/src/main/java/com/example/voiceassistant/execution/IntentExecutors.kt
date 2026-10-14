package com.example.voiceassistant.execution

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import com.example.voiceassistant.nlp.AppIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface IntentExecutor<T : AppIntent> {
    suspend fun execute(context: Context, intent: T)
}

class FlashlightExecutor : IntentExecutor<AppIntent.ToggleFlashlight> {
    override suspend fun execute(context: Context, intent: AppIntent.ToggleFlashlight) =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("CAMERA permission required for flashlight")
            }

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: throw IllegalStateException("No flashlight available")

            cameraManager.setTorchMode(cameraId, intent.isOn)
        }
}

class AlarmExecutor : IntentExecutor<AppIntent.SetAlarm> {
    override suspend fun execute(context: Context, intent: AppIntent.SetAlarm) =
        withContext(Dispatchers.Main) {
            val time = intent.time ?: throw IllegalArgumentException("Time is required")
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

            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                putExtra(AlarmClock.EXTRA_HOUR, hourOfDay)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (alarmIntent.resolveActivity(context.packageManager) == null) {
                throw IllegalStateException("No alarm application is available")
            }
            context.startActivity(alarmIntent)
        }
}
