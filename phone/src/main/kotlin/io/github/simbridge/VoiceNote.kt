package io.github.simbridge

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * A voice note recorded while the mic button is held: AMR (the audio format every phone's
 * picture messages accept), saved with the other message files and sent as an attachment.
 */
class VoiceNote private constructor(private val rec: MediaRecorder, private val file: File, private val startedAt: Long) {
    /** Stops; returns the attachment, or null when it was too short (a tap, not a hold). */
    fun stop(ctx: Context): Attachment? {
        val ms = System.currentTimeMillis() - startedAt
        runCatching { rec.stop() }
        rec.release()
        if (ms < 800 || !file.exists() || file.length() < 500) { file.delete(); return null }
        val sec = (ms / 1000).toInt()
        return Attachment("audio/amr", file.name, ctx.getString(R.string.voice_note, "%d:%02d".format(sec / 60, sec % 60)))
    }

    companion object {
        fun start(ctx: Context): VoiceNote? = runCatching {
            val f = File(ctx.filesDir, "mms").apply { mkdirs() }.resolve("voice-${System.currentTimeMillis()}.amr")
            val r = MediaRecorder(ctx).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setMaxDuration(120_000)
                setOutputFile(f.path)
                prepare(); start()
            }
            VoiceNote(r, f, System.currentTimeMillis())
        }.getOrNull()
    }
}
