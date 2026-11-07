package com.example.voiceassistant.generation.llama

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.voiceassistant.R
import com.example.voiceassistant.memory.DeviceMemory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the local model resident while the assistant is in use.
 *
 * Why a foreground service: loading a multi-GB model takes 5–15 seconds. If
 * the model were dropped whenever the app went to the background, every
 * question would pay that cost again. The service keeps the model loaded so
 * the *second* question is answered almost immediately.
 *
 * Why it unloads under memory pressure: a resident model is a liability when
 * the device is short of RAM. The check below uses **available** memory, not
 * the process's RSS. This is the important detail: llama.cpp memory-maps the
 * model weights, so the resident set includes pages that the kernel can evict
 * and page back in on demand. An RSS-based rule would see a multi-GB resident
 * set and drop the model even on a device with plenty of free memory, which
 * would make the service pointless. Available memory measures what the device
 * can actually still give us.
 */
@AndroidEntryPoint
class LlamaModelService : Service() {

    @Inject lateinit var memoryBudget: DeviceMemory

    @Inject lateinit var generator: LlamaLocalTextGenerator

    @Inject lateinit var modelStore: LlamaModelStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()
        startMemoryWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // The model is loaded lazily by the generator on first use; the
        // service's job is to stay alive so that load is not thrown away.
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiped the app away. There is no point in keeping a
        // multi-gigabyte model resident for an app that is not visible.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.model_service_notification))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.model_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.model_service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Polls available memory and unloads the model when the device gets tight.
     */
    private fun startMemoryWatchdog() {
        serviceScope.launch {
            while (true) {
                delay(MEMORY_CHECK_INTERVAL_MS)
                if (!generator.isModelLoaded) continue

                val available = memoryBudget.availableMemoryBytes
                if (available <= 0L) continue

                if (available < MEMORY_FLOOR_BYTES) {
                    generator.releaseModel()
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(
                        NOTIFICATION_ID,
                        buildNotification(getString(R.string.model_service_unloaded))
                    )
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_model_service"
        private const val NOTIFICATION_ID = 4201

        /** How often the watchdog checks available memory. */
        private const val MEMORY_CHECK_INTERVAL_MS = 15_000L

        /**
         * The floor below which the model is unloaded.
         *
         * 512 MB is enough headroom for the rest of the app (the audio
         * pipeline, the Compose UI and the system) while the model is
         * resident. Dropping the model at this point trades a 5–15 second
         * cold start later for not being OOM-killed now.
         */
        private const val MEMORY_FLOOR_BYTES = 512L * 1024L * 1024L

        const val ACTION_STOP = "com.example.voiceassistant.action.STOP_MODEL_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, LlamaModelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LlamaModelService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
