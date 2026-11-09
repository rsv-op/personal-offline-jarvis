package com.example.voiceassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.voiceassistant.wake.WakeState

/**
 * The hands-free status chip and the interrupt button.
 *
 * The chip answers one question at a glance — *is it listening, and will it act on what it
 * hears?* — which is the single most important thing to know about a microphone that is
 * open. The button next to it is the panic control: it flushes playback and halts
 * generation on the spot, exactly like saying "shaz", because a user who is being read a
 * long answer should not have to shout at their phone to stop it.
 */
@Composable
fun WakeChipRow(
    wakeState: WakeState,
    handsFreeActive: Boolean,
    wakeWordRequired: Boolean,
    windowMs: Long,
    onToggleHandsFree: () -> Unit,
    onToggleWakeWord: () -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(JarvisPanel)
                    .clickable(onClick = onToggleHandsFree)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("HandsFreeChip"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(wakeStateColor(wakeState))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = wakeLabel(wakeState, handsFreeActive, wakeWordRequired, windowMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Start,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (wakeWordRequired) "wake on" else "open mic",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier
                    .clickable(onClick = onToggleWakeWord)
                    .padding(vertical = 8.dp, horizontal = 2.dp)
                    .testTag("WakeWordToggle"),
            )
        }

        OutlinedButton(
            onClick = onInterrupt,
            modifier = Modifier.testTag("ShazInterruptButton"),
        ) {
            Text("SHAZ · stop", color = JarvisAmber)
        }
    }
}

private fun wakeStateColor(state: WakeState): androidx.compose.ui.graphics.Color = when (state) {
    WakeState.OFF -> JarvisPanel
    WakeState.STANDBY -> JarvisCyan.copy(alpha = 0.55f)
    WakeState.ARMED -> HereticMagenta
    WakeState.BUSY -> JarvisAmber
}

private fun wakeLabel(
    state: WakeState,
    handsFreeActive: Boolean,
    wakeWordRequired: Boolean,
    windowMs: Long,
): String = when {
    !handsFreeActive -> "Hands-free off · tap to listen"
    state == WakeState.ARMED && windowMs > 0 ->
        "Awake · ${(windowMs + 999) / 1000}s to speak"
    state == WakeState.BUSY -> "Working…"
    wakeWordRequired -> "Standby · say \"Heretic\""
    else -> "Open mic · listening"
}
