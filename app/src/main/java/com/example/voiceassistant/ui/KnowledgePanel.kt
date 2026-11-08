package com.example.voiceassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.voiceassistant.rag.RagSourceSummary

/**
 * The local knowledge-base screen.
 *
 * It is deliberately plain: what is indexed, how big it is, what was refused and why, plus
 * the three controls that matter — **add file**, **add folder**, **clear**. Everything here
 * happens through the Storage Access Framework, so the app never holds a broad storage
 * permission and never copies a document out of the user's own storage.
 *
 * The "target" field is the exact-match filter: type `notes.md page 2` and the next answer
 * is retrieved from that page only. It is pre-filled by [targetPrefill] when the user opened
 * this panel by asking about a specific file.
 */
@Composable
fun KnowledgePanel(
    sources: List<RagSourceSummary>,
    status: String,
    lastIngest: String,
    isBusy: Boolean,
    enabled: Boolean,
    targetFilter: String,
    onAddFile: () -> Unit,
    onAddFolder: () -> Unit,
    onClear: () -> Unit,
    onForget: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTargetFilterChanged: (String) -> Unit,
    onClose: () -> Unit,
) {
    var filter by remember(targetFilter) { mutableStateOf(targetFilter) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "LOCAL KNOWLEDGE",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("KnowledgeTitle"),
                )
                TextButton(onClick = onClose, modifier = Modifier.testTag("knowledge_close")) {
                    Text("Back", color = MaterialTheme.colorScheme.primary)
                }
            }

            Text(
                text = "Documents stay on this phone. They are chunked, indexed and quoted " +
                    "locally; the app still holds no INTERNET permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )

            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisPanel),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().testTag("KnowledgeStatus"),
                    )
                    if (lastIngest.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = lastIngest,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().testTag("KnowledgeLastIngest"),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Use documents when answering",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Switch(
                            checked = enabled,
                            onCheckedChange = onToggleEnabled,
                            modifier = Modifier.testTag("KnowledgeEnabledSwitch"),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(
                    onClick = onAddFile,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("AddKnowledgeFileButton"),
                ) { Text("Add file") }
                OutlinedButton(
                    onClick = onAddFolder,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("AddKnowledgeFolderButton"),
                ) { Text("Add folder") }
                OutlinedButton(
                    onClick = onClear,
                    enabled = !isBusy && sources.isNotEmpty(),
                    modifier = Modifier.testTag("ClearKnowledgeButton"),
                ) { Text("Clear") }
            }

            if (isBusy) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Indexing on device…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "TARGET FILTER (OPTIONAL)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            TextField(
                value = filter,
                onValueChange = {
                    filter = it
                    onTargetFilterChanged(it)
                },
                singleLine = true,
                placeholder = { Text("notes.md page 2") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = JarvisPanel,
                    unfocusedContainerColor = JarvisPanel,
                ),
                modifier = Modifier.fillMaxWidth().testTag("TargetFilterField"),
            )
            Text(
                text = "Exact-match filters: a file name, a page number, or both.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "INDEXED SOURCES",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (sources.isEmpty()) {
                Text(
                    text = "Nothing yet. Add a file, a .zip, or a folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.height(220.dp).fillMaxWidth()) {
                    items(sources, key = { it.sourcePath }) { source ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = source.sourcePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("SourceRow"),
                                )
                                Text(
                                    text = "${source.pages} page(s) · ${source.chunks} excerpt(s) · " +
                                        "${source.characters} chars",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                )
                            }
                            TextButton(onClick = { onForget(source.sourcePath) }) {
                                Text("Remove", color = JarvisAmber)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Ask: \"Heretic, from notes.md page 2, what did I decide about the " +
                    "battery budget?\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().testTag("KnowledgeHint"),
            )
        }
    }
}
