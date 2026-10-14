package com.example.voiceassistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voiceassistant.orchestrator.AssistantViewModel
import com.example.voiceassistant.statemachine.AssistantUiState

@Composable
fun AssistantApp(viewModel: AssistantViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val transcript by viewModel.partialTranscript.collectAsStateWithLifecycle()

    MaterialTheme {
        AssistantScreen(
            uiState = uiState,
            transcript = transcript,
            onConfirm = viewModel::confirmAction,
            onDeny = viewModel::denyAction,
            onManualWake = { /* A wake trigger can be connected here. */ }
        )
    }
}

@Composable
fun AssistantScreen(
    uiState: AssistantUiState,
    transcript: String,
    onConfirm: () -> Unit,
    onDeny: () -> Unit,
    onManualWake: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = mapStatusText(uiState),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("StatusText")
            )

            Spacer(modifier = Modifier.height(48.dp))
            AssistantOrb(uiState = uiState, onClick = onManualWake)
            Spacer(modifier = Modifier.height(48.dp))

            AnimatedVisibility(visible = transcript.isNotBlank()) {
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("TranscriptText")
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
            AnimatedVisibility(visible = uiState is AssistantUiState.NeedsConfirmation) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = onDeny, modifier = Modifier.testTag("DenyButton")) {
                        Text("Cancel")
                    }
                    Button(onClick = onConfirm, modifier = Modifier.testTag("ConfirmButton")) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}

@Composable
fun AssistantOrb(uiState: AssistantUiState, onClick: () -> Unit) {
    val isAnimating = uiState is AssistantUiState.Listening || uiState is AssistantUiState.Processing
    val transition = rememberInfiniteTransition(label = "assistant-orb")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isAnimating) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-scale"
    )

    val targetColor = when (uiState) {
        AssistantUiState.Idle, AssistantUiState.ReturnToIdle -> Color.LightGray
        AssistantUiState.Listening -> Color.Blue
        AssistantUiState.Processing -> Color.Yellow
        AssistantUiState.NeedsConfirmation -> Color(0xFFFFA500)
        AssistantUiState.Executing -> Color.Green
        else -> Color.Cyan
    }
    val color by animateColorAsState(targetValue = targetColor, animationSpec = tween(500), label = "orb-color")

    Box(
        modifier = Modifier
            .size(100.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
            .testTag("AssistantOrb")
    )
}

private fun mapStatusText(state: AssistantUiState): String = when (state) {
    AssistantUiState.Idle -> "Tap or say wake word"
    AssistantUiState.WakeDetected -> "Wake word detected"
    AssistantUiState.Listening -> "Listening..."
    AssistantUiState.Processing -> "Thinking..."
    AssistantUiState.NeedsConfirmation -> "Confirm action?"
    AssistantUiState.Executing -> "Executing..."
    AssistantUiState.Speaking -> "Speaking..."
    AssistantUiState.ReturnToIdle -> "Finishing..."
}
