package com.devin.messenger.ui.videocircle

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.devin.messenger.util.MediaUtils
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

@androidx.camera.core.ExperimentalGetImage
@Composable
fun VideoCircleRecorderScreen(
    onCancel: () -> Unit,
    onRecorded: (File, Int) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
    val hasAudioPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasCameraPermission || !hasAudioPermission) {
        PermissionFallback(onCancel = onCancel)
        return
    }

    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    val previewView = remember { PreviewView(context) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    // Bind CameraX provider once.
    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context).get()
        val preview = androidx.camera.core.Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .build()
        val capture = VideoCapture.withOutput(recorder)
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            videoCapture = capture
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { activeRecording?.stop() }
            executor.shutdown()
        }
    }

    // Tick elapsed time and auto-stop at 15s.
    LaunchedEffect(isRecording) {
        if (!isRecording) return@LaunchedEffect
        val started = System.currentTimeMillis()
        while (isRecording) {
            elapsedMs = System.currentTimeMillis() - started
            if (elapsedMs >= MAX_DURATION_MS) {
                activeRecording?.stop()
                break
            }
            delay(50)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = Color.White)
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    "Видеокружок",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { previewView },
                )
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC000000))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = MediaUtils.formatDuration(elapsedMs.toInt()) + " / 0:15",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            val ctxRef = context
            Box(
                modifier = Modifier
                    .padding(bottom = 48.dp)
                    .size(82.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color(0xFFB00020) else Color.White),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = {
                        if (isRecording) {
                            activeRecording?.stop()
                            return@IconButton
                        }
                        val capture = videoCapture ?: return@IconButton
                        val target = File(ctxRef.cacheDir, "circle-${UUID.randomUUID()}.mp4")
                        val outputOptions = FileOutputOptions.Builder(target).build()
                        val pending = capture.output
                            .prepareRecording(ctxRef, outputOptions)
                            .withAudioEnabled()
                        activeRecording = pending.start(executor) { event ->
                            when (event) {
                                is VideoRecordEvent.Start -> {
                                    isRecording = true
                                    elapsedMs = 0
                                }
                                is VideoRecordEvent.Finalize -> {
                                    val durationMs = elapsedMs.toInt().coerceAtLeast(0)
                                    isRecording = false
                                    activeRecording = null
                                    if (!event.hasError() && target.exists() && target.length() > 0) {
                                        onRecorded(target, durationMs)
                                    }
                                }
                                else -> {
                                    // ignore status updates
                                }
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                        contentDescription = if (isRecording) "Стоп" else "Запись",
                        tint = if (isRecording) Color.White else Color(0xFFB00020),
                        modifier = Modifier.size(if (isRecording) 32.dp else 56.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionFallback(onCancel: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Нужны разрешения на камеру и микрофон",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = Color.White)
            }
        }
    }
}

private const val MAX_DURATION_MS: Long = 15_000L

// Wrapper just so we can use Size without an explicit import on older AGP setups.
@Suppress("unused")
private fun previewSize(): Size = Size(720, 720)
