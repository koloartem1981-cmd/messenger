package com.devin.messenger.ui.chatsettings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devin.messenger.data.Api
import com.devin.messenger.data.ChatDetails
import com.devin.messenger.data.UserPublic
import com.devin.messenger.ui.components.Avatar
import com.devin.messenger.ui.theme.BrandAccent
import com.devin.messenger.ui.theme.BrandPrimary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    api: Api,
    token: String?,
    chatId: Long,
    currentUserId: Long,
    onClosed: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var details by remember(chatId) { mutableStateOf<ChatDetails?>(null) }
    var allUsers by remember { mutableStateOf<List<UserPublic>>(emptyList()) }
    var renameOpen by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var addOpen by remember { mutableStateOf(false) }
    var addPicked by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleteOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val tk = token ?: return
        details = api.getChatDetails(tk, chatId)
        allUsers = api.listAllUsers(tk)
    }

    LaunchedEffect(token, chatId) {
        try {
            refresh()
        } catch (_: Exception) {
        }
    }

    val data = details
    val role = data?.myRole ?: "member"
    val canManage = role == "owner" || role == "admin"
    val isOwner = role == "owner"
    val isChannel = data?.chat?.isChannel == true

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isChannel) "Канал" else "Группа",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClosed) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { paddingValues ->
        if (data == null) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(BrandPrimary, BrandAccent))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isChannel) Icons.Rounded.Campaign else Icons.Rounded.Group,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = data.chat.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val subtitle = if (isChannel) {
                        "канал · ${data.members.size} подписчиков"
                    } else {
                        "группа · ${data.members.size} участников"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                    if (!data.chat.description.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = data.chat.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                        )
                    }
                }
                if (canManage) {
                    IconButton(onClick = {
                        newTitle = data.chat.title
                        newDescription = data.chat.description.orEmpty()
                        renameOpen = true
                    }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Переименовать")
                    }
                }
            }

            error?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (canManage) {
                Surface(
                    color = Color.Transparent,
                    onClick = {
                        addPicked = emptySet()
                        addOpen = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BrandPrimary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = Color.White)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Добавить участников",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Text(
                text = "Участники",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(items = data.members, key = { it.user.id }) { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            user = m.user,
                            fullUrl = api.avatarUrlFor(m.user),
                            size = 44.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = m.user.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val sub = buildString {
                                append("@${m.user.username}")
                                if (m.role != "member") append(" · ${m.role}")
                            }
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            )
                        }
                        val showRemove = (canManage && m.user.id != currentUserId && m.role != "owner") ||
                            (m.user.id == currentUserId && !isOwner)
                        if (showRemove) {
                            IconButton(onClick = onClick@{
                                val tk = token ?: return@onClick
                                if (working) return@onClick
                                working = true
                                scope.launch {
                                    try {
                                        api.removeChatMember(tk, chatId, m.user.id)
                                        refresh()
                                        if (m.user.id == currentUserId) onClosed()
                                    } catch (e: Exception) {
                                        error = e.message
                                    } finally {
                                        working = false
                                    }
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.PersonRemove,
                                    contentDescription = "Удалить",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            if (isOwner) {
                Button(
                    onClick = { deleteOpen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(text = if (isChannel) "Удалить канал" else "Удалить группу")
                }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(if (isChannel) "Изменить канал" else "Изменить группу") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newDescription,
                        onValueChange = { newDescription = it },
                        label = { Text("Описание") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onClick@{
                    val tk = token ?: return@onClick
                    if (newTitle.isBlank() || working) return@onClick
                    working = true
                    scope.launch {
                        try {
                            api.updateChat(
                                token = tk,
                                chatId = chatId,
                                title = newTitle.trim(),
                                description = newDescription.trim(),
                            )
                            refresh()
                            renameOpen = false
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            working = false
                        }
                    }
                }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Отмена") }
            },
        )
    }

    if (addOpen && data != null) {
        val existingIds = data.members.map { it.user.id }.toSet()
        val candidates = allUsers.filter { it.id !in existingIds }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("Добавить участников") },
            text = {
                if (candidates.isEmpty()) {
                    Text("Все доступные пользователи уже добавлены")
                } else {
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        items(items = candidates, key = { it.id }) { user ->
                            val checked = user.id in addPicked
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        addPicked = if (checked) addPicked - user.id else addPicked + user.id
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(user = user, fullUrl = api.avatarUrlFor(user), size = 36.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = user.displayName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "@${user.username}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                    )
                                }
                                if (checked) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = BrandPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = addPicked.isNotEmpty(),
                    onClick = onClick@{
                        val tk = token ?: return@onClick
                        if (working) return@onClick
                        working = true
                        scope.launch {
                            try {
                                addPicked.forEach { uid ->
                                    runCatching { api.addChatMember(tk, chatId, uid) }
                                }
                                refresh()
                                addOpen = false
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                working = false
                            }
                        }
                    },
                ) {
                    Text("Добавить (${addPicked.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { addOpen = false }) { Text("Отмена") }
            },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(if (isChannel) "Удалить канал?" else "Удалить группу?") },
            text = { Text("Все сообщения будут удалены безвозвратно") },
            confirmButton = {
                TextButton(onClick = onClick@{
                    val tk = token ?: return@onClick
                    if (working) return@onClick
                    working = true
                    scope.launch {
                        try {
                            api.deleteChat(tk, chatId)
                            deleteOpen = false
                            onDeleted()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            working = false
                        }
                    }
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("Отмена") }
            },
        )
    }
}
