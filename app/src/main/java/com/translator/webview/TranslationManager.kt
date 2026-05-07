package com.translator.webview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "TranslationManager"

/**
 * Wraps ML Kit on-device Text Recognition + Translation.
 *
 * Flow:
 *   Bitmap → TextRecognition (OCR) → list of (RectF, rawText)
 *            → Translation (on-device) → list of (RectF, translatedText)
 *            → callback on main thread
 *
 * Language models are downloaded once over Wi-Fi and then used offline.
 */
class TranslationManager(context: Context) {

    // ── ML Kit clients ───────────────────────────────────────────────────────

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Default: EN → AR. Call setTargetLanguage() to change at runtime.
    private var targetLanguageCode: String = TranslateLanguage.ARABIC
    private var translator = buildTranslator(TranslateLanguage.ENGLISH, targetLanguageCode)
    private var isModelReady = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Data ─────────────────────────────────────────────────────────────────

    data class TextBlock(val bounds: RectF, val originalText: String)
    data class TranslatedBlock(val bounds: RectF, val translatedText: String)

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        downloadModelIfNeeded()
    }

    private fun buildTranslator(source: String, target: String) =
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
        )

    private fun downloadModelIfNeeded() {
        val conditions = DownloadConditions.Builder()
            .requireWifi()          // download models over Wi-Fi only
            .build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                isModelReady = true
                Log.d(TAG, "Translation model ready")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Model download failed (will retry on translate): ${e.message}")
            }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Change the target language at runtime and re-download the model if needed.
     * @param languageCode one of the TranslateLanguage.* constants
     */
    fun setTargetLanguage(languageCode: String) {
        if (languageCode == targetLanguageCode) return
        translator.close()
        targetLanguageCode = languageCode
        isModelReady = false
        translator = buildTranslator(TranslateLanguage.ENGLISH, languageCode)
        downloadModelIfNeeded()
    }

    /**
     * Run OCR + translation on [bitmap].
     * [callback] is always invoked on the main thread with the list of translated blocks.
     * On failure returns an empty list.
     */
    fun recognizeAndTranslate(bitmap: Bitmap, callback: (List<TranslatedBlock>) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawBlocks = visionText.textBlocks
                    .filter { it.boundingBox != null && it.text.isNotBlank() }
                    .map { block ->
                        val bb = block.boundingBox!!
                        TextBlock(
                            bounds = RectF(
                                bb.left.toFloat(),
                                bb.top.toFloat(),
                                bb.right.toFloat(),
                                bb.bottom.toFloat()
                            ),
                            originalText = block.text
                        )
                    }

                if (rawBlocks.isEmpty()) {
                    mainHandler.post { callback(emptyList()) }
                    return@addOnSuccessListener
                }

                translateBlocks(rawBlocks, callback)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed: ${e.message}")
                mainHandler.post { callback(emptyList()) }
            }
    }

    private fun translateBlocks(blocks: List<TextBlock>, callback: (List<TranslatedBlock>) -> Unit) {
        val result = Array<TranslatedBlock?>(blocks.size) { null }
        val remaining = AtomicInteger(blocks.size)

        blocks.forEachIndexed { index, block ->
            translator.translate(block.originalText)
                .addOnSuccessListener { translated ->
                    result[index] = TranslatedBlock(block.bounds, translated)
                    if (remaining.decrementAndGet() == 0) {
                        mainHandler.post { callback(result.filterNotNull()) }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Translation failed for block: ${e.message}")
                    // Fall back to original text if translation fails
                    result[index] = TranslatedBlock(block.bounds, block.originalText)
                    if (remaining.decrementAndGet() == 0) {
                        mainHandler.post { callback(result.filterNotNull()) }
                    }
                }
        }
    }

    fun close() {
        recognizer.close()
        translator.close()
    }
}
