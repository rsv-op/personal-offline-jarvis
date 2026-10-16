package com.example.voiceassistant.telematics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelematicsTickerTest {

    @Test
    fun `cpu history is bounded and ordered oldest first`() = runTest {
        val counter = Counter()
        // Use the test scope itself as the ticker's scope so the virtual clock
        // controls the delay() inside the upstream flow. The collector is invoked
        // only while at least one subscriber is alive; the test subscribes via
        // backgroundScope so the test body can finish without leaving an active
        // child job on the test scope.
        val ticker = TelematicsTicker(counter, this, intervalMillis = 5L)
        backgroundScope.launch { ticker.flow.collect { /* keep the ticker subscribed */ } }
        // Advance the virtual clock by 400 ms; with a 5 ms interval this
        // produces ~80 ticks without allocating any real coroutine state.
        advanceTimeBy(400L)
        runCurrent()
        val samples = ticker.cpuHistory.value
        assertTrue("expected many samples, got ${samples.size}", samples.size >= 5)
        assertTrue("history must not exceed CPU_HISTORY_SIZE", samples.size <= TelematicsTicker.CPU_HISTORY_SIZE)
        // Latest sample should be the most recent collector value.
        assertEquals(counter.lastCpu.toFloat(), samples.last(), 0.01f)
    }

    @Test
    fun `an empty collector returns the empty snapshot placeholder`() = runTest {
        // Same as the first test but no subscription: the initial value of the flow
        // is the empty placeholder. The ticker is constructed in a private scope that
        // is cancelled at the end so no upstream coroutine leaks.
        val scope = CoroutineScope(this.coroutineContext + kotlinx.coroutines.SupervisorJob())
        try {
            val ticker = TelematicsTicker(FailingCollector(), scope, intervalMillis = 50L)
            val first = ticker.flow.value
            assertEquals(0L, first.memory.totalBytes)
            assertEquals(0L, first.memory.availableBytes)
            assertEquals("", first.process.packageName)
        } finally {
            scope.cancel()
        }
    }

    /** Test double that records every call and reports a deterministic CPU%. */
    private class Counter : SystemMetricsCollector {
        var calls: Int = 0
        var lastCpu: Double = 0.0
        override fun snapshot(): TelematicsSnapshot {
            calls++
            lastCpu = (calls * 5.0) % 100.0
            return blankSnapshot().copy(
                cpu = TelematicsSnapshot.CpuSection(
                    overallPercent = lastCpu.toFloat(),
                    appPercent = 0f,
                    perCoreFrequenciesKHz = emptyList(),
                    threadCount = 1,
                    uptimeMs = calls * 1000L,
                    awakeMs = calls * 1000L
                )
            )
        }
    }

    /** Collector that throws — the ticker must not propagate. */
    private class FailingCollector : SystemMetricsCollector {
        override fun snapshot(): TelematicsSnapshot = throw RuntimeException("simulated failure")
    }
}

/**
 * File-level helper so the non-inner [Counter] / [FailingCollector] classes
 * (which have no enclosing instance) can construct a blank snapshot.
 */
internal fun blankSnapshot(): TelematicsSnapshot = TelematicsSnapshot(
    memory = TelematicsSnapshot.MemorySection(0L, 0L, 0L, false, 0L, 0L, 0L),
    storage = TelematicsSnapshot.StorageSection(0L, 0L, 0L),
    cpu = TelematicsSnapshot.CpuSection(0f, 0f, emptyList(), 0L, 0L, 0L),
    battery = TelematicsSnapshot.BatterySection(-1, false, "unknown", 0f, 0, 0L, "unknown"),
    thermal = TelematicsSnapshot.ThermalSection("UNAVAILABLE", -1),
    process = TelematicsSnapshot.ProcessSection(0L, "", "?", 0L, 0, 0)
)
