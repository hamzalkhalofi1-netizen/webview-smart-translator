package com.translator.webview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "TranslationManager"

/** Vertical distance (px) below which two adjacent blocks are merged for context. */
private const val BLOCK_MERGE_GAP_PX = 80f

/** Maximum characters in a single translation call to stay within ML Kit limits. */
private const val MAX_CHARS_PER_REQUEST = 5_000

class TranslationManager(context: Context) {

    // ── Public data types ─────────────────────────────────────────────────────

    data class TextBlock(val bounds: RectF, val originalText: String)
    data class TranslatedBlock(val bounds: RectF, val translatedText: String)

    // ── Language catalogue ────────────────────────────────────────────────────

    data class Language(val code: String, val label: String, val displayName: String)

    val supportedLanguages: List<Language> = listOf(
        Language(TranslateLanguage.ARABIC,     "AR", "العربية"),
        Language(TranslateLanguage.ENGLISH,    "EN", "English"),
        Language(TranslateLanguage.FRENCH,     "FR", "Français"),
        Language(TranslateLanguage.GERMAN,     "DE", "Deutsch"),
        Language(TranslateLanguage.SPANISH,    "ES", "Español"),
        Language(TranslateLanguage.TURKISH,    "TR", "Türkçe"),
        Language(TranslateLanguage.RUSSIAN,    "RU", "Русский"),
        Language(TranslateLanguage.CHINESE,    "ZH", "中文"),
        Language(TranslateLanguage.JAPANESE,   "JA", "日本語"),
    )

    // ── ML Kit state ──────────────────────────────────────────────────────────

    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null

    private var targetLanguageCode: String = TranslateLanguage.ARABIC
    private var sourceLanguageCode: String = TranslateLanguage.ENGLISH

    var isModelReady: Boolean = false
        private set

    var onModelStatusChanged: ((ready: Boolean, message: String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        initRecognizer()
        initTranslator(sourceLanguageCode, targetLanguageCode)
    }

    private fun initRecognizer() {
        try {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Log.d(TAG, "OCR recognizer ready")
        } catch (e: Exception) {
            Log.e(TAG, "OCR init failed", e)
        }
    }

    private fun initTranslator(source: String, target: String) {
        try {
            translator?.close()
            translator = null
            isModelReady = false

            if (source == target) {
                // Same language — no translation needed; treat as "ready" immediately
                isModelReady = true
                mainHandler.post { onModelStatusChanged?.invoke(true, "Same language — passthrough") }
                return
            }

            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
            Log.d(TAG, "Translator built: $source → $target")
            downloadModelIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "Translator init failed", e)
        }
    }

    private fun downloadModelIfNeeded() {
        val t = translator ?: return

        // ✅ No WiFi restriction — allow download over cellular so models
        //    are available immediately without waiting for Wi-Fi.
        val conditions = DownloadConditions.Builder().build()

        mainHandler.post { onModelStatusChanged?.invoke(false, "جارٍ تحميل نموذج الترجمة...") }

        t.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                isModelReady = true
                Log.d(TAG, "Model ready: $sourceLanguageCode → $targetLanguageCode")
                mainHandler.post { onModelStatusChanged?.invoke(true, "نموذج الترجمة جاهز") }
            }
            .addOnFailureListener { e ->
                val reason = when {
                    e is MlKitException && e.errorCode == MlKitException.NOT_FOUND ->
                        "النموذج غير موجود — تحقق من الاتصال"
                    e is MlKitException && e.errorCode == MlKitException.INTERNAL ->
                        "خطأ داخلي في ML Kit"
                    else -> e.message ?: "Unknown"
                }
                Log.w(TAG, "Model download failed: $reason")
                mainHandler.post { onModelStatusChanged?.invoke(false, reason) }
            }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getTargetLanguage(): Language =
        supportedLanguages.find { it.code == targetLanguageCode } ?: supportedLanguages[0]

    fun setTargetLanguage(languageCode: String) {
        if (languageCode == targetLanguageCode) return
        targetLanguageCode = languageCode
        isModelReady = false
        initTranslator(sourceLanguageCode, targetLanguageCode)
    }

    /**
     * Detects ALL text blocks in [bitmap], merges nearby blocks for context,
     * translates each group, then returns individual [TranslatedBlock]s mapped
     * back to their original screen coordinates.
     *
     * The merge step provides the translator with sentence-level context,
     * producing more natural results than single-word/phrase translation.
     */
    fun recognizeAndTranslate(bitmap: Bitmap, callback: (List<TranslatedBlock>) -> Unit) {
        val rec = recognizer
        if (rec == null) {
            Log.e(TAG, "Recognizer not initialized")
            mainHandler.post { callback(emptyList()) }
            return
        }

        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create InputImage", e)
            mainHandler.post { callback(emptyList()) }
            return
        }

