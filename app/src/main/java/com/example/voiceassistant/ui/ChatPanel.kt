package com.example.voiceassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.voiceassistant.chat.ChatMessageEntity

/**
 * The chat transcript surface.
 *
 * The transcript lives in Room and is shown as a normal conversation: the
 * user's messages on the right, the assistant's on the left. A reply that was
 * cut short by the app being killed is shown with the "interrupted" marker
 * that [ChatHistoryRepository.recoverInterruptedReplies] wrote into it.
 */
@Composable
fun ChatPanel(
    messages: List<ChatMessageEntity>,
    streamingReply: String,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Keep the newest message in view as the conversation grows.
    LaunchedEffect(messages.size, streamingReply) {
        val target = messages.size + if (streamingReply.isNotEmpty()) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisDeep)
            .testTag("chat_panel")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Conversation",
                style = MaterialTheme.typography.titleMedium,
                color = JarvisCyan
            )
            if (messages.isNotEmpty()) {
                TextButton(onClick = onClear, modifier = Modifier.testTag("chat_clear")) {
                    Text("Clear", color = JarvisCyan)
                }
            }
        }

        if (messages.isEmpty() && streamingReply.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Your conversation will appear here.",
                    color = JarvisCyan.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(message)
                }
                if (streamingReply.isNotEmpty()) {
                    item(key = "streaming") {
                        ChatBubble(
                            ChatMessageEntity(
                                id = -1L,
                                role = ChatMessageEntity.ROLE_ASSISTANT,
                                content = streamingReply,
                                createdAt = 0L,
                                pending = true
                            )
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input"),
                placeholder = { Text("Ask JARVIS something…") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp)
            )
            IconButtonPlaceholder(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        input = ""
                    }
                },
                enabled = input.isNotBlank()
            )
        }
    }
}

/**
 * The send button.
 *
 * A plain text button rather than a Material icon: the icons artefact would
 * pull in a dependency the app otherwise does not need, and one glyph is not
 * worth it.
 */
@Composable
private fun IconButtonPlaceholder(
    onClick: () -> Unit,
    enabled: Boolean
) {
    Surface(
        color = if (enabled) JarvisCyan.copy(alpha = 0.16f) else JarvisPanel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .padding(start = 8.dp)
            .size(48.dp)
            .testTag("chat_send")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "Send",
                    color = if (enabled) JarvisCyan else JarvisCyan.copy(alpha = 0.35f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/** One message bubble, aligned by role. */
@Composable
private fun ChatBubble(message: ChatMessageEntity) {
    val isUser = message.role == ChatMessageEntity.ROLE_USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) JarvisPanel else JarvisDeep,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .testTag(if (isUser) "chat_bubble_user" else "chat_bubble_assistant")
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.content,
                    color = if (isUser) androidx.compose.ui.graphics.Color.White else JarvisCyan,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (message.pending) {
                    Text(
                        text = "generating…",
                        color = JarvisCyan.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
