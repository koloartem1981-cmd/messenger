package com.devin.messenger.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devin.messenger.data.Api
import com.devin.messenger.data.Chat
import com.devin.messenger.data.ChatEntry
import com.devin.messenger.data.Message
import com.devin.messenger.data.MessageKind
import com.devin.messenger.data.RealtimeBus
import com.devin.messenger.data.UserPublic
import com.devin.messenger.ui.components.Avatar
import com.devin.messenger.ui.theme.BrandAccent
import com.devin.messenger.ui.theme.BrandPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    api: Api,
    token: String?,
    currentUser: UserPublic?,
    onOpenDm: (UserPublic) -> Unit,
    onOpenGroup: (Chat) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenProfile: () -> Unit,
    onCreateChat: (type: String) -> Unit,
) {
    var entries by remember { mutableStateOf<List<ChatEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var newChatMenuOpen by remember { mutableStateOf(false) }

    suspend fun reload() {
        if (token == null) return
        loading = true
        try {
            entries = api.listChats(token)
        } catch (_: Exception) {
        } finally {
            loading = false
        }
    }

    LaunchedEffect(token) {
        reload()
    }

    val incoming = RealtimeBus.incoming.collectAsState(initial = null)
    LaunchedEffect(incoming.value) {
        if (incoming.value != null) {
            reload()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Чаты",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        currentUser?.let {
                            Text(
                                text = "@${it.username}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = "Поиск")
                    }
                    IconButton(onClick = onOpenProfile) {
                        if (currentUser != null) {
                            Avatar(
                                user = currentUser,
                                fullUrl = api.avatarUrlFor(currentUser),
                                size = 36.dp,
                            )
                        } else {
                            Icon(Icons.Rounded.Person, contentDescription = "Профиль")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { newChatMenuOpen = true },
                shape = CircleShape,
                containerColor = BrandPrimary,
                contentColor = Color.White,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Новый чат")
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (entries.isEmpty() && !loading) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().animateContentSize(tween(300)),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(items = entries, key = { entryKey(it) }) { entry ->
                        ChatEntryRow(
                            entry = entry,
                            api = api,
                            onClick = {
                                when {
                                    entry.peer != null -> onOpenDm(entry.peer)
                                    entry.chat != null -> onOpenGroup(entry.chat)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (newChatMenuOpen) {
        AlertDialog(
            onDismissRequest = { newChatMenuOpen = false },
            confirmButton = {
                TextButton(onClick = { newChatMenuOpen = false }) { Text("Отмена") }
            },
            title = { Text("Создать") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NewChatRow(
                        icon = Icons.Rounded.PersonAdd,
                        title = "Поиск людей",
                        subtitle = "Открыть личный чат",
                        onClick = {
                            newChatMenuOpen = false
                            onOpenSearch()
                        },
                    )
                    NewChatRow(
                        icon = Icons.Rounded.Group,
                        title = "Группа",
                        subtitle = "Несколько участников, все могут писать",
                        onClick = {
                            newChatMenuOpen = false
                            onCreateChat("group")
                        },
                    )
                    NewChatRow(
                        icon = Icons.Rounded.Campaign,
                        title = "Канал",
                        subtitle = "Пишет только создатель / админ",
                        onClick = {
                            newChatMenuOpen = false
                            onCreateChat("channel")
                        },
                    )
                }
            },
        )
    }
}

private fun entryKey(entry: ChatEntry): String = when {
    entry.peer != null -> "dm-${entry.peer.id}"
    entry.chat != null -> "chat-${entry.chat.id}"
    else -> "unknown"
}

@Composable
private fun ChatEntryRow(
    entry: ChatEntry,
    api: Api,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.peer != null) {
                Avatar(
                    user = entry.peer,
                    fullUrl = api.avatarUrlFor(entry.peer),
                    size = 52.dp,
                )
            } else if (entry.chat != null) {
                GroupAvatar(chat = entry.chat, api = api)
            }
            Spacer(Modifier.size(14.dp, 0.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.chat != null) {
                        Icon(
                            imageVector = if (entry.chat.isChannel) Icons.Rounded.Campaign else Icons.Rounded.Group,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(6.dp, 0.dp))
                    }
                    Text(
                        text = entry.title.ifBlank { "Чат" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = previewLine(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun GroupAvatar(chat: Chat, api: Api) {
    val url = api.chatAvatarUrlFor(chat)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(BrandPrimary, BrandAccent))),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = chat.title,
                modifier = Modifier.size(52.dp).clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = if (chat.isChannel) Icons.Rounded.Campaign else Icons.Rounded.Group,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

private fun previewLine(entry: ChatEntry): String {
    val last = entry.lastMessage
    if (last != null) return previewForMessage(last)
    return when {
        entry.peer != null -> "@${entry.peer.username} — нет сообщений"
        entry.chat?.isChannel == true -> "${entry.chat.membersCount ?: 0} подписчиков"
        entry.chat != null -> "${entry.chat.membersCount ?: 0} участников"
        else -> ""
    }
}

private fun previewForMessage(m: Message): String = when (m.kind) {
    MessageKind.TEXT -> m.content.ifBlank { "(сообщение)" }
    MessageKind.VOICE -> "Голосовое сообщение"
    MessageKind.PHOTO -> "Фото"
    MessageKind.VIDEO -> "Видео"
    MessageKind.VIDEO_CIRCLE -> "Видеокружок"
    MessageKind.FILE -> m.mediaFilename?.let { "Файл: $it" } ?: "Файл"
    else -> m.content.ifBlank { "(сообщение)" }
}

@Composable
private fun NewChatRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(BrandPrimary, BrandAccent))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
        Spacer(Modifier.size(12.dp, 0.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(BrandPrimary, BrandAccent))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Тут пока пусто",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Создай группу или канал, либо открой личный чат через поиск",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}
