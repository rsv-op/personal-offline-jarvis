package com.example.voiceassistant.orchestrator

import android.content.Context
import android.util.Log
import com.example.voiceassistant.aimatrix.AiMatrixModelStore
import com.example.voiceassistant.aimatrix.IntentModelSource
import com.example.voiceassistant.aimatrix.OnDeviceBrain
import com.example.voiceassistant.execution.AlarmExecutor
import com.example.voiceassistant.execution.AssistantStatusProvider
import com.example.voiceassistant.execution.FlashlightExecutor
import com.example.voiceassistant.execution.StatusExecutor
import com.example.voiceassistant.execution.TimerExecutor
import com.example.voiceassistant.execution.TtsController
import com.example.voiceassistant.generation.LiteRtLocalTextGenerator
import com.example.voiceassistant.generation.LocalGenerationModelStore
import com.example.voiceassistant.generation.LocalTextGenerator
import com.example.voiceassistant.generation.RuleBasedLocalTextGenerator
import com.example.voiceassistant.nlp.IntentRouter
import com.example.voiceassistant.nlp.NlpProcessor
import com.example.voiceassistant.nlp.TextNormalizer
import com.example.voiceassistant.stt.SttEngine
import com.example.voiceassistant.stt.VoskSttEngine
import com.example.voiceassistant.vault.AndroidKeystoreVaultCipher
import com.example.voiceassistant.vault.JarvisMemory
import com.example.voiceassistant.vault.SecureVault
import com.example.voiceassistant.vault.VaultCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Dispatcher used by the speech-recognition pipeline. Exposed as a qualifier so tests
 * can drive transcription with a deterministic test dispatcher instead of a real thread.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SpeechDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AssistantDiModule {
    @Provides
    fun provideTextNormalizer() = TextNormalizer()

    @Provides
    fun provideIntentRouter() = IntentRouter()

    @Provides
    @Singleton
    fun provideNlpProcessor(
        normalizer: TextNormalizer,
        router: IntentRouter,
        brain: OnDeviceBrain
    ) = NlpProcessor(normalizer, router, brain)

    @Provides
    @Singleton
    fun provideTtsController(@ApplicationContext context: Context) = TtsController(context)

    @Provides
    fun provideFlashlightExecutor() = FlashlightExecutor()

    @Provides
    fun provideAlarmExecutor() = AlarmExecutor()

    @Provides
    fun provideTimerExecutor() = TimerExecutor()

    @Provides
    fun provideStatusExecutor(provider: AssistantStatusProvider) =
        StatusExecutor(assistantStatus = { provider.describe() })

    @Provides
    @Singleton
    fun provideSttEngine(): SttEngine = VoskSttEngine()

    @Provides
    @SpeechDispatcher
    fun provideSpeechDispatcher(): kotlinx.coroutines.CoroutineDispatcher =
        kotlinx.coroutines.Dispatchers.Default

    @Provides
    @Singleton
    fun provideLocalTextGenerator(
        ruleBased: RuleBasedLocalTextGenerator
    ): LocalTextGenerator = ruleBased

    /** The vault key lives in the device's secure element, not in the APK or in a file. */
    @Provides
    @Singleton
    fun provideVaultCipher(): VaultCipher = AndroidKeystoreVaultCipher()

    @Provides
    @Singleton
    fun provideSecureVault(@ApplicationContext context: Context, cipher: VaultCipher) =
        SecureVault(
            directory = File(context.filesDir, "vault"),
            cipher = cipher,
            onError = { message -> Log.w(TAG, message) }
        )

    @Provides
    @Singleton
    fun provideJarvisMemory(vault: SecureVault) = JarvisMemory(vault)

    @Provides
    @Singleton
    fun provideIntentModelSource(modelStore: AiMatrixModelStore): IntentModelSource = modelStore

    @Provides
    @Singleton
    fun provideOnDeviceBrain(modelSource: IntentModelSource, memory: JarvisMemory) =
        OnDeviceBrain(modelSource, memory)

    private const val TAG = "JarvisVault"
}
