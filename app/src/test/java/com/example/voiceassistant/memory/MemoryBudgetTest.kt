package com.example.voiceassistant.memory

import com.example.voiceassistant.memory.DeviceMemory.Companion.refusalReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The math the memory check uses is the safety net against an OOM kill. If the
 * numbers are wrong, the safety net doesn't work and the phone reboots.
 */
class MemoryBudgetTest {

    private fun mb(count: Int) = count.toLong() * 1024L * 1024L

    @Test
    fun `a model that fits in available RAM is allowed`() {
        val available = mb(8_000)
        val model = mb(2_000)
        assertNull(refusalReason(available, model))
    }

    @Test
    fun `a 2GB reply model is refused on a phone with 4GB free RAM`() {
        // This is the user's actual failure case: 8 GB phone, ~4 GB free, 2 GB model.
        val available = mb(4_000)
        val model = mb(2_000)
        val reason = refusalReason(available, model, headroomMultiplier = 2.0)
        assertNotNull("a 2 GB model needs 4 GB headroom, only 4 GB free → should refuse", reason)
        assertTrue(reason!!.contains("2048"))
        assertTrue(reason.contains("4000"))
    }

    @Test
    fun `the Vosk headroom is more generous than LiteRT`() {
        val available = mb(3_000)
        val model = mb(2_000)
        // 2.0× headroom (LiteRT): needs 4000 MB → refuse.
        assertNotNull(refusalReason(available, model, headroomMultiplier = 2.0))
        // 1.5× headroom (Vosk): needs 3000 MB → allow.
        assertNull(refusalReason(available, model, headroomMultiplier = 1.5))
    }

    @Test
    fun `a zero-byte model is refused with the empty-file reason`() {
        assertEquals("the model file is empty", refusalReason(mb(8_000), 0L))
    }

    @Test
    fun `an unknown available memory count is allowed to proceed`() {
        // getMemoryInfo may return 0 in unusual states; we don't want to block the load
        // on a transient read failure.
        assertNull(refusalReason(0L, mb(2_000)))
    }

    @Test
    fun `the 584 MB int4 model is allowed on 4 GB free RAM`() {
        // The recommended LiteRT model is ~584 MB. 2× headroom = 1.17 GB; 4 GB available → fine.
        val available = mb(4_000)
        val model = mb(584)
        assertNull(refusalReason(available, model))
    }

    @Test
    fun `the lgraph 2_3GB Vosk model is allowed on 4 GB free RAM`() {
        // Vosk uses 1.5× headroom. 2.3 GB × 1.5 = 3.45 GB; 4 GB free → fine.
        val available = mb(4_000)
        val model = mb(2_300)
        assertNull(refusalReason(available, model, headroomMultiplier = 1.5))
    }
}
