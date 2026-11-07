package com.example.voiceassistant.generation.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier picker decides which model a device can actually run. Getting this
 * wrong means the kernel OOM-kills the app, so the arithmetic is pinned here.
 */
class ModelTierPickerTest {

    private val mb = 1024L * 1024L

    @Test
    fun `a device with lots of free memory gets the largest tier`() {
        val tier = ModelTierPicker.tierFor(12_000L * mb)
        assertEquals(ModelTierPicker.Tier.MEDIUM, tier)
    }

    @Test
    fun `a device with moderate free memory gets the small tier`() {
        // The 2.5× headroom means a 2.4 GB model needs about 6 GB of free
        // RAM. 6.5 GB is the first whole number that clears it.
        val tier = ModelTierPicker.tierFor(6_500L * mb)
        assertEquals(ModelTierPicker.Tier.SMALL, tier)
    }

    @Test
    fun `just under the small tier boundary falls back to tiny`() {
        // 5 GB / 2.5 = 2 GB of usable headroom, which clears the 1.2 GB
        // TINY model but not the 2.4 GB SMALL one.
        val tier = ModelTierPicker.tierFor(5_000L * mb)
        assertEquals(ModelTierPicker.Tier.TINY, tier)
    }

    @Test
    fun `a device with little free memory gets no tier`() {
        // 2 GB / 2.5 = 800 MB, which does not even clear the TINY model.
        assertNull(ModelTierPicker.tierFor(2_000L * mb))
    }

    @Test
    fun `a device with almost no free memory gets no tier`() {
        assertNull(ModelTierPicker.tierFor(100L * mb))
    }

    @Test
    fun `unknown free memory yields no tier rather than a wrong guess`() {
        assertNull(ModelTierPicker.tierFor(0L))
    }

    @Test
    fun `a model that fits is accepted`() {
        // A 1 GB model needs 2.5 GB with the headroom applied.
        assertNull(ModelTierPicker.refusalReason(availableBytes = 4_000L * mb, modelBytes = 1_000L * mb))
    }

    @Test
    fun `a model that does not fit is refused with a message naming both numbers`() {
        val reason = ModelTierPicker.refusalReason(
            availableBytes = 1_000L * mb,
            modelBytes = 2_000L * mb
        )
        assertTrue(reason != null)
        val text = reason!!
        assertTrue(text, text.contains("2048") || text.contains("2000"))
        assertTrue(text, text.contains("available"))
    }

    @Test
    fun `an empty model file is refused`() {
        val reason = ModelTierPicker.refusalReason(availableBytes = 8_000L * mb, modelBytes = 0L)
        assertEquals("the model file is empty", reason)
    }

    @Test
    fun `unknown free memory lets the load attempt proceed`() {
        // We cannot read the memory, so refusing on a guess would be worse
        // than trying.
        assertNull(ModelTierPicker.refusalReason(availableBytes = 0L, modelBytes = 2_000L * mb))
    }

    @Test
    fun `the recommendation states the honest cold start time`() {
        val text = ModelTierPicker.recommendation(availableBytes = 8_000L * mb, totalBytes = 8_000L * mb)
        assertTrue(text, text.contains("5–15 seconds"))
    }
}
