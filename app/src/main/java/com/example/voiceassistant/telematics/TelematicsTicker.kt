package com.example.voiceassistant.telematics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the live-telematics snapshot stream. The collector reads from several sources
 * (ActivityManager, /proc/stat, /proc/[pid]/stat, BatteryManager, PowerManager), each
 * of which is cheap individually but adds up at high frequencies. 1 Hz is the right
 * default: it catches leaks, throttling, and charging changes without spending 5–10%
 * of CPU on telemetry alone.
 *
 * The same class also keeps a small ring buffer of CPU samples so the screen can
 * render a sparkline without holding the full 1 Hz flow in memory.
 *
 * The ticker is **lazy**: it does not start reading the device until something
 * actually subscribes to [flow]. When the last subscriber leaves, the loop is
 * cancelled after a short grace period. This keeps unit tests that only construct
 * the view model (and never open the diagnose screen) from spinning up an infinite
 * 1 Hz coroutine in the test dispatcher — which previously caused
 * [kotlinx.coroutines.test.UncompletedCoroutinesError] and a series of
 * OutOfMemoryError in the Robolectric Compose render tests that share the same
 * test JVM fork.
 *
 * In production both [flow] and [cpuHistory] are collected by the diagnose screen
 * for as long as it is visible, so the loop runs continuously while the user is
 * looking at the tab.
 */
class TelematicsTicker(
    private val collector: SystemMetricsCollector,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 1_000L
) {
    private val cpuRing = ArrayDeque<Float>(CPU_HISTORY_SIZE)
    private val _cpuHistory = MutableStateFlow(FloatArray(0))
    /** Last 60 CPU samples (oldest first) for the sparkline. */
    val cpuHistory: StateFlow<FloatArray> = _cpuHistory.asStateFlow()

    /**
     * The snapshot stream. Starts producing values on first subscription, stops a
     * short while after the last subscriber leaves. The initial value is the empty
     * placeholder so screens that render the flow before the first sample have
     * something to show.
     */
    val flow: StateFlow<TelematicsSnapshot> = flow {
        while (true) {
            val snap: TelematicsSnapshot = runCatching { collector.snapshot() }
                .getOrNull() ?: emptySnapshot()
            if (cpuRing.size == CPU_HISTORY_SIZE) cpuRing.removeFirst()
            cpuRing.addLast(snap.cpu.overallPercent)
            _cpuHistory.value = cpuRing.toFloatArray()
            emit(snap)
            delay(intervalMillis)
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1_000L),
        initialValue = emptySnapshot()
    )

    companion object {
        const val CPU_HISTORY_SIZE = 60
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
