package com.example.voiceassistant.memory

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A read-only view of device memory. Extracted as an interface so unit tests can stub it. */
interface DeviceMemory {
    /** Total RAM installed on the device, in bytes. 0 if unknown. */
    val totalMemoryBytes: Long

    /** Currently available RAM, in bytes. 0 if unknown. */
    val availableMemoryBytes: Long

    /**
     * Returns `null` if a model of [modelBytes] can be loaded right now, otherwise a
     * reason that names both the threshold and what was actually available. The Vosk
     * path uses [headroomMultiplier] of 1.5; the LiteRT path uses 2.5.
     *
     * The 2.5× figure is intentionally tighter than the 2× the user's crash
     * suggests the math needs: the on-disk size is a lower bound, and LiteRT
     * memory-maps the model and then allocates KV cache and a context window
     * on top of it. A 2 GB model on 4 GB free RAM still OOM-kills the device.
     */
    fun refusalReason(modelBytes: Long, headroomMultiplier: Double = 2.5): String? =
        Companion.refusalReason(availableMemoryBytes, modelBytes, headroomMultiplier)

    companion object {
        /**
         * Pure-JVM helper so the math can be unit-tested without Android. Returns `null`
         * when the load is safe, otherwise a human-readable reason.
         */
        fun refusalReason(
            availableBytes: Long,
            modelBytes: Long,
            headroomMultiplier: Double = 2.5
        ): String? {
            if (modelBytes <= 0L) return "the model file is empty"
            if (availableBytes == 0L) return null // we cannot read memory; let the load attempt proceed
            val required = (modelBytes * headroomMultiplier).toLong()
            return if (availableBytes < required) {
                val modelMb = modelBytes / (1024 * 1024)
                val requiredMb = required / (1024 * 1024)
                val availableMb = availableBytes / (1024 * 1024)
                "Loading a ${modelMb} MB model needs about ${requiredMb} MB of free RAM, " +
                    "but only ${availableMb} MB is available. Pick a smaller model or close " +
                    "other apps before importing."
            } else {
                null
            }
        }
    }
}

/**
 * Decides whether a model of a given on-disk size is safe to load on this device.
 *
 * The numbers here are deliberately conservative: the failure mode is the kernel
 * OOM-killing the process, and on the phones we target that has been observed to
 * take down the whole OS. So the budget is "free RAM must comfortably exceed what
 * the model will need at runtime", with a 1.5× headroom for the Vosk path and 2×
 * for the LiteRT path (LiteRT memory-maps the model and then allocates KV cache
 * for the context window on top of it).
 */
@Singleton
class MemoryBudget @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceMemory {
    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override val totalMemoryBytes: Long
        get() = activityManager.let { manager ->
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.totalMem
        }

    override val availableMemoryBytes: Long
        get() = activityManager.let { manager ->
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.availMem
        }
}
