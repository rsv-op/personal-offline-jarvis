package com.example.voiceassistant

import java.io.File

/**
 * Locates repository files from unit tests.
 *
 * Gradle runs unit tests with the module directory (`app/`) as the working directory,
 * but the same tests are occasionally run from the repository root, so a few candidate
 * paths are tried before failing loudly.
 */
object TestFiles {

    fun resolve(vararg candidates: String): File {
        val workingDirectory = File(".").absoluteFile
        for (candidate in candidates) {
            val file = File(candidate).let { if (it.isAbsolute) it else File(workingDirectory, candidate) }
            if (file.isFile) return file
            // Also try one level up, so `app/` and the repository root both work.
            val parentCandidate = File(workingDirectory.parentFile ?: workingDirectory, candidate)
            if (parentCandidate.isFile) return parentCandidate
        }
        throw AssertionError(
            "Could not find any of ${candidates.toList()} from $workingDirectory. " +
                "Unit tests expect the repository layout to be intact."
        )
    }

    /** The ai-matrix featurization snapshot produced by `python -m ai_matrix.golden`. */
    fun goldenFeatures(): File = resolve(
        "ai-matrix/tests/golden_features.json",
        "../ai-matrix/tests/golden_features.json",
        "../../ai-matrix/tests/golden_features.json"
    )

    /** The trained intent model bundled into the APK. */
    fun aiMatrixAsset(): File = resolve(
        "src/main/assets/ai_matrix_model.json",
        "app/src/main/assets/ai_matrix_model.json",
        "../app/src/main/assets/ai_matrix_model.json"
    )
}
