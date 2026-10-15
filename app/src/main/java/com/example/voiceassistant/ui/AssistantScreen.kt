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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voiceassistant.nlp.NlpProcessor
import com.example.voiceassistant.orchestrator.AssistantViewModel
import com.example.voiceassistant.statemachine.AssistantUiState
import com.example.voiceassistant.vault.JarvisMemory

private val JarvisCyan = Color(0xFF29E3F5)
private val JarvisDeep = Color(0xFF05070D)
private val JarvisPanel = Color(0xFF101827)

/** Actions a phrase can be taught for; the labels match the ai-matrix intent labels. */
enum class TeachAction(val label: String, val title: String) {
    FLASHLIGHT_ON("FLASHLIGHT_ON", "Flashlight on"),
    FLASHLIGHT_OFF("FLASHLIGHT_OFF", "Flashlight off"),
    ALARM_SET("ALARM_SET", "Set alarm"),
    TIMER_SET("TIMER_SET", "Set timer"),
    DEVICE_STATUS("DEVICE_STATUS", "Device status")
}

@Composable
fun AssistantApp(
    viewModel: AssistantViewModel,
    onInstallModel: () -> Unit,
    onInstallArchive: () -> Unit = {},
    onInstallReplyModel: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val transcript by viewModel.partialTranscript.collectAsStateWithLifecycle()
    val lastUtterance by viewModel.lastUtterance.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val modelSizeMb by viewModel.modelSizeMb.collectAsStateWithLifecycle()
    val replyModelStatus by viewModel.replyModelStatus.collectAsStateWithLifecycle()
    val brainStatus by viewModel.brainStatus.collectAsStateWithLifecycle()
    val vaultStatus by viewModel.vaultStatus.collectAsStateWithLifecycle()
    val taughtPhrases by viewModel.taughtPhrases.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val confirmationPrompt by viewModel.confirmationPrompt.collectAsStateWithLifecycle()
    val intentSource by viewModel.intentSource.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JarvisCyan,
            background = JarvisDeep,
            surface = JarvisPanel,
            onPrimary = JarvisDeep,
            onBackground = Color(0xFFE6F1FF),
            onSurface = Color(0xFFE6F1FF)
        )
    ) {
        AssistantScreen(
            uiState = uiState,
            transcript = transcript,
            onConfirm = viewModel::confirmAction,
            onDeny = viewModel::denyAction,
            onManualWake = viewModel::toggleListening,
            modelStatus = modelStatus,
            replyModelStatus = replyModelStatus,
            statusMessage = statusMessage,
            onInstallModel = onInstallModel,
            onInstallReplyModel = onInstallReplyModel,
            onInstallArchive = onInstallArchive,
            brainStatus = brainStatus,
            vaultStatus = vaultStatus,
            confirmationPrompt = confirmationPrompt,
            taughtPhrases = taughtPhrases,
            lastUtterance = lastUtterance,
            isImporting = isImporting,
            intentSource = intentSource,
            modelSizeMb = modelSizeMb,
            onTeach = viewModel::teach,
            onTeachLast = viewModel::teachLastUtterance,
            onForget = viewModel::forgetPhrase,
            onForgetAll = viewModel::forgetAllPhrases
        )
    }
}

