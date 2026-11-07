package com.example.voiceassistant.generation.llama

/**
 * Picks a local model size that actually fits in the device's RAM.
 *
 * The tiers are deliberately conservative. Loading a model that is too large
 * for the device does not fail cleanly: the kernel OOM-killer takes the app
 * out, and on some phones it takes the whole OS with it. So the picker works
 * from *available* memory and applies a generous headroom multiplier.
 *
 * The headroom is larger than the model's own file size because llama.cpp
 * memory-maps the weights (so the file size is a floor, not a ceiling) and then
 * allocates a KV cache for the context window on top of them.
 */
object ModelTierPicker {

    /** A model size class. */
    enum class Tier(
        val label: String,
        val maxModelBytes: Long,
        val approximateParameters: String
    ) {
        /** ~0.5–1 GB on disk: a 1B-class model at Q4. */
        TINY("Tiny", 1_200_000_000L, "≈1B params, Q4"),

        /** ~1.5–2 GB on disk: a 3B-class model at Q4. */
        SMALL("Small", 2_400_000_000L, "≈3B params, Q4"),

        /** ~3–4.5 GB on disk: a 7–8B-class model at Q4. */
        MEDIUM("Medium", 4_600_000_000L, "≈7–8B params, Q4")
    }

    /**
     * Headroom applied to the model's on-disk size.
     *
     * 2.5× was chosen after 2× proved too tight on the user's device: a 2 GB
     * model on 4 GB of free RAM still OOM-killed the phone. The mapped pages
     * count against the process, and the KV cache adds roughly 10% of the
     * model size again on top.
     */
    const val HEADROOM_MULTIPLIER: Double = 2.5

    /** The largest tier whose model comfortably fits in [availableBytes] of RAM. */
    fun tierFor(availableBytes: Long): Tier? {
        if (availableBytes <= 0L) return null
        val usable = (availableBytes / HEADROOM_MULTIPLIER).toLong()
        return Tier.entries.lastOrNull { usable >= it.maxModelBytes }
    }

    /**
     * Whether a model of [modelBytes] can be loaded right now.
     *
     * Returns `null` when it fits, otherwise a message naming both the
     * requirement and what was actually available.
     */
    fun refusalReason(availableBytes: Long, modelBytes: Long): String? {
        if (modelBytes <= 0L) return "the model file is empty"
        if (availableBytes <= 0L) return null // unknown; let the load attempt proceed
        val required = (modelBytes * HEADROOM_MULTIPLIER).toLong()
        if (availableBytes < required) {
            val modelMb = modelBytes / (1024 * 1024)
            val requiredMb = required / (1024 * 1024)
            val availableMb = availableBytes / (1024 * 1024)
            return "Loading a ${modelMb} MB model needs about ${requiredMb} MB of free RAM, " +
                "but only ${availableMb} MB is available. Pick a smaller model or close " +
                "other apps first."
        }
        return null
    }

    /** A short, user-facing description of what this device can run. */
    fun recommendation(availableBytes: Long, totalBytes: Long): String {
        val tier = tierFor(availableBytes)
            ?: return "Not enough free memory to run a local model right now."
        val freeMb = availableBytes / (1024 * 1024)
        val totalMb = totalBytes / (1024 * 1024)
        return "Recommended: ${tier.label} (${tier.approximateParameters}). " +
            "This device has ${totalMb} MB of RAM with ${freeMb} MB free. " +
            "A model of this size takes 5–15 seconds to load the first time."
    }
}
