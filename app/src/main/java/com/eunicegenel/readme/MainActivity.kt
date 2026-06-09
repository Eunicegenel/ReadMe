package com.eunicegenel.readme

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Html
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eunicegenel.readme.tts.KokoroNarrationEngine
import com.eunicegenel.readme.ui.theme.ReadMeTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class KokoroSpeakerOption(
    val id: Int,
    val name: String,
    val description: String
)

val kokoroSpeakerOptions = listOf(
    KokoroSpeakerOption(0, "af", "Female voice"),
    KokoroSpeakerOption(1, "af_bella", "Female voice"),
    KokoroSpeakerOption(2, "af_nicole", "Female voice"),
    KokoroSpeakerOption(3, "af_sarah", "Female voice"),
    KokoroSpeakerOption(4, "af_sky", "Female voice"),
    KokoroSpeakerOption(5, "am_adam", "Male voice"),
    KokoroSpeakerOption(6, "am_michael", "Male voice"),
    KokoroSpeakerOption(7, "bf_emma", "Female voice"),
    KokoroSpeakerOption(8, "bf_isabella", "Female voice"),
    KokoroSpeakerOption(9, "bm_george", "Male voice"),
    KokoroSpeakerOption(10, "bm_lewis", "Male voice")
)

class MainActivity : ComponentActivity() {
    private var kokoroEngine: KokoroNarrationEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PDFBoxResourceLoader.init(applicationContext)
        kokoroEngine = KokoroNarrationEngine(applicationContext)

        setContent {
            ReadMeTheme {
                ReaderScreen(
                    onInitializeEngine = { onReady ->
                        initializeKokoro(onReady)
                    },
                    onSpeak = { text, speakerId, speed, onDone, onError ->
                        speakWithKokoro(
                            text = text,
                            speakerId = speakerId,
                            speed = speed,
                            onDone = onDone,
                            onError = onError
                        )
                    },
                    onStop = {
                        stopSpeaking()
                    }
                )
            }
        }
    }

    private fun initializeKokoro(onReady: (Boolean, String?) -> Unit) {
      onReady(true, null)
      kokoroEngine?.warmUp()
    }

    private fun speakWithKokoro(
        text: String,
        speakerId: Int,
        speed: Float,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        kokoroEngine?.speak(
            text = text,
            speakerId = speakerId,
            speed = speed,
            onDone = onDone,
            onError = onError
        )
    }

    private fun stopSpeaking() {
        kokoroEngine?.stop()
    }

    override fun onDestroy() {
        kokoroEngine?.shutdown()
        kokoroEngine = null
        super.onDestroy()
    }
}

