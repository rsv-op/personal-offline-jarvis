package com.example.voiceassistant.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voiceassistant.nlp.NlpProcessor
import com.example.voiceassistant.orchestrator.AssistantViewModel
import com.example.voiceassistant.statemachine.AssistantUiState
import com.example.voiceassistant.vault.JarvisMemory
import kotlinx.coroutines.launch

/** Actions a phrase can be taught for; the labels match the ai-matrix intent labels. */
enum class TeachAction(val label: String, val title: String) {
    FLASHLIGHT_ON("FLASHLIGHT_ON", "Flashlight on"),
    FLASHLIGHT_OFF("FLASHLIGHT_OFF", "Flashlight off"),
    ALARM_SET("ALARM_SET", "Set alarm"),
    TIMER_SET("TIMER_SET", "Set timer"),
    DEVICE_STATUS("DEVICE_STATUS", "Device status")
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AssistantApp(
    viewModel: AssistantViewModel,
    onInstallModel: () -> Unit,
    onInstallArchive: () -> Unit = {},
    onInstallReplyModel: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val transcript by viewModel.partialTranscript.collectAsStateWithLifecycle()
    val levelDb by viewModel.diagnosticLevelDb.collectAsStateWithLifecycle()
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
    val isDiagnosing by viewModel.isDiagnosing.collectAsStateWithLifecycle()

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
        if (isDiagnosing) {
            DiagnoseScreen(
                viewModel = viewModel,
                onClose = viewModel::closeDiagnostics
            )
        } else {
            AssistantScreen(
                uiState = uiState,
                transcript = transcript,
                onConfirm = viewModel::confirmAction,
                onDeny = viewModel::denyAction,
                onManualWake = viewModel::toggleListening,
                onLongPress = viewModel::openDiagnostics,
                levelDb = levelDb,
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
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun AssistantScreen(
    uiState: AssistantUiState,
    transcript: String,
    onConfirm: () -> Unit,
    onDeny: () -> Unit,
    onManualWake: () -> Unit,
    levelDb: Float = -60f,
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
    onForgetAll: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        // Pulled the scroll state out of the inline modifier so the corner
        // TEACH / UPDATE labels at the bottom of the home screen can
        // animate-scroll the column to the relevant section without
        // pulling in any extra state machinery.
        val scrollState = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()
        // Two scroll anchors: one for the StatusCard (UPDATE) and one for
        // the TeachPanel (TEACH). Bringing an anchor into view scrolls the
        // outer [Column] so the anchor is visible; the BringIntoViewRequester
        // is a stable object the [Modifier.bringIntoViewRequester] attaches
        // to, so the request works even if the children re-compose.
        val statusAnchor = remember { BringIntoViewRequester() }
        val teachAnchor = remember { BringIntoViewRequester() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(scrollState)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HereticOrb(
                uiState = uiState,
                onClick = onManualWake,
                onLongPress = onLongPress
            )

            // Heretic title with a soft cyan glow. We stack three Texts in
            // a Box so they overlap at the same position; the back layers
            // are scaled up and very low alpha, producing a halo without a
            // per-frame blur. No RenderEffect is used so this works on min
            // SDK 26. The layers are z-stacked in drawing order: outer halo
            // first, then mid halo, then the bright fill on top.
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "HERETIC",
                    style = MaterialTheme.typography.headlineMedium,
                    color = JarvisCyan.copy(alpha = 0.10f),
                    modifier = Modifier.graphicsLayer { scaleX = 1.08f; scaleY = 1.6f }
                )
                Text(
                    text = "HERETIC",
                    style = MaterialTheme.typography.headlineMedium,
                    color = JarvisCyan.copy(alpha = 0.20f),
                    modifier = Modifier.graphicsLayer { scaleX = 1.04f; scaleY = 1.3f }
                )
                Text(
                    text = "HERETIC",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "Private offline assistant • no internet permission",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(12.dp))
            // The waveform reads the same audio level the diagnose screen
            // shows, so the user sees a live signal when they are holding the
            // orb and a flat line when they are not. When capturing is not
            // active the level sits at -60 dB and the wave collapses to a
            // thin line, which is the right "idle" presentation.
            HereticWaveform(levelDb = levelDb, modifier = Modifier.fillMaxWidth())

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
            // UPDATE anchor: the corner button at the bottom of the home
            // screen calls statusAnchor.bringIntoView() to scroll this card
            // into view. The empty Box is a single-element [Modifier] host
            // for the requester; the StatusCard composable draws inside it.
            Box(modifier = Modifier.bringIntoViewRequester(statusAnchor)) {
                StatusCard(
                    modelStatus = modelStatus,
                    modelSizeMb = modelSizeMb,
                    brainStatus = brainStatus,
                    vaultStatus = vaultStatus,
                    replyModelStatus = replyModelStatus
                )
            }

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
            // TEACH anchor: same pattern as the UPDATE anchor above. The
            // corner TEACH label calls teachAnchor.bringIntoView() to scroll
            // the TeachPanel into view.
            Box(modifier = Modifier.bringIntoViewRequester(teachAnchor)) {
                TeachPanel(
                    lastUtterance = lastUtterance,
                    taughtPhrases = taughtPhrases,
                    onTeach = onTeach,
                    onTeachLast = onTeachLast,
                    onForget = onForget,
                    onForgetAll = onForgetAll
                )
            }

            // Bottom-of-screen corner labels. The design shows TEACH on the
            // left and UPDATE on the right; a [Row] with
            // [Arrangement.SpaceBetween] lays them out exactly like that.
            // Each label is a clickable Text: TEACH scrolls to the
            // TeachPanel, UPDATE scrolls to the StatusCard.
            Spacer(modifier = Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "TEACH",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .testTag("TeachCornerLabel")
                        .clickable {
                            coroutineScope.launch { teachAnchor.bringIntoView() }
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                )
                Text(
                    text = "UPDATE",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .testTag("UpdateCornerLabel")
                        .clickable {
                            coroutineScope.launch { statusAnchor.bringIntoView() }
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                )
            }
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
                text = "Teach Heretic",
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
