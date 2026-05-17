package com.devin.messenger.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devin.messenger.data.Api
import com.devin.messenger.data.Message
import com.devin.messenger.data.RealtimeBus
import com.devin.messenger.data.UserPublic
import com.devin.messenger.ui.components.Avatar
import com.devin.messenger.ui.theme.BrandPrimary
import com.devin.messenger.ui.theme.IncomingBubbleDark
import com.devin.messenger.ui.theme.OutgoingBubble
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    api: Api,
    token: String?,
    currentUserId: Long,
    peerId: Long,
    peerDisplayName: String,
    peerUsername: String,
    peerAvatarUrl: String?,
    onBack: () -> Unit,
) {
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val peerStub = remember(peerId) {
        UserPublic(
            id = peerId,
            username = peerUsername,
            displayName = peerDisplayName,
            bio = null,
            avatarUrl = null,
        )
    }

    LaunchedEffect(token, peerId) {
        if (token == null) return@LaunchedEffect
        try {
            messages = api.listMessages(token, peerId)
            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.size - 1)
            }
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(token, peerId) {
        if (token == null) return@LaunchedEffect
        RealtimeBus.incoming.collect { msg ->
            val involvesPeer = (msg.senderId == peerId && msg.recipientId == currentUserId) ||
                (msg.recipientId == peerId && msg.senderId == currentUserId)
            if (involvesPeer && messages.none { it.id == msg.id }) {
                messages = messages + msg
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            ChatHeader(
                peer = peerStub,
                avatarUrl = peerAvatarUrl,
                onBack = onBack,
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = "Поздоровайся с @$peerUsername",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                        )
                    }
                }
                items(items = messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        isOutgoing = msg.senderId == currentUserId,
                    )
                }
            }

            MessageInput(
                value = inputText,
                onValueChange = { inputText = it },
                sending = sending,
                onSend = {
                    val text = inputText.trim()
                    if (text.isEmpty() || token == null || sending) return@MessageInput
                    sending = true
                    scope.launch {
                        try {
                            val sent = api.sendMessage(token, peerId, text)
                            if (messages.none { it.id == sent.id }) {
                                messages = messages + sent
                                listState.animateScrollToItem(messages.size - 1)
                            }
                            inputText = ""
                        } catch (_: Exception) {
                        } finally {
                            sending = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatHeader(
    peer: UserPublic,
    avatarUrl: String?,
    onBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Avatar(user = peer, fullUrl = avatarUrl, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "@${peer.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, isOutgoing: Boolean) {
    val arrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    val bubbleColor = if (isOutgoing) OutgoingBubble else IncomingBubbleDark
    val textColor = Color.White
    val shape = if (isOutgoing) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
    }
    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220)),
        exit = fadeOut(tween(160)),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
            Surface(
                color = bubbleColor,
                shape = shape,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(shape),
            ) {
                Text(
                    text = message.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Сообщение…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(BrandPrimary),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onSend, enabled = !sending) {
                    Icon(
                        imageVector = Icons.Rounded.Send,
                        contentDescription = "Отправить",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
