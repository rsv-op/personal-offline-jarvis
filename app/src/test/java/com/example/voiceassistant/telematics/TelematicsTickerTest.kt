package com.example.voiceassistant.telematics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelematicsTickerTest {

    @Test
    fun `cpu history is bounded and ordered oldest first`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val counter = Counter()
            val ticker = TelematicsTicker(counter, scope, intervalMillis = 5L)
            // Wait for at least CPU_HISTORY_SIZE + 5 samples to push the oldest out.
            delay(400L)
            val samples = ticker.cpuHistory.value
            assertTrue("expected many samples, got ${samples.size}", samples.size >= 5)
            assertTrue("history must not exceed CPU_HISTORY_SIZE", samples.size <= TelematicsTicker.CPU_HISTORY_SIZE)
            // Latest sample should be the most recent collector value.
            assertEquals(counter.lastCpu.toFloat(), samples.last(), 0.01f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an empty collector returns the empty snapshot placeholder`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val ticker = TelematicsTicker(FailingCollector(), scope, intervalMillis = 50L)
            // First sample lands almost immediately. Even before that, the value is
            // the empty placeholder (zeros), not a crash.
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
            return emptySnapshot().copy(
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

    private fun emptySnapshot(): TelematicsSnapshot = TelematicsSnapshot(
        memory = TelematicsSnapshot.MemorySection(0L, 0L, 0L, false, 0L, 0L, 0L, 0L, 0L),
        storage = TelematicsSnapshot.StorageSection(0L, 0L, 0L),
        cpu = TelematicsSnapshot.CpuSection(0f, 0f, emptyList(), 0L, 0L, 0L),
        battery = TelematicsSnapshot.BatterySection(-1, false, "unknown", 0f, 0, 0L, "unknown"),
        thermal = TelematicsSnapshot.ThermalSection("UNAVAILABLE", -1),
        process = TelematicsSnapshot.ProcessSection(0L, "", "?", 0L, 0, 0)
    )
}
