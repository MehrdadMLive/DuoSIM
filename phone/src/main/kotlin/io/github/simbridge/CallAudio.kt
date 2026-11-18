package io.github.simbridge

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import kotlin.math.abs

/**
 * Moves call audio between this phone's mic/earpiece and the RTP stream from the SIM phone.
 * The mic is the clock: every 20 ms frame read from it is sent, and one frame from the jitter
 * buffer is played. Telecom already put the phone in communication mode, so routing
 * (earpiece / speaker / Bluetooth) follows Google's call screen.
 */
class CallAudio {
    @Volatile var muted = false
    /** Set while the call is being recorded; gets both sides of every frame. */
    @Volatile var recorder: CallRecorder? = null
    /** Set during the call; hears the caller and may replace my voice with translated speech. */
    @Volatile var translator: CallTranslator? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    private var startedAt = 0L
    private var port: MediaPort? = null

    @Synchronized fun start(port: MediaPort) {
        if (running) return
        startedAt = System.currentTimeMillis(); this.port = port
        running = true
        thread = Thread({ run(port) }, "call-audio").also { it.start() }
    }

    /** How the last call sounded: length, gaps (missing audio) and late audio dropped, plus Wi-Fi. */
    fun quality(ctx: android.content.Context): CallQuality? {
        val p = port as? io.github.sparkcyf.voicesipagent.sip.media.RtpSession ?: return null
        if (startedAt == 0L) return null
        return CallQuality(System.currentTimeMillis(), (System.currentTimeMillis() - startedAt) / 1000,
            p.jitterUnderruns, p.jitterDropped, p.packetsReceived, WifiLink.local(ctx))
    }

    @Synchronized fun stop() {
        running = false
        thread?.join(600)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun run(port: MediaPort) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = port.format.sampleRate
        val n = port.format.frameSamples
        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        val effects = ArrayList<android.media.audiofx.AudioEffect>()
        try {
            val inFmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minRec = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(inFmt).setBufferSizeInBytes(maxOf(minRec, n * 2 * 6)).build()
            val outFmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val minPlay = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(outFmt).setBufferSizeInBytes(maxOf(minPlay, n * 2 * 6))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; effects += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects += it }

            rec.startRecording()
            track.play()
            val buf = ShortArray(n)
            val zero = ShortArray(n)
            repeat(2) { track.write(zero, 0, n) }
            var upPeak = 0; var downPeak = 0; var frames = 0
            val silenced = runCatching { rec.activeRecordingConfiguration?.isClientSilenced }.getOrNull()
            AudioMeter.rate = rate
            Log.i("audio: started ${rate}Hz, aec=${effects.size}, micSilenced=$silenced")
            while (running) {
                var got = 0
                while (got < n && running) {
                    val r = rec.read(buf, got, n - got)
                    if (r <= 0) break
                    got += r
                }
                if (got < n) { Thread.sleep(5); continue }
                val tr = translator
                val up = if (muted) zero else tr?.uplink(buf) ?: buf
                port.writeFrame(up.copyOf())
                val down = port.readFrame()
                val play = tr?.downlink(down) ?: down
                track.write(play, 0, play.size)
                tr?.heard(down)
                recorder?.write(up, down)
                if (++frames % 250 == 0) {  // every 5 s
                    Log.i("audio: upPeak=$upPeak downPeak=$downPeak muted=$muted")
                    upPeak = 0; downPeak = 0
                } else {
                    for (s in up) upPeak = maxOf(upPeak, abs(s.toInt()))
                    for (s in down) downPeak = maxOf(downPeak, abs(s.toInt()))
                    if (downPeak > AudioMeter.downMax) AudioMeter.downMax = downPeak
                    if (upPeak > AudioMeter.upMax) AudioMeter.upMax = upPeak
                }
            }
        } catch (t: Throwable) {
            Log.w("audio: failed", t)
        } finally {
            effects.forEach { runCatching { it.release() } }
            runCatching { rec?.stop() }; rec?.release()
            runCatching { track?.stop() }; track?.release()
            Log.i("audio: stopped")
        }
    }
}

/** Loudest samples heard in the current call (for the audio self-test). */
object AudioMeter {
    @Volatile var downMax = 0
    @Volatile var upMax = 0
    @Volatile var rate = 0
    fun reset() { downMax = 0; upMax = 0 }
}

/** One call's audio health; [gaps] and [late] count 20 ms frames. */
data class CallQuality(val at: Long, val seconds: Long, val gaps: Long, val late: Long, val packets: Long, val wifi: WifiLink?) {
    /** Share of the call with missing audio. */
    val lossPct get() = if (seconds <= 0) 0.0 else gaps * 2.0 / seconds   // 20 ms frames: gaps*0.02 s / seconds * 100
    val verdict get() = when { lossPct < 1 && late < seconds / 4 -> 0; lossPct < 4 -> 1; else -> 2 }   // good, fair, poor

    fun json(): String = org.json.JSONObject().put("at", at).put("s", seconds).put("g", gaps).put("l", late).put("p", packets)
        .put("w", wifi?.let { "${it.band}|${it.rssi}|${it.tx}|${it.rx}" } ?: "").toString()

    companion object {
        fun parse(s: String?): CallQuality? = runCatching {
            val o = org.json.JSONObject(s!!)
            val w = o.optString("w").split('|').takeIf { it.size == 4 }
            CallQuality(o.getLong("at"), o.getLong("s"), o.getLong("g"), o.getLong("l"), o.getLong("p"),
                w?.let { WifiLink("", if (it[0] == "5 GHz") 5200 else if (it[0] == "6 GHz") 6000 else 2400, it[1].toInt(), it[2].toInt(), it[3].toInt()) })
        }.getOrNull()
    }
}
