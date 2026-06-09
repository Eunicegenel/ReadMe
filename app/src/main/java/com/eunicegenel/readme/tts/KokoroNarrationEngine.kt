package com.eunicegenel.readme.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class KokoroNarrationEngine(
    context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var isStopped = false

    private val modelDir = "models/kokoro-en-v0_19"

    fun initialize() {
        if (tts != null) return

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$modelDir/model.onnx",
                    voices = "$modelDir/voices.bin",
                    tokens = "$modelDir/tokens.txt",
                    dataDir = "$modelDir/espeak-ng-data"
                ),
                numThreads = 4,
                debug = true,
                provider = "cpu"
            ),
            maxNumSentences = 1,
            silenceScale = 0.15f
        )

        tts = OfflineTts(
            assetManager = appContext.assets,
            config = config
        )
    }

    fun speak(
        text: String,
        speakerId: Int = 3,
        speed: Float = 1.0f,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (text.isBlank()) {
            onDone()
            return
        }

        isStopped = false

        scope.launch {
            try {
                initialize()

                val engine = tts
                    ?: throw IllegalStateException("Kokoro TTS engine failed to initialize.")

                val generationConfig = GenerationConfig(
                    sid = speakerId,
                    speed = speed,
                    silenceScale = 0.15f
                )

                val audio = engine.generateWithConfig(
                    text = text,
                    config = generationConfig
                )

                if (!isStopped) {
                    playFloatAudio(
                        samples = audio.samples,
                        sampleRate = audio.sampleRate
                    )
                }

                withContext(Dispatchers.Main) {
                    if (!isStopped) {
                        onDone()
                    }
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    onError(error)
                }
            }
        }
    }

    fun stop() {
        isStopped = true

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (_: Throwable) {
            // AudioTrack can throw if stopped before fully initialized.
        }

        try {
            audioTrack?.release()
        } catch (_: Throwable) {
            // Already released or invalid state.
        }

        audioTrack = null
    }

    fun shutdown() {
        stop()
        scope.cancel()

        try {
            tts?.release()
        } catch (_: Throwable) {
            // Native engine may already be released.
        }

        tts = null
    }

    private fun playFloatAudio(
        samples: FloatArray,
        sampleRate: Int
    ) {
        val pcmBytes = floatSamplesToPcm16Bytes(samples)

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = max(minBufferSize, 4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track

        track.play()

        var offset = 0

        while (offset < pcmBytes.size && !isStopped) {
            val bytesToWrite = min(4096, pcmBytes.size - offset)
            val written = track.write(pcmBytes, offset, bytesToWrite)

            if (written <= 0) {
                break
            }

            offset += written
        }

        try {
            track.stop()
        } catch (_: Throwable) {
            // Ignore invalid stop states.
        }

        try {
            track.release()
        } catch (_: Throwable) {
            // Ignore release issues.
        }

        if (audioTrack == track) {
            audioTrack = null
        }
    }

    private fun floatSamplesToPcm16Bytes(samples: FloatArray): ByteArray {
        val output = ByteArray(samples.size * 2)

        for (index in samples.indices) {
            val clipped = samples[index].coerceIn(-1.0f, 1.0f)
            val pcm = (clipped * Short.MAX_VALUE).toInt().toShort()

            output[index * 2] = (pcm.toInt() and 0xFF).toByte()
            output[index * 2 + 1] = ((pcm.toInt() shr 8) and 0xFF).toByte()
        }

        return output
    }
}
