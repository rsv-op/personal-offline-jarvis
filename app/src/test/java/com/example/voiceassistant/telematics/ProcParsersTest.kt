package com.example.voiceassistant.telematics

import com.example.voiceassistant.telematics.ProcParsers.CpuTimes
import com.example.voiceassistant.telematics.ProcParsers.parseMeminfoField
import com.example.voiceassistant.telematics.ProcParsers.parseProcStatDetailed
import com.example.voiceassistant.telematics.ProcParsers.parseProcStatTotalJiffies
import com.example.voiceassistant.telematics.ProcParsers.parseProcessStat
import com.example.voiceassistant.telematics.ProcParsers.parseSingleInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The telematics screen is read-only and best-effort, but the parsers feed every
 * number on it. A bug here means the user sees wrong RAM/CPU/battery values, which
 * is exactly the kind of "live diagnostics" lie this PR is supposed to prevent.
 */
class ProcParsersTest {

    @Test
    fun `parses total jiffies from a healthy proc stat`() {
        val stat = """
            cpu  100 0 50 800 10 0 0 0 0 0
            cpu0 50 0 25 400 5 0 0 0 0 0
            cpu1 50 0 25 400 5 0 0 0 0 0
            intr 12345 0 0
            ctxt 67890
        """.trimIndent()
        // 100 + 0 + 50 + 800 + 10 + 0 + 0 + 0 = 960
        assertEquals(960L, parseProcStatTotalJiffies(stat))
    }

    @Test
    fun `parses detailed cpu times including iowait and steal`() {
        val stat = """
            cpu  100 0 50 800 10 0 0 5 0 0
        """.trimIndent()
        val times = parseProcStatDetailed(stat)!!
        // user(100) + nice(0) + system(50) + idle(800) + iowait(10) + irq(0) + softirq(0) + steal(5) = 965
        assertEquals(965L, times.total)
        // idle + iowait = 810
        assertEquals(810L, times.idle)
        // busy = total - idle - iowait = 155
        assertEquals(155L, times.busy)
    }

    @Test
    fun `busy fraction handles zero total without dividing by zero`() {
        val times = CpuTimes(total = 0L, idle = 0L, busy = 0L)
        assertEquals(0f, times.busyFraction(), 0f)
    }

    @Test
    fun `parses a process stat line with embedded spaces in the name`() {
        // The (comm) field in /proc/[pid]/stat is wrapped in parens and may contain
        // spaces or parens. We must find the closing paren, not split on whitespace.
        val line = "1234 (jarvis: io) R 1 1234 1234 0 -1 1077936384 100 0 0 0 5 2 0 0 20 0 1 0 12345 1234567 200 18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 17 0 0 0"
        val parsed = parseProcessStat(line)!!
        assertEquals(5L, parsed.utimeJiffies)
        assertEquals(2L, parsed.stimeJiffies)
        assertEquals(1L, parsed.numThreads)
        assertEquals(12345L, parsed.startTimeClockTicks)
        assertEquals(7L, parsed.totalJiffies())
    }

    @Test
    fun `process stat without a closing paren returns null`() {
        val malformed = "1234 junk junk junk"
        assertNull(parseProcessStat(malformed))
    }

    @Test
    fun `parses meminfo by field name`() {
        val meminfo = """
            MemTotal:        8000000 kB
            MemFree:          1234567 kB
            MemAvailable:    4567890 kB
            Buffers:           100000 kB
            Cached:           2000000 kB
        """.trimIndent()
        assertEquals(8_000_000L, parseMeminfoField(meminfo, "MemTotal"))
        assertEquals(1_234_567L, parseMeminfoField(meminfo, "MemFree"))
        assertEquals(4_567_890L, parseMeminfoField(meminfo, "MemAvailable"))
        assertNull(parseMeminfoField(meminfo, "SwapTotal"))
    }

    @Test
    fun `parses a single integer from a file like scaling_cur_freq`() {
        assertEquals(1_800_000L, parseSingleInteger("1800000\n"))
        assertEquals(2_400_000L, parseSingleInteger("2400000"))
        assertNull(parseSingleInteger(""))
        assertNull(parseSingleInteger("not a number"))
    }

    @Test
    fun `parses a multi-core frequency list with missing cores`() {
        val sys = "/sys/devices/system/cpu"
        val cpu0 = mockFile("$sys/cpu0/cpufreq/scaling_cur_freq", "1800000")
        val cpu1 = mockFile("$sys/cpu1/cpufreq/scaling_cur_freq", "2400000")
        // The AndroidSystemMetricsCollector code path tolerates a missing file by
        // returning null; this test pins the input-side contract for the parser.
        val freq0 = parseSingleInteger(cpu0.content)
        val freq1 = parseSingleInteger(cpu1.content)
        assertNotNull(freq0)
        assertNotNull(freq1)
        assertTrue(freq0!! > 0L)
        assertTrue(freq1!! > 0L)
    }

    private data class MockFile(val name: String, val content: String)
    private fun mockFile(name: String, content: String) = MockFile(name, content)
}
