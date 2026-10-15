package com.example.voiceassistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.voiceassistant.nlp.NlpProcessor
import com.example.voiceassistant.statemachine.AssistantUiState
import com.example.voiceassistant.vault.JarvisMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `listening state renders orb and transcript`() {
        composeTestRule.setContent {
            AssistantScreen(
                uiState = AssistantUiState.Listening,
                transcript = "hello",
                onConfirm = {},
                onDeny = {},
                onManualWake = {}
            )
        }

        composeTestRule.onNodeWithTag("StatusText").assertTextEquals("Listening offline — tap to stop")
        composeTestRule.onNodeWithTag("AssistantOrb").assertIsDisplayed()
        composeTestRule.onNodeWithTag("TranscriptText").assertIsDisplayed().assertTextEquals("hello")
    }

    @Test
    fun `confirmation state displays buttons and invokes callbacks`() {
        var confirmed = false
        var denied = false
        composeTestRule.setContent {
            AssistantScreen(
                uiState = AssistantUiState.NeedsConfirmation,
                transcript = "Set alarm?",
                onConfirm = { confirmed = true },
                onDeny = { denied = true },
                onManualWake = {},
                confirmationPrompt = "Do you want me to set the alarm for 07:00?"
            )
        }

        composeTestRule.onNodeWithTag("ConfirmationPrompt")
            .performScrollTo()
            .assertTextEquals("Do you want me to set the alarm for 07:00?")
        composeTestRule.onNodeWithTag("ConfirmButton").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("DenyButton").performScrollTo().performClick()
        assertTrue(confirmed)
        assertTrue(denied)
    }

    @Test
    fun `blank transcript hides transcript text`() {
        composeTestRule.setContent {
            AssistantScreen(
                uiState = AssistantUiState.Idle,
                transcript = "",
                onConfirm = {},
                onDeny = {},
                onManualWake = {}
            )
        }
        val transcriptNodes = composeTestRule.onAllNodesWithTag("TranscriptText").fetchSemanticsNodes()
        assertEquals(0, transcriptNodes.size)
    }

    @Test
    fun `status panel shows what is installed`() {
        composeTestRule.setContent {
            AssistantScreen(
                uiState = AssistantUiState.Idle,
                transcript = "",
                onConfirm = {},
                onDeny = {},
                onManualWake = {},
                modelStatus = "Offline speech model ready",
                modelSizeMb = 41.0,
                brainStatus = "intent brain ready",
                vaultStatus = "0 taught phrases • vault on StrongBox secure element",
                replyModelStatus = "Local reply model not installed"
            )
        }

        composeTestRule.onNodeWithTag("ModelStatusText")
            .performScrollTo()
            .assertTextEquals("Offline speech model ready (41 MB)")
        composeTestRule.onNodeWithTag("BrainStatusText")
            .performScrollTo()
            .assertTextEquals("intent brain ready")
        composeTestRule.onNodeWithTag("VaultStatusText")
            .performScrollTo()
            .assertTextEquals("0 taught phrases • vault on StrongBox secure element")
    }

    @Test
    fun `teaching a phrase reports the chosen action`() {
        var taughtPhrase: String? = null
        var taughtLabel: String? = null
        composeTestRule.setContent {
            AssistantScreen(
                uiState = AssistantUiState.Idle,
                transcript = "",
                onConfirm = {},
                onDeny = {},
                onManualWake = {},
                onTeach = { phrase, label ->
                    taughtPhrase = phrase
                    taughtLabel = label
                }
            )
        }

        composeTestRule.onNodeWithTag("TeachPhraseField")
            .performScrollTo()
            .performTextInput("make it bright")
        composeTestRule.onNodeWithTag("Teach_FLASHLIGHT_ON").performScrollTo().performClick()

        assertEquals("make it bright", taughtPhrase)
        assertEquals("FLASHLIGHT_ON", taughtLabel)
    }

    @Test
    fun `taught phrases can be forgotten`() {
        var forgotten: String? = null
        composeTestRule.setContent {
            AssistantScreen(
                uiState = AssistantUiState.Idle,
                transcript = "",
                onConfirm = {},
                onDeny = {},
                onManualWake = {},
                taughtPhrases = listOf(
                    JarvisMemory.TaughtExample("make it bright", "FLASHLIGHT_ON", 0L)
                ),
                onForget = { phrase -> forgotten = phrase }
            )
        }

        composeTestRule.onNodeWithTag("ForgetAllButton").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("Forget_make it bright").performScrollTo().performClick()
        assertEquals("make it bright", forgotten)
    }

    @Test
    fun `import buttons are offered for folders archives and reply models`() {
        var folder = false
        var archive = false
        var reply = false
        composeTestRule.setContent {
            AssistantScreen(
                uiState = AssistantUiState.Idle,
                transcript = "",
                onConfirm = {},
                onDeny = {},
                onManualWake = {},
                onInstallModel = { folder = true },
                onInstallArchive = { archive = true },
                onInstallReplyModel = { reply = true }
            )
        }

        composeTestRule.onNodeWithTag("InstallModelButton").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("InstallArchiveButton").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("InstallReplyModelButton").performScrollTo().performClick()

        assertTrue(folder)
        assertTrue(archive)
        assertTrue(reply)
    }

    @Test
    fun `the routing source is shown when the brain matched a phrase`() {
        composeTestRule.setContent {
            AssistantScreen(
                uiState = AssistantUiState.NeedsConfirmation,
                transcript = "make it bright",
                onConfirm = {},
                onDeny = {},
                onManualWake = {},
                intentSource = NlpProcessor.Source.TAUGHT
            )
        }
        composeTestRule.onNodeWithTag("IntentSourceText")
            .performScrollTo()
            .assertTextEquals("matched a phrase you taught me")
    }
}
