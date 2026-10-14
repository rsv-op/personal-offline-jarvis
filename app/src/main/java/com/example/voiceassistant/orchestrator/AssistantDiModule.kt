package com.example.voiceassistant.orchestrator

import android.content.Context
import com.example.voiceassistant.execution.AlarmExecutor
import com.example.voiceassistant.execution.FlashlightExecutor
import com.example.voiceassistant.execution.TtsController
import com.example.voiceassistant.nlp.IntentRouter
import com.example.voiceassistant.nlp.NlpProcessor
import com.example.voiceassistant.nlp.TextNormalizer
import com.example.voiceassistant.stt.SttEngine
import com.example.voiceassistant.stt.VoskSttEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AssistantDiModule {
    @Provides
    fun provideTextNormalizer() = TextNormalizer()

    @Provides
    fun provideIntentRouter() = IntentRouter()

    @Provides
    fun provideNlpProcessor(normalizer: TextNormalizer, router: IntentRouter) =
        NlpProcessor(normalizer, router)

    @Provides
    @Singleton
    fun provideTtsController(@ApplicationContext context: Context) = TtsController(context)

    @Provides
    fun provideFlashlightExecutor() = FlashlightExecutor()

    @Provides
    fun provideAlarmExecutor() = AlarmExecutor()

    @Provides
    @Singleton
    fun provideSttEngine(): SttEngine = VoskSttEngine()
}
