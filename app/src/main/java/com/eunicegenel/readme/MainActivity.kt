package com.eunicegenel.readme

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eunicegenel.readme.ui.theme.ReadMeTheme
import java.nio.charset.Charset
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var textToSpeech: TextToSpeech? = null
    private var onUtteranceDone: (() -> Unit)? = null

    private val paragraphUtteranceId = "readme_paragraph"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ReadMeTheme {
                ReaderScreen(
                    onInitializeTts = { onReady ->
                        initializeTts(onReady)
                    },
                    onSpeak = { text, onDone ->
                        speakText(text, onDone)
                    },
                    onStop = {
                        stopSpeaking()
                    }
                )
            }
        }
    }

    private fun initializeTts(onReady: (Boolean) -> Unit) {
        if (textToSpeech != null) {
            onReady(true)
            return
        }

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.US)

                val isSupported = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

                textToSpeech?.setSpeechRate(0.95f)
                textToSpeech?.setPitch(1.0f)

                textToSpeech?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == paragraphUtteranceId) {
                                runOnUiThread {
                                    onUtteranceDone?.invoke()
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == paragraphUtteranceId) {
                                runOnUiThread {
                                    onUtteranceDone = null
                                }
                            }
                        }
                    }
                )

                runOnUiThread {
                    onReady(isSupported)
                }
            } else {
                runOnUiThread {
                    onReady(false)
                }
            }
        }
    }

    private fun speakText(text: String, onDone: () -> Unit) {
        if (text.isBlank()) return

        onUtteranceDone = onDone

        textToSpeech?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            paragraphUtteranceId
        )
    }

    private fun stopSpeaking() {
        onUtteranceDone = null
        textToSpeech?.stop()
    }

    override fun onDestroy() {
        stopSpeaking()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }
}

@Composable
fun ReaderScreen(
    onInitializeTts: ((Boolean) -> Unit) -> Unit,
    onSpeak: (String, () -> Unit) -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current

    var isTtsReady by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    var fileName by remember { mutableStateOf("No file selected") }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentParagraphIndex by remember { mutableIntStateOf(0) }

    val currentParagraph = paragraphs.getOrNull(currentParagraphIndex).orEmpty()

    fun playFromIndex(index: Int) {
        val paragraph = paragraphs.getOrNull(index)

        if (paragraph.isNullOrBlank()) {
            isPlaying = false
            return
        }

        currentParagraphIndex = index
        isPlaying = true

        onSpeak(paragraph) {
            val nextIndex = index + 1

            if (isPlaying && nextIndex in paragraphs.indices) {
                playFromIndex(nextIndex)
            } else {
                isPlaying = false
            }
        }
    }

    fun stopPlayback() {
        isPlaying = false
        onStop()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        try {
            stopPlayback()

            val importedText = readTextFromUri(context, uri)
            val importedParagraphs = splitIntoParagraphs(importedText)

            paragraphs = importedParagraphs
            currentParagraphIndex = 0
            fileName = getFileName(context, uri) ?: "Selected .txt file"

            if (importedParagraphs.isEmpty()) {
                Toast.makeText(
                    context,
                    "No readable paragraphs found.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (error: Exception) {
            Toast.makeText(
                context,
                "Failed to read file: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    DisposableEffect(Unit) {
        onInitializeTts { ready ->
            isTtsReady = ready

            if (!ready) {
                Toast.makeText(
                    context,
                    "Text-to-speech is not ready on this device.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        onDispose {
            stopPlayback()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "ReadMe",
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "TXT reader test",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "text/plain",
                            "text/*",
                            "application/octet-stream"
                        )
                    )
                }
            ) {
                Text("Pick .txt file")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = fileName,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (paragraphs.isEmpty()) {
                    "Paragraphs: 0"
                } else {
                    "Paragraph ${currentParagraphIndex + 1} of ${paragraphs.size}"
                },
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = currentParagraph.ifBlank {
                        "Pick a .txt file to begin."
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = isTtsReady && currentParagraph.isNotBlank(),
                onClick = {
                    if (isPlaying) {
                        stopPlayback()
                    } else {
                        playFromIndex(currentParagraphIndex)
                    }
                }
            ) {
                Text(
                    if (isPlaying) {
                        "Pause auto-read"
                    } else {
                        "Play from current paragraph"
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    stopPlayback()
                }
            ) {
                Text("Stop")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = paragraphs.isNotEmpty() && currentParagraphIndex > 0,
                    onClick = {
                        stopPlayback()
                        currentParagraphIndex -= 1
                    }
                ) {
                    Text("Previous")
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = paragraphs.isNotEmpty() && currentParagraphIndex < paragraphs.lastIndex,
                    onClick = {
                        stopPlayback()
                        currentParagraphIndex += 1
                    }
                ) {
                    Text("Next")
                }
            }
        }
    }
}

fun readTextFromUri(context: Context, uri: Uri): String {
    return context.contentResolver.openInputStream(uri)?.use { inputStream ->
        inputStream.readBytes().toString(Charset.forName("UTF-8"))
    }.orEmpty()
}

fun splitIntoParagraphs(text: String): List<String> {
    return text
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .split(Regex("\\n\\s*\\n+"))
        .map { paragraph ->
            paragraph
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n+"), " ")
                .trim()
        }
        .filter { it.isNotBlank() }
}

fun getFileName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

        if (nameIndex >= 0 && cursor.moveToFirst()) {
            cursor.getString(nameIndex)
        } else {
            null
        }
    }
}
