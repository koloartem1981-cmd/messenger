package com.devin.messenger.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.decode.VideoFrameDecoder
import com.devin.messenger.audio.VoiceRecorder
import com.devin.messenger.data.Api
import com.devin.messenger.data.ChatDetails
import com.devin.messenger.data.Message
import com.devin.messenger.data.MessageKind
import com.devin.messenger.data.RealtimeBus
import com.devin.messenger.data.UserPublic
import com.devin.messenger.ui.components.Avatar
import com.devin.messenger.ui.theme.BrandAccent
import com.devin.messenger.ui.theme.BrandPrimary
import com.devin.messenger.ui.theme.IncomingBubbleDark
import com.devin.messenger.ui.theme.OutgoingBubble
import com.devin.messenger.ui.videocircle.VideoCircleRecorderScreen
import com.devin.messenger.util.MediaUtils
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_VOICE_DURATION_MS = 700

/**
 * Unified chat screen that handles both 1-on-1 DMs and group/channel chats.
 *
 * Exactly one of [dmPeerId] or [chatId] must be greater than zero. The screen
 * routes messages and metadata loads to the matching backend endpoints.
 */
@androidx.camera.core.ExperimentalGetImage
@Composable
fun ChatScreen(
    api: Api,
    token: String?,
    currentUserId: Long,
    dmPeerId: Long = 0L,
    dmPeerDisplayName: String = "",
    dmPeerUsername: String = "",
    dmPeerAvatarUrl: String? = null,
    chatId: Long = 0L,
    onBack: () -> Unit,
    onOpenChatSettings: ((Long) -> Unit) = {},
) {
    val isGroupOrChannel = chatId > 0L
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var showCircleRecorder by remember { mutableStateOf(false) }
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var chatDetails by remember(chatId) { mutableStateOf<ChatDetails?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val peerStub = remember(dmPeerId, dmPeerUsername, dmPeerDisplayName) {
        UserPublic(
            id = dmPeerId,
            username = dmPeerUsername,
            displayName = dmPeerDisplayName,
            bio = null,
            avatarUrl = null,
        )
    }

    val recorder = remember(context) { VoiceRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartedAt by remember { mutableStateOf(0L) }
    var recordingElapsed by remember { mutableStateOf(0L) }

    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        while (isRecording) {
            recordingElapsed = System.currentTimeMillis() - recordingStartedAt
            delay(80)
        }
    }

    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    suspend fun appendIfNew(msg: Message) {
        if (messages.none { it.id == msg.id }) {
            messages = messages + msg
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun uploadCachedFile(
        file: File,
        kind: String,
        mime: String,
        durationMs: Int? = null,
        caption: String? = null,
        deleteAfter: Boolean = true,
    ) {
        val authToken = token ?: return
        scope.launch {
            try {
                val sent = if (isGroupOrChannel) {
                    api.sendChatMediaMessage(
                        token = authToken,
                        chatId = chatId,
                        kind = kind,
                        file = file,
                        mime = mime,
                        durationMs = durationMs,
                        caption = caption,
                    )
                } else {
                    api.sendMediaMessage(
                        token = authToken,
                        recipientId = dmPeerId,
                        kind = kind,
                        file = file,
                        mime = mime,
                        durationMs = durationMs,
                        caption = caption,
                    )
                }
                appendIfNew(sent)
            } catch (_: Exception) {
                // surfaces silently for now; toast could be added later
            } finally {
                if (deleteAfter) runCatching { file.delete() }
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val copied = MediaUtils.copyUriToCache(context, uri, "photo") ?: return@rememberLauncherForActivityResult
        uploadCachedFile(copied.file, MessageKind.PHOTO, copied.mime.ifBlank { "image/jpeg" })
    }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val copied = MediaUtils.copyUriToCache(context, uri, "video") ?: return@rememberLauncherForActivityResult
        val duration = MediaUtils.durationMsFor(copied.file)
        uploadCachedFile(copied.file, MessageKind.VIDEO, copied.mime.ifBlank { "video/mp4" }, duration)
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val copied = MediaUtils.copyUriToCache(context, uri, "file") ?: return@rememberLauncherForActivityResult
        uploadCachedFile(copied.file, MessageKind.FILE, copied.mime.ifBlank { "application/octet-stream" })
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        if (granted) pendingPermissionAction?.invoke()
        pendingPermissionAction = null
    }
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pendingPermissionAction?.invoke()
        pendingPermissionAction = null
    }

    fun ensureAudioPermission(then: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            then()
        } else {
            pendingPermissionAction = then
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun ensureCameraPermission(then: () -> Unit) {
        val cam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        val aud = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (cam == PackageManager.PERMISSION_GRANTED && aud == PackageManager.PERMISSION_GRANTED) {
            then()
        } else {
            pendingPermissionAction = then
            cameraPermLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )
        }
    }

    fun openVideoCircleRecorder() {
        // Drop IME focus so the camera UI opens onto a clean surface instead of
        // racing with a keyboard that the text field would otherwise keep open.
        dismissKeyboard()
        ensureCameraPermission { showCircleRecorder = true }
    }

    LaunchedEffect(token, dmPeerId, chatId) {
        if (token == null) return@LaunchedEffect
        try {
            messages = if (isGroupOrChannel) {
                api.listChatMessages(token, chatId)
            } else {
                api.listMessages(token, dmPeerId)
            }
            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.size - 1)
            }
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(token, chatId) {
        if (token == null || !isGroupOrChannel) return@LaunchedEffect
        try {
            chatDetails = api.getChatDetails(token, chatId)
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(token, dmPeerId, chatId) {
        if (token == null) return@LaunchedEffect
        RealtimeBus.incoming.collect { msg ->
            val matches = if (isGroupOrChannel) {
                msg.chatId == chatId
            } else {
                msg.chatId == null && (
                    (msg.senderId == dmPeerId && msg.recipientId == currentUserId) ||
                        (msg.recipientId == dmPeerId && msg.senderId == currentUserId)
                    )
            }
            if (matches && messages.none { it.id == msg.id }) {
                messages = messages + msg
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    val canPost = if (isGroupOrChannel) chatDetails?.canPost ?: true else true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            if (isGroupOrChannel) {
                GroupChatHeader(
                    details = chatDetails,
                    onBack = onBack,
                    onOpenSettings = { onOpenChatSettings(chatId) },
                )
            } else {
                DmChatHeader(
                    peer = peerStub,
                    avatarUrl = dmPeerAvatarUrl,
                    onBack = onBack,
                )
            }
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
                        val emptyText = when {
                            isGroupOrChannel && chatDetails?.chat?.isChannel == true ->
                                "Это канал «${chatDetails?.chat?.title}» — здесь пишет только создатель"
                            isGroupOrChannel ->
                                "Группа «${chatDetails?.chat?.title ?: ""}» пуста. Напиши первое сообщение!"
                            else ->
                                "Поздоровайся с @$dmPeerUsername"
                        }
                        Text(
                            text = emptyText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 80.dp, start = 24.dp, end = 24.dp),
                        )
                    }
                }
                items(items = messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        isOutgoing = msg.senderId == currentUserId,
                        api = api,
                        showSenderName = isGroupOrChannel && msg.senderId != currentUserId,
                        senderName = chatDetails?.members
                            ?.firstOrNull { it.user.id == msg.senderId }
                            ?.user?.displayName,
                    )
                }
            }

            if (isRecording) {
                RecordingIndicator(
                    elapsedMs = recordingElapsed,
                    onCancel = {
                        recorder.cancel()
                        isRecording = false
                        recordingElapsed = 0L
                    },
                    onStop = {
                        val authToken = token
                        val result = recorder.stop()
                        isRecording = false
                        if (authToken != null && result != null) {
                            val (file, durationMs) = result
                            if (durationMs >= MIN_VOICE_DURATION_MS && file.length() > 0) {
                                uploadCachedFile(file, MessageKind.VOICE, "audio/mp4", durationMs)
                            } else {
                                runCatching { file.delete() }
                            }
                        }
                        recordingElapsed = 0L
                    },
                )
            } else if (canPost) {
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
                                val sent = if (isGroupOrChannel) {
                                    api.sendChatMessage(token, chatId, text)
                                } else {
                                    api.sendMessage(token, dmPeerId, text)
                                }
                                appendIfNew(sent)
                                inputText = ""
                            } catch (_: Exception) {
                            } finally {
                                sending = false
                            }
                        }
                    },
                    onAttach = {
                        dismissKeyboard()
                        attachMenuOpen = true
                    },
                    onVoicePressAndHold = {
                        dismissKeyboard()
                        ensureAudioPermission {
                            runCatching {
                                recorder.start()
                                recordingStartedAt = System.currentTimeMillis()
                                recordingElapsed = 0L
                                isRecording = true
                            }
                        }
                    },
                )
            } else {
                ReadOnlyChannelBanner()
            }
        }
    }

    if (attachMenuOpen) {
        AttachMenuDialog(
            onDismiss = { attachMenuOpen = false },
            onPickPhoto = {
                attachMenuOpen = false
                photoPicker.launch("image/*")
            },
            onPickVideo = {
                attachMenuOpen = false
                videoPicker.launch("video/*")
            },
            onPickFile = {
                attachMenuOpen = false
                filePicker.launch("*/*")
            },
            onVideoCircle = {
                attachMenuOpen = false
                openVideoCircleRecorder()
            },
        )
    }

    if (showCircleRecorder) {
        VideoCircleRecorderScreen(
            onCancel = { showCircleRecorder = false },
            onRecorded = { file, durationMs ->
                showCircleRecorder = false
                uploadCachedFile(
                    file = file,
                    kind = MessageKind.VIDEO_CIRCLE,
                    mime = "video/mp4",
                    durationMs = durationMs,
                )
            },
        )
    }
}

