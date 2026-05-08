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

class TranslationManager(context: Context) {

    // ── Data classes ─────────────────────────────────────────────────────────

    data class TextBlock(val bounds: RectF, val originalText: String)
    data class TranslatedBlock(val bounds: RectF, val translatedText: String)

    // ── ML Kit clients (nullable — safe if init fails) ────────────────────────

    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var targetLanguageCode: String = TranslateLanguage.ARABIC
    var isModelReady: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        initRecognizer()
        initTranslator(TranslateLanguage.ENGLISH, targetLanguageCode)
    }

    private fun initRecognizer() {
        try {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Log.d(TAG, "OCR recognizer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OCR recognizer — ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun initTranslator(source: String, target: String) {
        try {
            translator?.close()
            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
            Log.d(TAG, "Translator built: $source → $target")
            downloadModelIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build translator — ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun downloadModelIfNeeded() {
        val t = translator ?: return
        val conditions = DownloadConditions.Builder().requireWifi().build()

        t.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                isModelReady = true
                Log.d(TAG, "Translation model ready (EN→$targetLanguageCode)")
            }
            .addOnFailureListener { e ->
                val reason = when {
                    e is MlKitException && e.errorCode == MlKitException.NOT_FOUND ->
                        "Model not found — check network"
                    e is MlKitException && e.errorCode == MlKitException.INTERNAL ->
                        "Internal ML Kit error"
                    else -> e.message ?: "Unknown"
                }
                Log.w(TAG, "Model download failed: $reason")
            }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setTargetLanguage(languageCode: String) {
        if (languageCode == targetLanguageCode) return
        targetLanguageCode = languageCode
        isModelReady = false
        initTranslator(TranslateLanguage.ENGLISH, languageCode)
    }

    fun recognizeAndTranslate(bitmap: Bitmap, callback: (List<TranslatedBlock>) -> Unit) {
        val rec = recognizer
        if (rec == null) {
            Log.e(TAG, "recognizeAndTranslate called but recognizer is null — init failed")
            mainHandler.post { callback(emptyList()) }
            return
        }

        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create InputImage: ${e.message}", e)
            mainHandler.post { callback(emptyList()) }
            return
        }

        rec.process(image)
            .addOnSuccessListener { visionText ->
                try {
                    val rawBlocks = visionText.textBlocks
                        .filter { it.boundingBox != null && it.text.isNotBlank() }
                        .map { block ->
                            val bb = block.boundingBox!!
                            TextBlock(
                                bounds = RectF(
                                    bb.left.toFloat(), bb.top.toFloat(),
                                    bb.right.toFloat(), bb.bottom.toFloat()
                                ),
                                originalText = block.text
                            )
                        }

                    Log.d(TAG, "OCR found ${rawBlocks.size} text block(s)")

                    if (rawBlocks.isEmpty()) {
                        mainHandler.post { callback(emptyList()) }
                    } else {
                        translateBlocks(rawBlocks, callback)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing OCR result: ${e.message}", e)
                    mainHandler.post { callback(emptyList()) }
                }
            }
            .addOnFailureListener { e ->
                val reason = when {
                    e is MlKitException && e.errorCode == MlKitException.NOT_FOUND ->
                        "OCR model not found"
                    e is MlKitException && e.errorCode == MlKitException.INTERNAL ->
                        "OCR internal error"
                    else -> e.message ?: "Unknown"
                }
                Log.e(TAG, "OCR failed: $reason")
                mainHandler.post { callback(emptyList()) }
            }
    }

    private fun translateBlocks(blocks: List<TextBlock>, callback: (List<TranslatedBlock>) -> Unit) {
        val t = translator
        if (t == null) {
            Log.e(TAG, "translateBlocks called but translator is null")
            val fallback = blocks.map { TranslatedBlock(it.bounds, it.originalText) }
            mainHandler.post { callback(fallback) }
            return
        }

        val result   = Array<TranslatedBlock?>(blocks.size) { null }
        val remaining = AtomicInteger(blocks.size)

        blocks.forEachIndexed { index, block ->
            t.translate(block.originalText)
                .addOnSuccessListener { translated ->
                    result[index] = TranslatedBlock(block.bounds, translated)
                    if (remaining.decrementAndGet() == 0) {
                        mainHandler.post { callback(result.filterNotNull()) }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Block[$index] translation failed: ${e.message} — using original")
                    result[index] = TranslatedBlock(block.bounds, block.originalText)
                    if (remaining.decrementAndGet() == 0) {
                        mainHandler.post { callback(result.filterNotNull()) }
                    }
                }
        }
    }

    fun close() {
        try { recognizer?.close() } catch (e: Exception) { Log.w(TAG, "Error closing recognizer: ${e.message}") }
        try { translator?.close() } catch (e: Exception) { Log.w(TAG, "Error closing translator: ${e.message}") }
    }
}
