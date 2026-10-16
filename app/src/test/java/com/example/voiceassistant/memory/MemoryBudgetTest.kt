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
        // The helper divides by 1_048_576 (binary MB), so 2 GB = 2000 in this test's
        // units and 2.5× headroom is 5000; the message is honest about it.
        val available = mb(4_000)
        val model = mb(2_000)
        val reason = refusalReason(available, model, headroomMultiplier = 2.5)
        assertNotNull("a 2 GB model on 4 GB free should be refused with 2.5× headroom", reason)
        assertTrue("reason should name the model size, was: $reason", reason!!.contains("2000"))
        assertTrue("reason should name the required headroom, was: $reason", reason.contains("5000"))
        assertTrue("reason should name the available RAM, was: $reason", reason.contains("4000"))
    }

    @Test
    fun `a 2GB reply model is allowed on a phone with 5GB free RAM`() {
        // 2.5× headroom → requires 5 GB → 5 GB free → allowed (just barely).
        val available = mb(5_000)
        val model = mb(2_000)
        assertNull(refusalReason(available, model, headroomMultiplier = 2.5))
    }

    @Test
    fun `the Vosk headroom is more generous than LiteRT`() {
        val available = mb(3_500)
        val model = mb(2_000)
        // 2.5× headroom (LiteRT): needs 5000 MB → refuse.
        assertNotNull(refusalReason(available, model, headroomMultiplier = 2.5))
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
