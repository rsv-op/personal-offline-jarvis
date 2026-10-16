package com.example.voiceassistant.telematics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the live-telematics snapshot stream. The collector reads from several sources
 * (ActivityManager, /proc/stat, /proc/[pid]/stat, BatteryManager, PowerManager), each
 * of which is cheap individually but adds up at high frequencies. 1 Hz is the right
 * default: it catches leaks, throttling, and charging changes without spending 5–10%
 * of CPU on telemetry alone.
 *
 * The same class also keeps a small ring buffer of CPU samples so the screen can
 * render a sparkline without holding the full 1 Hz flow in memory.
 */
class TelematicsTicker(
    private val collector: SystemMetricsCollector,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 1_000L
) {
    private val _flow = MutableStateFlow(emptySnapshot())
    val flow: StateFlow<TelematicsSnapshot> = _flow.asStateFlow()

    private val cpuRing = ArrayDeque<Float>(CPU_HISTORY_SIZE)
    private val _cpuHistory = MutableStateFlow(FloatArray(0))
    val cpuHistory: StateFlow<FloatArray> = _cpuHistory.asStateFlow()

    init {
        scope.launch {
            while (true) {
                val snap = runCatching { collector.snapshot() }.getOrElse { emptySnapshot() }
                _flow.value = snap
                if (cpuRing.size == CPU_HISTORY_SIZE) cpuRing.removeFirst()
                cpuRing.addLast(snap.cpu.overallPercent)
                _cpuHistory.value = cpuRing.toFloatArray()
                delay(intervalMillis)
            }
        }
    }

    companion object {
        const val CPU_HISTORY_SIZE = 60

        /**
         * Convenience factory that exposes a CPU-history flow tied to a fresh ticker.
         * Used by the view model so it can offer both the snapshot and the sparkline
         * data without two parallel coroutines.
         */
        fun cpuHistoryFor(
            scope: CoroutineScope,
            collector: SystemMetricsCollector,
            intervalMillis: Long = 1_000L
        ): StateFlow<FloatArray> = TelematicsTicker(collector, scope, intervalMillis).cpuHistory
    }
}

private fun emptySnapshot(): TelematicsSnapshot = TelematicsSnapshot(
    memory = TelematicsSnapshot.MemorySection(0L, 0L, 0L, false, 0L, 0L, 0L),
    storage = TelematicsSnapshot.StorageSection(0L, 0L, 0L),
    cpu = TelematicsSnapshot.CpuSection(0f, 0f, emptyList(), 0L, 0L, 0L),
    battery = TelematicsSnapshot.BatterySection(-1, false, "unknown", 0f, 0, 0L, "unknown"),
    thermal = TelematicsSnapshot.ThermalSection("UNAVAILABLE", -1),
    process = TelematicsSnapshot.ProcessSection(0L, "", "?", 0L, 0, 0)
)
