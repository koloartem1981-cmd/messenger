package com.devin.messenger.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

/**
 * Tiny wrapper around MediaRecorder that captures AAC audio inside an MP4 container
 * (audio/mp4) suitable for the messenger backend's media endpoint.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt: Long = 0L

    fun start(): File {
        stopQuietly()
        val file = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        output = file
        startedAt = System.currentTimeMillis()
        return file
    }

    /** Stops recording and returns the captured file with its duration in milliseconds. */
    fun stop(): Pair<File, Int>? {
        val r = recorder ?: return null
        val f = output ?: return null
        val durationMs = (System.currentTimeMillis() - startedAt).toInt().coerceAtLeast(0)
        try {
            r.stop()
        } catch (_: Exception) {
            // RuntimeException is thrown if stop() is called too quickly (no audio captured)
        }
        r.release()
        recorder = null
        output = null
        startedAt = 0L
        return f to durationMs
    }

    fun cancel() {
        stopQuietly()
        output?.delete()
        output = null
        startedAt = 0L
    }

    private fun stopQuietly() {
        val r = recorder ?: return
        runCatching { r.stop() }
        runCatching { r.release() }
        recorder = null
    }
}
