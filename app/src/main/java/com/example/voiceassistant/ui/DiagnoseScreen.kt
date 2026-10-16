package com.example.voiceassistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    var selectedTab by remember { mutableStateOf(0) }

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
                text = "Read-only view of what the app is hearing and what the device is reporting. " +
                    "The recogniser is not running here — press the orb to test the full STT loop.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            TabStrip(
                tabs = listOf("Diagnose", "Telematics"),
                selected = selectedTab,
                onSelect = { selectedTab = it }
            )

            when (selectedTab) {
                0 -> DiagnoseTab(
                    viewModel = viewModel,
                    onClose = onClose
                )
                else -> TelematicsTab(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun TabStrip(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selected
            val bg = if (isSelected) JarvisCyan else JarvisPanel
            val fg = if (isSelected) JarvisDeep else JarvisCyan
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("DiagnoseTab_$index")
            ) {
                Text(
                    text = label,
                    color = fg,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun DiagnoseTab(
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

    val ticker = rememberTicker(intervalMillis = 250L)
    val frames = stats.framesDelivered
    val zeros = stats.framesReadZero
    val errors = stats.readErrors
    val latency = stats.averageReadLatencyMicros

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

@Composable
private fun TelematicsTab(viewModel: AssistantViewModel) {
    val snap by viewModel.telematics.collectAsStateWithLifecycle()
    val cpuHistory by viewModel.cpuHistory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var lastCopyState by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val mem = snap.memory
        val availableMb = mem.availableBytes / (1024 * 1024)
        val totalMb = mem.totalBytes / (1024 * 1024)
        val thresholdMb = mem.thresholdBytes / (1024 * 1024)
        val inLowMemory = mem.isLowMemory || (mem.thresholdBytes > 0 && mem.availableBytes < mem.thresholdBytes)

        SectionCard(title = "Memory (live, 1 Hz)") {
            StatRow(
                "Available RAM",
                "$availableMb MB of $totalMb MB (${(mem.availableFraction * 100).toInt()}%)",
                ok = !inLowMemory
            )
            if (thresholdMb > 0) {
                StatRow(
                    "LMK threshold",
                    "$thresholdMb MB (apps will be killed below this)"
                )
            }
            StatRow("App PSS", "${mem.appPssKb / 1024} MB")
            StatRow("App private dirty", "${mem.appPrivateDirtyKb / 1024} MB")
            StatRow("Native heap", "${mem.nativeHeapBytes / (1024 * 1024)} MB")
            if (inLowMemory) {
                Text(
                    text = "Low memory. Android is likely to start killing background apps, " +
                        "and JARVIS may be terminated if it tries to load a model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        SectionCard(title = "CPU (live, 1 Hz)") {
            StatRow("Overall", "%.1f%%".format(snap.cpu.overallPercent))
            StatRow("This app", "%.1f%%".format(snap.cpu.appPercent))
            StatRow("Threads", snap.cpu.threadCount.toString())
            StatRow("Uptime / awake", "${snap.cpu.uptimeMs / 1000}s / ${snap.cpu.awakeMs / 1000}s")
            if (snap.cpu.perCoreFrequenciesKHz.isNotEmpty()) {
                Text(
                    text = "Per-core (kHz): " + snap.cpu.perCoreFrequenciesKHz.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            CpuSparkline(samples = cpuHistory)
        }

        val storage = snap.storage
        val freeGb = storage.internalFreeBytes / (1024L * 1024L * 1024L)
        val totalGb = storage.internalTotalBytes / (1024L * 1024L * 1024L)
        SectionCard(title = "Storage") {
            StatRow("Internal free", "$freeGb GB of $totalGb GB")
            StatRow("App files", "%.1f MB".format(storage.appFilesBytes / (1024.0 * 1024.0)))
        }

        val bat = snap.battery
        SectionCard(title = "Battery") {
            StatRow("Level", if (bat.levelPercent < 0) "?" else "${bat.levelPercent}%")
            StatRow("Charging", if (bat.isCharging) "yes (${bat.plugType})" else "no (${bat.plugType})")
            if (bat.temperatureC > 0f) StatRow("Temperature", "%.1f °C".format(bat.temperatureC))
            if (bat.voltageMv > 0) StatRow("Voltage", "${bat.voltageMv} mV")
            if (bat.currentNowMicroA != 0L) {
                val ma = bat.currentNowMicroA / 1000.0
                val sign = if (bat.currentNowMicroA > 0) "charging" else "discharging"
                StatRow("Current draw", "%.0f mA ($sign)".format(ma))
            }
            StatRow("Health", bat.health)
        }

        val therm = snap.thermal
        val throttled = therm.level >= 3 // SEVERE == 3
        SectionCard(title = "Thermal") {
            StatRow(
                "Status",
                therm.status,
                ok = !throttled
            )
            if (throttled) {
                Text(
                    text = "The device is throttling. Expect STT and reply quality to degrade; " +
                        "closing other apps may help.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        SectionCard(title = "This process") {
            StatRow("PID", snap.process.pid.toString())
            StatRow("Package", snap.process.packageName)
            StatRow("Version", "${snap.process.versionName} (${snap.process.versionCode})")
            StatRow("Target / min SDK", "${snap.process.targetSdk} / ${snap.process.minSdk}")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                onClick = {
                    val text = buildString {
                        appendLine("JARVIS telematics")
                        appendLine("----------------")
                        appendLine(
                            "Memory: ${mem.availableBytes / (1024 * 1024)} MB free of " +
                                "${mem.totalBytes / (1024 * 1024)} MB; threshold " +
                                "${mem.thresholdBytes / (1024 * 1024)} MB; " +
                                "PSS ${mem.appPssKb / 1024} MB; low=${mem.isLowMemory}"
                        )
                        appendLine(
                            "CPU: overall=%.1f%% this app=%.1f%% threads=%d uptimeMs=%d awakeMs=%d".format(
                                snap.cpu.overallPercent, snap.cpu.appPercent,
                                snap.cpu.threadCount, snap.cpu.uptimeMs, snap.cpu.awakeMs
                            )
                        )
                        if (snap.cpu.perCoreFrequenciesKHz.isNotEmpty()) {
                            appendLine("CPU freq (kHz): " + snap.cpu.perCoreFrequenciesKHz.joinToString(","))
                        }
                        appendLine("Storage: free=${storage.internalFreeBytes}B of ${storage.internalTotalBytes}B; app files=${storage.appFilesBytes}B")
                        appendLine("Battery: ${bat.levelPercent}% ${bat.plugType} ${bat.temperatureC}°C ${bat.voltageMv}mV current=${bat.currentNowMicroA}µA health=${bat.health}")
                        appendLine("Thermal: ${therm.status} (level ${therm.level})")
                        appendLine(
                            "Process: pid=${snap.process.pid} pkg=${snap.process.packageName} " +
                                "v${snap.process.versionName} (${snap.process.versionCode}) " +
                                "target=${snap.process.targetSdk} min=${snap.process.minSdk}"
                        )
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS telematics", text))
                    lastCopyState = "Copied (${text.length} chars). Paste into the chat."
                },
                modifier = Modifier.testTag("TelematicsCopyButton")
            ) {
                Text("Copy telematics")
            }
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

@Composable
private fun CpuSparkline(samples: FloatArray) {
    if (samples.isEmpty()) return
    val maxValue = 100f
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(top = 4.dp)
    ) {
        val w = size.width
        val h = size.height
        val n = samples.size
        if (n < 2) return@Canvas
        val step = w / (n - 1).toFloat()
        val path = androidx.compose.ui.graphics.Path()
        for (i in 0 until n) {
            val v = (samples[i] / maxValue).coerceIn(0f, 1f)
            val x = step * i
            val y = h - (v * h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
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