        rec.process(image)
            .addOnSuccessListener { visionText ->
                try {
                    // ── 1. Collect every non-empty block ──────────────────────
                    val rawBlocks = visionText.textBlocks
                        .filter { it.boundingBox != null && it.text.isNotBlank() }
                        .map { block ->
                            val bb = block.boundingBox!!
                            TextBlock(
                                bounds = RectF(
                                    bb.left.toFloat(), bb.top.toFloat(),
                                    bb.right.toFloat(), bb.bottom.toFloat()
                                ),
                                originalText = block.text.trim()
                            )
                        }
                        .sortedBy { it.bounds.top }   // top-to-bottom reading order

                    Log.d(TAG, "OCR: ${rawBlocks.size} block(s) detected")

                    if (rawBlocks.isEmpty()) {
                        mainHandler.post { callback(emptyList()) }
                        return@addOnSuccessListener
                    }

                    // ── 2. Same-language passthrough (no translation needed) ──
                    if (sourceLanguageCode == targetLanguageCode) {
                        val passthrough = rawBlocks.map {
                            TranslatedBlock(it.bounds, it.originalText)
                        }
                        mainHandler.post { callback(passthrough) }
                        return@addOnSuccessListener
                    }

                    // ── 3. Group nearby blocks for context-aware translation ──
                    val groups = mergeNearbyBlocks(rawBlocks)
                    translateGroups(groups, rawBlocks, callback)

                } catch (e: Exception) {
                    Log.e(TAG, "OCR result processing error", e)
                    mainHandler.post { callback(emptyList()) }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed: ${e.message}")
                mainHandler.post { callback(emptyList()) }
            }
    }

    // ── Context grouping ──────────────────────────────────────────────────────

    /**
     * Groups consecutive blocks whose vertical gap is ≤ [BLOCK_MERGE_GAP_PX]
     * into a single paragraph. Returns list of (groupText, list of memberIndices).
     * Translating a whole paragraph gives ML Kit more context → better quality.
     */
    private fun mergeNearbyBlocks(blocks: List<TextBlock>): List<Pair<String, List<Int>>> {
        if (blocks.isEmpty()) return emptyList()

        val groups = mutableListOf<Pair<String, MutableList<Int>>>()
        var currentText = StringBuilder(blocks[0].originalText)
        var currentIndices = mutableListOf(0)
        var currentBottom = blocks[0].bounds.bottom

        for (i in 1 until blocks.size) {
            val block = blocks[i]
            val gap = block.bounds.top - currentBottom

            if (gap <= BLOCK_MERGE_GAP_PX &&
                currentText.length + block.originalText.length < MAX_CHARS_PER_REQUEST) {
                // Merge into current group
                currentText.append(" ").append(block.originalText)
                currentIndices.add(i)
            } else {
                // Save current group and start a new one
                groups.add(currentText.toString() to currentIndices)
                currentText = StringBuilder(block.originalText)
                currentIndices = mutableListOf(i)
            }
            currentBottom = maxOf(currentBottom, block.bounds.bottom)
        }
        groups.add(currentText.toString() to currentIndices)
        return groups
    }

    /**
     * Translates each group as one request, then assigns the translated text
     * back to each member block (split by newline from the original structure).
     */
    private fun translateGroups(
        groups: List<Pair<String, List<Int>>>,
        rawBlocks: List<TextBlock>,
        callback: (List<TranslatedBlock>) -> Unit
    ) {
        val t = translator
        if (t == null) {
            // No translator — return original text
            val fallback = rawBlocks.map { TranslatedBlock(it.bounds, it.originalText) }
            mainHandler.post { callback(fallback) }
            return
        }

        val resultMap  = HashMap<Int, String>(rawBlocks.size)
        val remaining  = AtomicInteger(groups.size)

        for ((groupText, memberIndices) in groups) {
            t.translate(groupText)
                .addOnSuccessListener { translated ->
                    // Distribute translated text back to each member block.
                    // If a group had N members, split translated text into N parts
                    // (by proportional character count) and assign to each block.
                    val parts = splitTranslation(translated, memberIndices.size)
                    memberIndices.forEachIndexed { i, blockIdx ->
                        resultMap[blockIdx] = parts.getOrElse(i) { translated }
                    }
                    if (remaining.decrementAndGet() == 0) {
                        val ordered = (0 until rawBlocks.size).map { idx ->
                            TranslatedBlock(
                                rawBlocks[idx].bounds,
                                resultMap[idx] ?: rawBlocks[idx].originalText
                            )
                        }
                        mainHandler.post { callback(ordered) }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Group translation failed: ${e.message}")
                    memberIndices.forEach { idx ->
                        resultMap[idx] = rawBlocks[idx].originalText
                    }
                    if (remaining.decrementAndGet() == 0) {
                        val ordered = (0 until rawBlocks.size).map { idx ->
                            TranslatedBlock(
                                rawBlocks[idx].bounds,
                                resultMap[idx] ?: rawBlocks[idx].originalText
                            )
                        }
                        mainHandler.post { callback(ordered) }
                    }
                }
        }
    }

    /**
     * Splits [text] into [n] roughly equal segments separated by sentence
     * boundaries where possible. Falls back to equal character splits.
     */
    private fun splitTranslation(text: String, n: Int): List<String> {
        if (n <= 1) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?؟।]\\s)"))
        if (sentences.size >= n) {
            // Distribute sentences evenly across n parts
            val chunkSize = maxOf(1, sentences.size / n)
            return (0 until n).map { i ->
                val from = i * chunkSize
                val to   = if (i == n - 1) sentences.size else minOf((i + 1) * chunkSize, sentences.size)
                sentences.subList(from, to).joinToString(" ")
            }
        }
        // Fewer sentences than blocks — just repeat the full translation
        return List(n) { text }
    }

    fun close() {
        try { recognizer?.close() } catch (e: Exception) { Log.w(TAG, "recognizer close: ${e.message}") }
        try { translator?.close() } catch (e: Exception) { Log.w(TAG, "translator close: ${e.message}") }
    }
}
