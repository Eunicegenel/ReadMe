package com.eunicegenel.readme.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

class KokoroNarrationEngine(
    context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initializeLock = Any()

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var isStopped = false

    private val assetModelDir = "models/kokoro-en-v0_19"

    fun warmUp() {
        scope.launch {
            try {
                initialize()
                Log.d(TAG, "Kokoro warm-up complete.")
            } catch (error: Throwable) {
                Log.e(TAG, "Kokoro warm-up failed.", error)
            }
        }
    }

    fun initialize() {
        synchronized(initializeLock) {
            if (tts != null) return

            Log.d(TAG, "Preparing Kokoro filesystem data...")

            val copiedEspeakDataDir = copyEspeakDataDirToFiles()

            Log.d(TAG, "Initializing Kokoro engine...")
            Log.d(TAG, "Using espeak-ng-data at: ${copiedEspeakDataDir.absolutePath}")

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$assetModelDir/model.onnx",
                        voices = "$assetModelDir/voices.bin",
                        tokens = "$assetModelDir/tokens.txt",
                        dataDir = copiedEspeakDataDir.absolutePath
                    ),
                    numThreads = 4,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1,
                silenceScale = 0.2f
            )

            tts = OfflineTts(
                assetManager = appContext.assets,
                config = config
            )

            Log.d(TAG, "Kokoro engine initialized.")
        }
    }

    fun speak(
        text: String,
        speakerId: Int = 5,
        speed: Float = 1.0f,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val cleanedText = cleanTextForKokoro(text)

        if (cleanedText.isBlank()) {
            onDone()
            return
        }

        isStopped = false

        scope.launch {
            try {
                initialize()

                val engine = tts
                    ?: throw IllegalStateException("Kokoro TTS engine failed to initialize.")

                val safeSpeakerId = speakerId.coerceIn(0, 10)
                val safeSpeed = speed.coerceIn(0.75f, 1.35f)

                Log.d(
                    TAG,
                    "Generating Kokoro audio. speakerId=$safeSpeakerId speed=$safeSpeed textLength=${cleanedText.length}"
                )

                val generationConfig = GenerationConfig(
                    sid = safeSpeakerId,
                    speed = safeSpeed,
                    silenceScale = 0.2f
                )

                val audio = engine.generateWithConfig(
                    text = cleanedText,
                    config = generationConfig
                )

                Log.d(
                    TAG,
                    "Generated audio. samples=${audio.samples.size} sampleRate=${audio.sampleRate}"
                )

                if (!isStopped && audio.samples.isNotEmpty()) {
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
                Log.e(TAG, "Kokoro speak failed.", error)

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
        }

        try {
            audioTrack?.release()
        } catch (_: Throwable) {
        }

        audioTrack = null
    }

    fun shutdown() {
        stop()
        scope.cancel()

        try {
            tts?.release()
        } catch (_: Throwable) {
        }

        tts = null
    }

    private fun copyEspeakDataDirToFiles(): File {
        val sourceAssetDir = "$assetModelDir/espeak-ng-data"
        val targetDir = File(
            appContext.filesDir,
            "sherpa/models/kokoro-en-v0_19/espeak-ng-data"
        )

        val markerFile = File(targetDir, ".copy_complete")

        if (targetDir.exists() && markerFile.exists()) {
            return targetDir
        }

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }

        targetDir.mkdirs()

        copyAssetDirectory(
            assetDir = sourceAssetDir,
            targetDir = targetDir
        )

        markerFile.writeText("ok")

        return targetDir
    }

    private fun copyAssetDirectory(
        assetDir: String,
        targetDir: File
    ) {
        val children = appContext.assets.list(assetDir)?.toList().orEmpty()

        if (children.isEmpty()) {
            copyAssetFile(
                assetPath = assetDir,
                targetFile = targetDir
            )
            return
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        for (child in children) {
            val childAssetPath = "$assetDir/$child"
            val childTargetFile = File(targetDir, child)
            val grandChildren = appContext.assets.list(childAssetPath)?.toList().orEmpty()

            if (grandChildren.isEmpty()) {
                copyAssetFile(
                    assetPath = childAssetPath,
                    targetFile = childTargetFile
                )
            } else {
                copyAssetDirectory(
                    assetDir = childAssetPath,
                    targetDir = childTargetFile
                )
            }
        }
    }

    private fun copyAssetFile(
        assetPath: String,
        targetFile: File
    ) {
        targetFile.parentFile?.mkdirs()

        appContext.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun playFloatAudio(
        samples: FloatArray,
        sampleRate: Int
    ) {
        if (samples.isEmpty() || sampleRate <= 0) return

        val pcmBytes = floatSamplesToPcm16Bytes(samples)

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = max(minBufferSize, 8192)

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
            val bytesToWrite = min(8192, pcmBytes.size - offset)
            val written = track.write(pcmBytes, offset, bytesToWrite)

            if (written <= 0) break

            offset += written
        }

        try {
            track.stop()
        } catch (_: Throwable) {
        }

        try {
            track.release()
        } catch (_: Throwable) {
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

    private fun cleanTextForKokoro(text: String): String {
        return text
            .replace("\u0000", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TEXT_LENGTH_PER_GENERATION)
    }

    companion object {
        private const val TAG = "ReadMeKokoro"
        private const val MAX_TEXT_LENGTH_PER_GENERATION = 500
    }
}
