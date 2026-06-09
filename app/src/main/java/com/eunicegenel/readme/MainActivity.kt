package com.eunicegenel.readme

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eunicegenel.readme.ui.theme.ReadMeTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var textToSpeech: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ReadMeTheme {
                TtsTestScreen(
                    onInitializeTts = { onReady ->
                        initializeTts(onReady)
                    },
                    onSpeak = { text ->
                        speakText(text)
                    },
                    onStop = {
                        textToSpeech?.stop()
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

                onReady(isSupported)
            } else {
                onReady(false)
            }
        }
    }

    private fun speakText(text: String) {
        textToSpeech?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "readme_tts_test"
        )
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }
}

@Composable
fun TtsTestScreen(
    onInitializeTts: ((Boolean) -> Unit) -> Unit,
    onSpeak: (String) -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current

    var isTtsReady by remember { mutableStateOf(false) }

    val sampleText = """
        The rain tapped against the window like impatient fingers.
        
        Maria looked at the old wooden door and whispered, "Something is outside."
        
        Nobody moved. Nobody breathed.
    """.trimIndent()

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
            onStop()
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
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "ReadMe",
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Offline text-to-speech reader test",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = sampleText,
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = isTtsReady,
                onClick = {
                    onSpeak(sampleText)
                }
            ) {
                Text("Play sample narration")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onStop
            ) {
                Text("Stop")
            }
        }
    }
}