@Composable
private fun DmChatHeader(
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
private fun GroupChatHeader(
    details: ChatDetails?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val isChannel = details?.chat?.isChannel == true
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(BrandPrimary, BrandAccent)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isChannel) Icons.Rounded.Campaign else Icons.Rounded.Group,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = details?.chat?.title ?: "Чат",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                val subtitle = when {
                    details == null -> "загрузка…"
                    isChannel -> "канал · ${details.chat.membersCount ?: details.members.size} подписчиков"
                    else -> "группа · ${details.chat.membersCount ?: details.members.size} участников"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "Настройки чата")
            }
        }
    }
}

@Composable
private fun ReadOnlyChannelBanner() {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Campaign,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "В этом канале пишет только создатель",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    isOutgoing: Boolean,
    api: Api,
    showSenderName: Boolean,
    senderName: String?,
) {
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
            Column(horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start) {
                if (showSenderName && !senderName.isNullOrBlank()) {
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandAccent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 2.dp),
                    )
                }
                when (message.kind) {
                    MessageKind.PHOTO -> PhotoMessage(message = message, api = api)
                    MessageKind.VIDEO -> VideoThumbMessage(message = message, api = api, bubbleColor = bubbleColor)
                    MessageKind.VIDEO_CIRCLE -> VideoCircleMessage(message = message, api = api)
                    MessageKind.VOICE -> Surface(color = bubbleColor, shape = shape) {
                        VoiceMessageContent(message = message, api = api, textColor = textColor)
                    }
                    MessageKind.FILE -> Surface(color = bubbleColor, shape = shape) {
                        FileMessageContent(message = message, api = api, textColor = textColor)
                    }
                    else -> Surface(
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
    }
}

