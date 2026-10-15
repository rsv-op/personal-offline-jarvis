package com.example.voiceassistant.aimatrix

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source of the trained intent model, so the brain can be unit-tested without an
 * Android asset manager.
 */
interface IntentModelSource {
    /** Returns the classifier, or `null` if the model is missing or unreadable. */
    suspend fun classifier(): AiMatrixClassifier?

    /** Why the model is unavailable, when it is. */
    val lastError: String?
}

/**
 * Loads the bundled ai-matrix weights from assets and keeps them in memory.
 *
 * The artifact is compiled into the APK (`app/src/main/assets/ai_matrix_model.json`),
 * so the intent brain is available on first launch with no download, no import step and
 * no internet permission. Parsing happens once, off the main thread.
 */
@Singleton
class AiMatrixModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) : IntentModelSource {
    private val loadMutex = Mutex()

    @Volatile
    private var cached: AiMatrixClassifier? = null

    @Volatile
    override var lastError: String? = null
        private set

    /** True once the bundled model has been parsed successfully. */
    val isLoaded: Boolean get() = cached != null

    /** Returns the classifier, or `null` if the asset is missing or unreadable. */
    override suspend fun classifier(): AiMatrixClassifier? {
        cached?.let { return it }
        return loadMutex.withLock {
            cached?.let { return@withLock it }
            val loaded = readAsset()
            cached = loaded
            loaded
        }
    }

    private suspend fun readAsset(): AiMatrixClassifier? = withContext(Dispatchers.IO) {
        try {
            val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val classifier = AiMatrixClassifier.fromJson(json)
            lastError = null
            classifier
        } catch (error: Exception) {
            lastError = error.message ?: "The bundled intent model could not be read"
            null
        }
    }

    companion object {
        const val ASSET_NAME = "ai_matrix_model.json"
    }
}
