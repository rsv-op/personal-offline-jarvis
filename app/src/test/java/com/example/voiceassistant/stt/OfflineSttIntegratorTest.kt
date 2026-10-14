package com.example.voiceassistant.stt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSttIntegratorTest {
    private lateinit var mockEngine: SttEngine
    private lateinit var audioStream: MutableSharedFlow<ShortArray>
    private lateinit var recycledFrames: MutableList<ShortArray>
    private lateinit var capturedEvents: MutableList<SttEvent>
    private lateinit var dispatcher: TestDispatcher
    private lateinit var integrator: OfflineSttIntegrator

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        mockEngine = mock(SttEngine::class.java)
        audioStream = MutableSharedFlow(extraBufferCapacity = 10)
        recycledFrames = mutableListOf()
        capturedEvents = mutableListOf()
        integrator = OfflineSttIntegrator(
            engine = mockEngine,
            audioStream = audioStream,
            frameRecycler = { recycledFrames.add(it) },
            eventDispatcher = { capturedEvents.add(it) },
            dispatcher = dispatcher
        )
    }

    @Test
    fun `emits partial and final results and recycles frames`() = runTest(dispatcher) {
        val frame = shortArrayOf(1, 2, 3)
        `when`(mockEngine.processAudio(frame)).thenReturn(false)
        `when`(mockEngine.getPartialText()).thenReturn("turn on")
        `when`(mockEngine.getFinalText()).thenReturn("turn on the lights")

        integrator.loadModel("/model")
        runCurrent()
        integrator.startTranscription()
        runCurrent()
        audioStream.emit(frame)
        runCurrent()

        assertTrue(capturedEvents.contains(SttEvent.OnPartialResult("turn on")))
        assertEquals(1, recycledFrames.size)
        integrator.stopTranscription()
        advanceUntilIdle()
        assertTrue(capturedEvents.contains(SttEvent.OnFinalResult("turn on the lights")))
    }

    @Test
    fun `empty final result emits silence event`() = runTest(dispatcher) {
        integrator.loadModel("/model")
        runCurrent()
        `when`(mockEngine.getFinalText()).thenReturn("")
        integrator.startTranscription()
        runCurrent()
        integrator.stopTranscription()
        advanceUntilIdle()
        assertTrue(capturedEvents.contains(SttEvent.OnEmptyAcoustic))
    }

    @Test
    fun `initialization failure emits error`() = runTest(dispatcher) {
        val failure = RuntimeException("Crash")
        doThrow(failure).`when`(mockEngine).initialize(anyString())

        integrator.loadModel("/model")
        advanceUntilIdle()

        val error = capturedEvents.filterIsInstance<SttEvent.OnError>().single()
        assertTrue(error.exception.message.orEmpty().contains("Failed to initialize STT model"))
    }

    @Test
    fun `starting before initialization emits error`() = runTest(dispatcher) {
        integrator.startTranscription()
        assertEquals(
            "STT Engine is not initialized.",
            (capturedEvents.single() as SttEvent.OnError).exception.message
        )
    }
}
