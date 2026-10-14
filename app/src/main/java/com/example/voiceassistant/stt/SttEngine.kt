package com.example.voiceassistant.stt

/** Adapter contract for an offline STT engine such as Vosk, whisper.cpp, or Sherpa-ONNX. */
interface SttEngine {
    @Throws(Exception::class)
    fun initialize(modelPath: String)

    fun processAudio(audioData: ShortArray): Boolean
    fun getPartialText(): String
    fun getFinalText(): String
    fun reset()
    fun release()
}
