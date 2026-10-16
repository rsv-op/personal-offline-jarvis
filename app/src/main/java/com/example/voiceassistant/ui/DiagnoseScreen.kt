package com.example.voiceassistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.voiceassistant.orchestrator.AssistantViewModel
import com.example.voiceassistant.stt.AudioDiagnostics
import kotlin.math.roundToInt

/**
 * The in-app diagnose panel. Reached by long-pressing the orb while idle. The point is
 * to give the user (and me) a direct read on what the app is hearing, so a bad STT
 * transcript is not a guessing game: the screen tells you whether the mic is hot,
 * whether audio frames are being dropped, what sample rate Vosk is configured for,
 * and offers to save the last 10 s of audio for offline playback.
 *
 * The panel is read-only on the assistant: opening it does not start the recogniser,
 * and closing it does not change the assistant state.
 */
@Composable
fun DiagnoseScreen(
    viewModel: AssistantViewModel,
    onClose: () -> Unit
) {
    val partial by viewModel.diagnosticPartial.collectAsStateWithLifecycle()
    val levelDb by viewModel.diagnosticLevelDb.collectAsStateWithLifecycle()
    val stats = remember { viewModel.diagnosticStats }
    val modelPath = remember { viewModel.diagnosticModelPath }
    val modelSizeMb = remember { viewModel.diagnosticModelSizeMb }
    val totalMemoryMb = remember(viewModel) { viewModel.memoryBudget.totalMemoryBytes / (1024 * 1024) }
    val availableMemoryMb = remember(viewModel) { viewModel.memoryBudget.availableMemoryBytes / (1024 * 1024) }
    val context = LocalContext.current
    var lastSavePath by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var lastCopyState by remember { mutableStateOf<String?>(null) }

    // Re-read the stats every 250 ms while on this screen. Stats is a plain object, not
    // a flow, so we tick a clock to force recomposition.
    val ticker = rememberTicker(intervalMillis = 250L)
    val frames = stats.framesDelivered
    val zeros = stats.framesReadZero
    val errors = stats.readErrors
    val latency = stats.averageReadLatencyMicros

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Diagnosis",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Read-only view of what the app is hearing. The recogniser is not running here — " +
                    "press the orb to test the full STT loop, or use the buttons below to capture " +
                    "audio without engaging the assistant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            AudioLevelBar(levelDb = levelDb)
            MicStatusPill(levelDb = levelDb, framesDelivered = frames, ticker = ticker)

            SectionCard(title = "Live partial transcript") {
                Text(
                    text = partial.ifBlank { "(hold the orb to start capturing)" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("DiagnosePartialText")
                )
            }

            SectionCard(title = "Capture stats") {
                StatRow("Frames delivered", frames.toString())
                StatRow("Frames that returned 0 bytes", zeros.toString())
                StatRow("Read errors", errors.toString())
                StatRow("Average read latency", "$latency µs/frame")
                StatRow("Sample rate", "${com.example.voiceassistant.stt.AudioCaptureSource.SAMPLE_RATE_HZ} Hz")
            }

            SectionCard(title = "Model") {
                if (modelPath == null) {
                    Text(
                        text = "No Vosk model installed. The app cannot transcribe until one is imported.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(text = "Path: $modelPath", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Size: ${modelSizeMb?.let { "%.1f MB".format(it) } ?: "(reading)"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            SectionCard(title = "Memory") {
                StatRow("Total RAM", "$totalMemoryMb MB")
                StatRow("Available RAM", "$availableMemoryMb MB")
                if (modelSizeMb != null) {
                    val voskHeadroomMb = (modelSizeMb * 1.5).toInt()
                    val safe = availableMemoryMb >= voskHeadroomMb
                    StatRow(
                        "Vosk model headroom (1.5×)",
                        "$voskHeadroomMb MB needed",
                        ok = safe
                    )
                    if (!safe) {
                        Text(
                            text = "This device may not have enough free RAM to load the installed Vosk model. " +
                                "Close other apps and retry, or install a smaller model.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val path = viewModel.saveLastTenSeconds()
                        if (path == null) {
                            saveError = "Could not save (audio buffer is empty or Downloads is not writable)."
                            lastSavePath = null
                        } else {
                            saveError = null
                            lastSavePath = path
                        }
                    },
                    modifier = Modifier.testTag("DiagnoseSaveButton")
                ) {
                    Text("Save last 10 s")
                }
                TextButton(
                    onClick = {
                        val text = viewModel.diagnosticSnapshot()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS diagnosis", text))
                        lastCopyState = "Copied (${text.length} chars). Paste into the chat."
                    },
                    modifier = Modifier.testTag("DiagnoseCopyButton")
                ) {
                    Text("Copy diagnostics")
                }
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("DiagnoseCloseButton")
                ) {
                    Text("Close")
                }
            }

            if (lastSavePath != null) {
                Text(
                    text = "Saved to: $lastSavePath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (saveError != null) {
                Text(
                    text = saveError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (lastCopyState != null) {
                Text(
                    text = lastCopyState!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun AudioLevelBar(levelDb: Float) {
    // Map -60..0 dBFS to 0..1 for the bar width.
    val normalized = ((levelDb + 60f) / 60f).coerceIn(0f, 1f)
    val color = when {
        levelDb < -50f -> Color(0xFF6B7280) // gray - "no signal"
        levelDb < -20f -> Color(0xFF10B981) // green
        levelDb < -3f -> Color(0xFFFBBF24)  // amber
        else -> Color(0xFFEF4444)            // red - clipping
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Mic level", style = MaterialTheme.typography.labelLarge)
            Text("${levelDb.roundToInt()} dBFS", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1F2937))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = normalized)
                    .height(16.dp)
                    .background(color)
            )
        }
    }
}

@Composable
private fun MicStatusPill(levelDb: Float, framesDelivered: Long, ticker: Long) {
    val (label, color) = when {
        framesDelivered == 0L -> "NO MIC" to Color(0xFF6B7280)
        levelDb < -50f -> "QUIET" to Color(0xFFFBBF24)
        levelDb < -3f -> "OK" to Color(0xFF10B981)
        else -> "CLIPPING" to Color(0xFFEF4444)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
    // ticker is read to anchor the recomposition; the param keeps it in the recompose path.
    @Suppress("UNUSED_EXPRESSION") ticker
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = JarvisPanel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, ok: Boolean? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        val tint = when (ok) {
            true -> Color(0xFF10B981)
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onBackground
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, color = tint)
    }
}

/**
 * A lightweight recomposition ticker. The diagnose stats are a plain object, not a flow,
 * so we cannot reactively read them inside Compose; the ticker triggers a recompose
 * every [intervalMillis] which causes the reads of [stats] to pick up the new values.
 */
@Composable
private fun rememberTicker(intervalMillis: Long): Long {
    var tick by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(intervalMillis)
            tick = System.currentTimeMillis()
        }
    }
    return tick
}
