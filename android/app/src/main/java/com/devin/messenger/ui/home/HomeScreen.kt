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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.devin.messenger.data.ChatPreview
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
    onOpenChat: (UserPublic) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    var chats by remember { mutableStateOf<List<ChatPreview>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    suspend fun reload() {
        if (token == null) return
        loading = true
        try {
            chats = api.listChats(token)
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
                onClick = onOpenSearch,
                shape = CircleShape,
                containerColor = BrandPrimary,
                contentColor = Color.White,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Новый чат")
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (chats.isEmpty() && !loading) {
                EmptyState(onOpenSearch = onOpenSearch)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().animateContentSize(tween(300)),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(items = chats, key = { it.peer.id }) { chat ->
                        ChatListItem(
                            chat = chat,
                            api = api,
                            onClick = { onOpenChat(chat.peer) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatListItem(
    chat: ChatPreview,
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
            Avatar(
                user = chat.peer,
                fullUrl = api.avatarUrlFor(chat.peer),
                size = 52.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.peer.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = chat.lastMessage?.content ?: "@${chat.peer.username} — нет сообщений",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(@Suppress("UNUSED_PARAMETER") onOpenSearch: () -> Unit) {
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
            text = "Найди друзей по юзернейму и начни общение",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}