@Composable
private fun PhotoMessage(message: Message, api: Api) {
    val context = LocalContext.current
    val url = api.mediaUrlFor(message) ?: return
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(url).build(),
            contentDescription = "Фото",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .height(260.dp)
                .background(IncomingBubbleDark),
        )
    }
}

@Composable
private fun VideoThumbMessage(message: Message, api: Api, bubbleColor: Color) {
    val context = LocalContext.current
    val url = api.mediaUrlFor(message) ?: return
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bubbleColor)
            .clickable {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.setDataAndType(Uri.parse(url), message.mediaMime ?: "video/mp4")
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(url)
                .decoderFactory(VideoFrameDecoder.Factory())
                .crossfade(true)
                .build(),
            contentDescription = "Видео",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .height(220.dp)
                .background(IncomingBubbleDark),
        )
        Icon(
            imageVector = Icons.Rounded.PlayCircle,
            contentDescription = "Воспроизвести",
            tint = Color.White,
            modifier = Modifier.size(56.dp),
        )
        if (message.mediaDurationMs != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = MediaUtils.formatDuration(message.mediaDurationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoCircleMessage(message: Message, api: Api) {
    val context = LocalContext.current
    val url = api.mediaUrlFor(message) ?: return
    var playing by remember { mutableStateOf(false) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 1f
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.playWhenReady = false
                    player.seekTo(0L)
                    playing = false
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .size(220.dp)
            .clip(CircleShape)
            .background(Color.Black)
            .clickable {
                playing = !playing
                if (playing) player.seekTo(0L)
                player.playWhenReady = playing
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    this.player = player
                }
            },
            modifier = Modifier.size(220.dp).clip(CircleShape),
        )
        if (!playing) {
            Icon(
                imageVector = Icons.Rounded.PlayCircle,
                contentDescription = "Воспроизвести",
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
        if (message.mediaDurationMs != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = MediaUtils.formatDuration(message.mediaDurationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun VoiceMessageContent(message: Message, api: Api, textColor: Color) {
    val url = api.mediaUrlFor(message) ?: return
    var playing by remember(url) { mutableStateOf(false) }
    var prepared by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        MediaPlayer().apply {
            setOnCompletionListener { playing = false; seekTo(0) }
            setOnPreparedListener { prepared = true }
            setOnErrorListener { _, _, _ -> playing = false; true }
        }
    }
    DisposableEffect(player) {
        runCatching {
            player.setDataSource(url)
            player.prepareAsync()
        }
        onDispose {
            runCatching { player.release() }
        }
    }
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).widthIn(min = 180.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = {
                    if (!prepared) return@IconButton
                    if (playing) {
                        runCatching { player.pause() }
                        playing = false
                    } else {
                        runCatching {
                            player.start()
                            playing = true
                        }
                    }
                },
            ) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Пауза" else "Воспроизвести",
                    tint = textColor,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = "Голосовое сообщение",
                color = textColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = MediaUtils.formatDuration(message.mediaDurationMs),
                color = textColor.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun FileMessageContent(message: Message, api: Api, textColor: Color) {
    val context = LocalContext.current
    val url = api.mediaUrlFor(message) ?: return
    Row(
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .widthIn(min = 200.dp, max = 280.dp)
            .clickable {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setDataAndType(Uri.parse(url), message.mediaMime ?: "*/*")
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0x33FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.InsertDriveFile,
                contentDescription = "Файл",
                tint = textColor,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.mediaFilename ?: "Файл",
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            val sub = listOfNotNull(
                MediaUtils.formatSize(message.mediaSize).takeIf { it.isNotBlank() },
                message.mediaMime,
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    color = textColor.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
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
    onAttach: () -> Unit,
    onVoicePressAndHold: () -> Unit,
) {
    val canSend by remember(value, sending) {
        derivedStateOf { value.trim().isNotEmpty() && !sending }
    }
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onAttach) {
                Icon(
                    imageVector = Icons.Rounded.AttachFile,
                    contentDescription = "Прикрепить",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
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
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(BrandPrimary)
                    .pointerInput(canSend) {
                        detectTapGestures(
                            onLongPress = {
                                if (!canSend) onVoicePressAndHold()
                            },
                            onTap = {
                                if (canSend) onSend() else onVoicePressAndHold()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (canSend) Icons.Rounded.Send else Icons.Rounded.Mic,
                    contentDescription = if (canSend) "Отправить" else "Голосовое",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun RecordingIndicator(
    elapsedMs: Long,
    onCancel: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = "Отменить")
            }
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFB00020)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Запись… ${MediaUtils.formatDuration(elapsedMs.toInt())}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(BrandPrimary)
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Send,
                    contentDescription = "Отправить",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun AttachMenuDialog(
    onDismiss: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onVideoCircle: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        title = { Text("Прикрепить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AttachRow(
                    icon = Icons.Rounded.InsertDriveFile,
                    label = "Фото из галереи",
                    onClick = onPickPhoto,
                )
                AttachRow(
                    icon = Icons.Rounded.PlayCircle,
                    label = "Видео из галереи",
                    onClick = onPickVideo,
                )
                AttachRow(
                    icon = Icons.Rounded.AttachFile,
                    label = "Файл",
                    onClick = onPickFile,
                )
                AttachRow(
                    icon = Icons.Rounded.RadioButtonChecked,
                    label = "Видеокружок",
                    onClick = onVideoCircle,
                )
            }
        },
    )
}

@Composable
private fun AttachRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
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
        Icon(imageVector = icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
