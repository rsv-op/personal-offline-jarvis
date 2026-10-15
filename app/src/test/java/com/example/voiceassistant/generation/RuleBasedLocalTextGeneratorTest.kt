package com.example.voiceassistant.generation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Pure-JVM tests for [RuleBasedLocalTextGenerator]. The generator must:
 *
 *  - delegate to the LiteRT engine when a model is installed, so the upgrade path is unchanged,
 *  - otherwise produce a short, deterministic, *honest* reply that says what it can and can't do,
 *  - never claim to have taken a device action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class RuleBasedLocalTextGeneratorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val modelStore: LocalGenerationModelStore = mock()
    private val liteRt: LiteRtLocalTextGenerator = mock()
    private lateinit var generator: RuleBasedLocalTextGenerator

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        generator = RuleBasedLocalTextGenerator(modelStore, providerOf(liteRt))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `delegates to the LiteRT generator when a model is installed`() = runTest {
        whenever(modelStore.installedModelPath()).thenReturn("/data/local/lm/model.litertlm")
        whenever(liteRt.generateReply(any())).thenReturn("from the on-device model")

        val reply = generator.generateReply("what time is it?")

        assertEquals("from the on-device model", reply)
        verify(liteRt).generateReply("what time is it?")
    }

    @Test
    fun `a greeting gets a short acknowledgement without mentioning the model`() = runTest {
        noModel()
        val reply = generator.generateReply("hello")
        assertEquals("Hello. I am ready when you are.", reply)
        verify(liteRt, never()).generateReply(any())
    }

    @Test
    fun `an identity question introduces the assistant and stays offline`() = runTest {
        noModel()
        val reply = generator.generateReply("Who are you?")
        assertTrue(reply, reply.contains("JARVIS"))
        assertTrue(reply, reply.contains("offline") || reply.contains("on this device"))
    }

    @Test
    fun `a help request lists the supported actions and the model limitation`() = runTest {
        noModel()
        val reply = generator.generateReply("what can you do")
        assertTrue(reply, reply.contains("flashlight"))
        assertTrue(reply, reply.contains("alarm"))
        assertTrue(reply, reply.contains("timer"))
        assertTrue(reply, reply.contains("local reply model"))
    }

    @Test
    fun `an open-ended question is honestly redirected to a model install`() = runTest {
        noModel()
        val reply = generator.generateReply("Why is the sky blue?")
        assertTrue(reply, reply.contains("local reply model"))
        assertTrue(reply, reply.contains(".litertlm"))
    }

    @Test
    fun `a device-action phrasing is refused without claiming to act`() = runTest {
        noModel()
        for (prompt in listOf("turn on the flashlight", "set an alarm", "start a timer")) {
            val reply = generator.generateReply(prompt)
            assertFalse(
                "must not claim to have acted on '$prompt' (was: $reply)",
                reply.contains("done") || reply.contains("set") && reply.contains("now")
            )
            assertTrue(
                "reply should redirect the user for '$prompt' (was: $reply)",
                reply.contains("reply path") || reply.contains("controls on the screen")
            )
        }
    }

    @Test
    fun `a very short utterance asks for a longer sentence rather than guessing`() = runTest {
        noModel()
        val reply = generator.generateReply("hi")
        // 'hi' is in the greeting set; that path is a greeting, not a guess. This test
        // specifically exercises the *length-floor* branch with a 4-character non-greeting.
        val nonGreeting = generator.generateReply("y33")
        assertTrue(nonGreeting, nonGreeting.contains("need a longer sentence"))
    }

    @Test
    fun `an empty or whitespace utterance is acknowledged and the loop is not invoked`() = runTest {
        noModel()
        for (blank in listOf("", "   ", "\n\t")) {
            val reply = generator.generateReply(blank)
            assertEquals("I didn't catch that. Please try again.", reply)
        }
        verify(liteRt, never()).generateReply(any())
    }

    @Test
    fun `an unrecognised sentence echoes the input instead of fabricating an answer`() = runTest {
        noModel()
        val reply = generator.generateReply("Tell me about the lighthouse at Alexandria")
        assertTrue(reply, reply.contains("Tell me about the lighthouse at Alexandria"))
        assertTrue(reply, reply.contains("don't have a local reply model"))
    }

    @Test
    fun `onModelUpdated is delegated to the LiteRT engine`() = runTest {
        generator.onModelUpdated()
        verify(liteRt).onModelUpdated()
    }

    @Test
    fun `ReplyComposer handles punctuation in greetings`() {
        assertEquals("Hello. I am ready when you are.", ReplyComposer.compose("Hello!"))
        assertEquals("Hello. I am ready when you are.", ReplyComposer.compose("  hello.  "))
    }

    private fun noModel() {
        whenever(modelStore.installedModelPath()).thenReturn(null)
    }

    private fun providerOf(delegate: LiteRtLocalTextGenerator): javax.inject.Provider<LiteRtLocalTextGenerator> =
        javax.inject.Provider { delegate }
}