@Composable
fun ReaderScreen(
    onInitializeEngine: ((Boolean, String?) -> Unit) -> Unit,
    onSpeak: (String, Int, Float, () -> Unit, (Throwable) -> Unit) -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current

    var isEngineReady by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }

    var fileName by remember { mutableStateOf("No file selected") }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentParagraphIndex by remember { mutableIntStateOf(0) }

    var selectedSpeakerId by remember { mutableIntStateOf(5) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var showSpeakerDialog by remember { mutableStateOf(false) }

    val selectedSpeaker = kokoroSpeakerOptions.firstOrNull { it.id == selectedSpeakerId }
        ?: kokoroSpeakerOptions.first()

    val currentParagraph = paragraphs.getOrNull(currentParagraphIndex).orEmpty()

    fun stopPlayback() {
        isPlaying = false
        isGenerating = false
        onStop()
    }

    fun playFromIndex(index: Int) {
        val paragraph = paragraphs.getOrNull(index)

        if (paragraph.isNullOrBlank()) {
            isPlaying = false
            isGenerating = false
            return
        }

        currentParagraphIndex = index
        isPlaying = true
        isGenerating = true

        onSpeak(
            paragraph,
            selectedSpeakerId,
            speed,
            {
                val nextIndex = index + 1

                isGenerating = false

                if (isPlaying && nextIndex in paragraphs.indices) {
                    playFromIndex(nextIndex)
                } else {
                    isPlaying = false
                }
            },
            { error ->
                isGenerating = false
                isPlaying = false

                Toast.makeText(
                    context,
                    "Kokoro failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        try {
            stopPlayback()

            val selectedFileName = getFileName(context, uri) ?: "Selected file"
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            val fileBytes = readBytesFromUri(context, uri)

            val importedText = when {
                selectedFileName.endsWith(".epub", ignoreCase = true) ||
                    mimeType.equals("application/epub+zip", ignoreCase = true) -> {
                    extractTextFromEpub(fileBytes)
                }

                selectedFileName.endsWith(".pdf", ignoreCase = true) ||
                    mimeType.equals("application/pdf", ignoreCase = true) -> {
                    extractTextFromPdf(context, fileBytes)
                }

                selectedFileName.endsWith(".txt", ignoreCase = true) ||
                    mimeType.startsWith("text/", ignoreCase = true) ||
                    mimeType.equals("application/octet-stream", ignoreCase = true) -> {
                    fileBytes.toString(Charset.forName("UTF-8"))
                }

                else -> {
                    fileBytes.toString(Charset.forName("UTF-8"))
                }
            }

            val importedParagraphs = splitIntoParagraphs(importedText)

            paragraphs = importedParagraphs
            currentParagraphIndex = 0
            fileName = selectedFileName

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
        onInitializeEngine { ready, errorMessage ->
            isEngineReady = ready

            if (!ready) {
                Toast.makeText(
                    context,
                    "Kokoro is not ready: ${errorMessage ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        onDispose {
            stopPlayback()
        }
    }

    if (showSpeakerDialog) {
        AlertDialog(
            onDismissRequest = {
                showSpeakerDialog = false
            },
            title = {
                Text("Select Kokoro speaker")
            },
            text = {
                Column(
                    modifier = Modifier
                        .height(420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    kokoroSpeakerOptions.forEach { speaker ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                stopPlayback()
                                selectedSpeakerId = speaker.id
                                showSpeakerDialog = false
                            }
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "${speaker.id} • ${speaker.name}",
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                Text(
                                    text = speaker.description,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSpeakerDialog = false
                    }
                ) {
                    Text("Close")
                }
            }
        )
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
                text = "TXT / EPUB / PDF • Kokoro neural TTS",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "text/plain",
                            "text/*",
                            "application/epub+zip",
                            "application/pdf",
                            "application/octet-stream"
                        )
                    )
                }
            ) {
                Text("Pick .txt, .epub, or .pdf file")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = isEngineReady,
                onClick = {
                    showSpeakerDialog = true
                }
            ) {
                Text("Speaker: ${selectedSpeaker.id} • ${selectedSpeaker.name}")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Speed: ${"%.2f".format(speed)}",
                style = MaterialTheme.typography.bodyMedium
            )

            Slider(
                value = speed,
                onValueChange = {
                    speed = it
                },
                valueRange = 0.75f..1.35f,
                enabled = !isPlaying
            )

            Spacer(modifier = Modifier.height(12.dp))

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

            if (isGenerating) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Generating narration...",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = currentParagraph.ifBlank {
                        "Pick a .txt, .epub, or .pdf file to begin."
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = isEngineReady && currentParagraph.isNotBlank(),
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

fun readBytesFromUri(context: Context, uri: Uri): ByteArray {
    return context.contentResolver.openInputStream(uri)?.use { inputStream ->
        inputStream.readBytes()
    } ?: ByteArray(0)
}

fun splitIntoParagraphs(text: String): List<String> {
    return text
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\u00A0", " ")
        .split(Regex("\\n\\s*\\n+"))
        .flatMap { paragraph ->
            splitLongParagraph(paragraph)
        }
        .map { paragraph ->
            paragraph
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n+"), " ")
                .trim()
        }
        .filter { it.isNotBlank() }
}

fun splitLongParagraph(paragraph: String, maxLength: Int = 220): List<String> {
    val cleaned = paragraph.trim()

    if (cleaned.length <= maxLength) {
        return listOf(cleaned)
    }

    val sentences = cleaned.split(Regex("(?<=[.!?])\\s+"))
    val chunks = mutableListOf<String>()
    val currentChunk = StringBuilder()

    for (sentence in sentences) {
        val candidateLength = currentChunk.length + sentence.length + 1

        if (candidateLength > maxLength && currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
            currentChunk.clear()
        }

        currentChunk.append(sentence).append(" ")
    }

    if (currentChunk.isNotBlank()) {
        chunks.add(currentChunk.toString().trim())
    }

    return chunks
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

fun extractTextFromPdf(context: Context, pdfBytes: ByteArray): String {
    PDFBoxResourceLoader.init(context.applicationContext)

    return PDDocument.load(ByteArrayInputStream(pdfBytes)).use { document ->
        val stripper = PDFTextStripper()
        stripper.setSortByPosition(true)
        stripper.getText(document).trim()
    }
}

fun extractTextFromEpub(epubBytes: ByteArray): String {
    val entries = unzipToMap(epubBytes)

    val containerXml = entries["META-INF/container.xml"]
        ?: throw IllegalArgumentException("EPUB container.xml not found.")

    val opfPath = parseOpfPath(containerXml)
    val opfBytes = entries[opfPath]
        ?: throw IllegalArgumentException("EPUB package file not found: $opfPath")

    val chapterPaths = parseChapterPathsFromOpf(opfBytes, opfPath)

    if (chapterPaths.isEmpty()) {
        throw IllegalArgumentException("No readable EPUB chapters found.")
    }

    return chapterPaths.joinToString(separator = "\n\n") { chapterPath ->
        val chapterBytes = entries[chapterPath]

        if (chapterBytes == null) {
            ""
        } else {
            htmlToPlainText(chapterBytes.toString(Charset.forName("UTF-8")))
        }
    }.trim()
}

fun unzipToMap(zipBytes: ByteArray): Map<String, ByteArray> {
    val result = mutableMapOf<String, ByteArray>()

    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInputStream ->
        while (true) {
            val entry = zipInputStream.nextEntry ?: break

            if (!entry.isDirectory) {
                result[entry.name] = zipInputStream.readBytes()
            }

            zipInputStream.closeEntry()
        }
    }

    return result
}

fun parseOpfPath(containerXmlBytes: ByteArray): String {
    val document = parseXml(containerXmlBytes)
    val rootFiles = document.getElementsByTagName("rootfile")

    if (rootFiles.length == 0) {
        val rootFilesByNamespace = document.getElementsByTagNameNS("*", "rootfile")

        if (rootFilesByNamespace.length == 0) {
            throw IllegalArgumentException("No rootfile found in EPUB container.")
        }

        val rootFile = rootFilesByNamespace.item(0) as Element
        return rootFile.getAttribute("full-path")
    }

    val rootFile = rootFiles.item(0) as Element
    return rootFile.getAttribute("full-path")
}

fun parseChapterPathsFromOpf(opfBytes: ByteArray, opfPath: String): List<String> {
    val document = parseXml(opfBytes)

    val manifestItems = getElementsByLocalName(document.documentElement, "item")
    val spineItems = getElementsByLocalName(document.documentElement, "itemref")

    val manifestById = manifestItems.associate { item ->
        item.getAttribute("id") to item
    }

    val opfBasePath = opfPath.substringBeforeLast("/", missingDelimiterValue = "")

    return spineItems.mapNotNull { itemRef ->
        val idRef = itemRef.getAttribute("idref")
        val manifestItem = manifestById[idRef] ?: return@mapNotNull null
        val href = manifestItem.getAttribute("href")

        if (href.isBlank()) {
            null
        } else {
            normalizeZipPath(
                if (opfBasePath.isBlank()) {
                    href
                } else {
                    "$opfBasePath/$href"
                }
            )
        }
    }
}

fun parseXml(xmlBytes: ByteArray): org.w3c.dom.Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true

    val builder = factory.newDocumentBuilder()

    return builder.parse(ByteArrayInputStream(xmlBytes))
}

fun getElementsByLocalName(root: Element, localName: String): List<Element> {
    val results = mutableListOf<Element>()
    val nodes = root.getElementsByTagNameNS("*", localName)

    for (index in 0 until nodes.length) {
        val node = nodes.item(index)

        if (node is Element) {
            results.add(node)
        }
    }

    if (results.isNotEmpty()) {
        return results
    }

    val fallbackNodes = root.getElementsByTagName(localName)

    for (index in 0 until fallbackNodes.length) {
        val node = fallbackNodes.item(index)

        if (node is Element) {
            results.add(node)
        }
    }

    return results
}

fun normalizeZipPath(path: String): String {
    val parts = mutableListOf<String>()

    path.split("/").forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> {
                if (parts.isNotEmpty()) {
                    parts.removeAt(parts.lastIndex)
                }
            }

            else -> parts.add(part)
        }
    }

    return parts.joinToString("/")
}

fun htmlToPlainText(html: String): String {
    val withoutScripts = html
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "</p>\n\n")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "<br>\n")
        .replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\n\n")

    return Html.fromHtml(withoutScripts, Html.FROM_HTML_MODE_LEGACY)
        .toString()
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