@Composable
fun AssistantScreen(
    uiState: AssistantUiState,
    transcript: String,
    onConfirm: () -> Unit,
    onDeny: () -> Unit,
    onManualWake: () -> Unit,
    modelStatus: String = "",
    replyModelStatus: String = "",
    statusMessage: String = "",
    onInstallModel: () -> Unit = {},
    onInstallReplyModel: () -> Unit = {},
    onInstallArchive: () -> Unit = {},
    brainStatus: String = "",
    vaultStatus: String = "",
    confirmationPrompt: String = "",
    taughtPhrases: List<JarvisMemory.TaughtExample> = emptyList(),
    lastUtterance: String = "",
    isImporting: Boolean = false,
    intentSource: NlpProcessor.Source? = null,
    modelSizeMb: Double? = null,
    onTeach: (String, String) -> Unit = { _, _ -> },
    onTeachLast: (String) -> Unit = {},
    onForget: (String) -> Unit = {},
    onForgetAll: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "JARVIS",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Private offline assistant • no internet permission",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(20.dp))
            AssistantOrb(uiState = uiState, onClick = onManualWake)

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = mapStatusText(uiState),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("StatusText")
            )

            if (statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().testTag("AssistantMessage")
                )
            }

            if (intentSource != null && uiState !is AssistantUiState.Idle) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (intentSource) {
                        NlpProcessor.Source.RULES -> "matched a fixed command"
                        NlpProcessor.Source.BRAIN -> "matched the on-device intent model"
                        NlpProcessor.Source.TAUGHT -> "matched a phrase you taught me"
                        null -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.testTag("IntentSourceText")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            AnimatedVisibility(visible = transcript.isNotBlank()) {
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("TranscriptText")
                )
            }

            AnimatedVisibility(visible = uiState is AssistantUiState.NeedsConfirmation) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = JarvisPanel),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = confirmationPrompt.ifBlank { "Confirm this action?" },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().testTag("ConfirmationPrompt")
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Say \"yes\", tap Confirm, or tap Cancel. Nothing changes until you do.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
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

            Spacer(modifier = Modifier.height(20.dp))
            StatusCard(
                modelStatus = modelStatus,
                modelSizeMb = modelSizeMb,
                brainStatus = brainStatus,
                vaultStatus = vaultStatus,
                replyModelStatus = replyModelStatus
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(
                    onClick = onInstallModel,
                    enabled = !isImporting,
                    modifier = Modifier.testTag("InstallModelButton")
                ) {
                    Text(if (modelStatus.contains("ready", ignoreCase = true)) "Change model folder" else "Import model folder")
                }
                OutlinedButton(
                    onClick = onInstallArchive,
                    enabled = !isImporting,
                    modifier = Modifier.testTag("InstallArchiveButton")
                ) {
                    Text("Import model .zip")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onInstallReplyModel,
                enabled = !isImporting,
                modifier = Modifier.testTag("InstallReplyModelButton")
            ) {
                Text(
                    if (replyModelStatus.contains("ready", ignoreCase = true)) {
                        "Change reply model"
                    } else {
                        "Import local reply model"
                    }
                )
            }
            if (isImporting) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Importing…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            TeachPanel(
                lastUtterance = lastUtterance,
                taughtPhrases = taughtPhrases,
                onTeach = onTeach,
                onTeachLast = onTeachLast,
                onForget = onForget,
                onForgetAll = onForgetAll
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(
    modelStatus: String,
    modelSizeMb: Double?,
    brainStatus: String,
    vaultStatus: String,
    replyModelStatus: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = JarvisPanel),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = buildString {
                    append(modelStatus.ifBlank { "Speech model unknown" })
                    if (modelSizeMb != null) append(" (%.0f MB)".format(modelSizeMb))
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("ModelStatusText")
            )
            Text(
                text = brainStatus.ifBlank { "Intent brain unknown" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("BrainStatusText")
            )
            Text(
                text = vaultStatus.ifBlank { "Vault unknown" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("VaultStatusText")
            )
            Text(
                text = replyModelStatus.ifBlank { "Reply model unknown" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("ReplyModelStatusText")
            )
        }
    }
}

@Composable
private fun TeachPanel(
    lastUtterance: String,
    taughtPhrases: List<JarvisMemory.TaughtExample>,
    onTeach: (String, String) -> Unit,
    onTeachLast: (String) -> Unit,
    onForget: (String) -> Unit,
    onForgetAll: () -> Unit
) {
    var phrase by remember { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = JarvisPanel),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Teach JARVIS",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Type (or paste) a phrase, pick the action it should trigger. " +
                    "Taught phrases are stored encrypted on this device and are checked before the model.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = phrase,
                onValueChange = { phrase = it },
                label = { Text("Phrase, e.g. make it bright") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("TeachPhraseField")
            )

            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TeachAction.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { action ->
                            OutlinedButton(
                                onClick = {
                                    if (phrase.isNotBlank()) {
                                        onTeach(phrase, action.label)
                                        phrase = ""
                                    }
                                },
                                enabled = phrase.isNotBlank(),
                                modifier = Modifier.testTag("Teach_${action.label}")
                            ) {
                                Text(action.title, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (lastUtterance.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Last thing I heard: \"$lastUtterance\"",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().testTag("LastUtteranceText")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onTeachLast(TeachAction.FLASHLIGHT_ON.label) },
                        modifier = Modifier.testTag("TeachLastFlashlightOn")
                    ) { Text("→ light on", style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(
                        onClick = { onTeachLast(TeachAction.FLASHLIGHT_OFF.label) },
                        modifier = Modifier.testTag("TeachLastFlashlightOff")
                    ) { Text("→ light off", style = MaterialTheme.typography.labelSmall) }
                }
            }

            if (taughtPhrases.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Learned phrases (${taughtPhrases.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                taughtPhrases.take(8).forEach { example ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "\"${example.phrase}\" → ${example.label}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onForget(example.phrase) },
                            modifier = Modifier.testTag("Forget_${example.phrase}")
                        ) { Text("Forget") }
                    }
                }
                TextButton(onClick = onForgetAll, modifier = Modifier.testTag("ForgetAllButton")) {
                    Text("Forget everything")
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
        targetValue = if (isAnimating) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-scale"
    )

    val targetColor = when (uiState) {
        AssistantUiState.Idle, AssistantUiState.ReturnToIdle -> Color(0xFF1F2937)
        AssistantUiState.WakeDetected -> Color(0xFF0E7490)
        AssistantUiState.Listening -> JarvisCyan
        AssistantUiState.Processing -> Color(0xFFFBBF24)
        AssistantUiState.NeedsConfirmation -> Color(0xFFF97316)
        AssistantUiState.Executing -> Color(0xFF34D399)
        AssistantUiState.Speaking -> Color(0xFFA78BFA)
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(500),
        label = "orb-color"
    )

    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
            .testTag("AssistantOrb"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (uiState is AssistantUiState.Listening) "STOP" else "TALK",
            style = MaterialTheme.typography.labelLarge,
            color = JarvisDeep
        )
    }

    Spacer(modifier = Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "on-device",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

internal fun mapStatusText(state: AssistantUiState): String = when (state) {
    AssistantUiState.Idle -> "Tap the orb to speak"
    AssistantUiState.WakeDetected -> "Waking up…"
    AssistantUiState.Listening -> "Listening offline — tap to stop"
    AssistantUiState.Processing -> "Processing offline..."
    AssistantUiState.NeedsConfirmation -> "Confirm action?"
    AssistantUiState.Executing -> "Executing..."
    AssistantUiState.Speaking -> "Speaking..."
    AssistantUiState.ReturnToIdle -> "Finishing..."
}
