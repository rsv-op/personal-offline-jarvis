package com.example.voiceassistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.voiceassistant.statemachine.AssistantUiState
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

        composeTestRule.onNodeWithTag("StatusText").assertTextEquals("Listening...")
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
                onManualWake = {}
            )
        }

        composeTestRule.onNodeWithTag("ConfirmButton").performClick()
        composeTestRule.onNodeWithTag("DenyButton").performClick()
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
}
