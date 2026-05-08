package com.translator.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Floating, draggable, resizable, scrollable overlay drawn entirely on Canvas.
 *
 * Header row (left → right):
 *   [طبقة الترجمة / subtitle]  |  [🌐 LANG]  |  [⟳ تحديث]
 *
 * Content area:
 *   - Dimmed screenshot with translated text blocks overlaid at their coordinates.
 *   - Vertical scroll via swipe (when content overflows).
 *
 * Resize handle: bottom-right corner.
 */
@SuppressLint("ClickableViewAccessibility")
class OverlayView(
    context: Context,
    private val translationManager: TranslationManager,
    private val onSyncRequested: () -> Unit
) : View(context) {

    // ── Geometry ─────────────────────────────────────────────────────────────

    private var overlayLeft   = 40f
    private var overlayTop    = 120f
    private var overlayWidth  = 900f
    private var overlayHeight = 660f

    private val headerHeight = 90f
    private val handleSize   = 70f
    private val cornerRadius = 28f
    private val minWidth     = 320f
    private val minHeight    = 220f

    // ── Scroll state ──────────────────────────────────────────────────────────

    private var contentScrollY    = 0f
    private var maxContentScrollY = 0f

    // ── Touch ─────────────────────────────────────────────────────────────────

    private enum class TouchMode { NONE, DRAG, RESIZE, SCROLL }

    private var touchMode  = TouchMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var velocityTracker: VelocityTracker? = null

    private val syncBtnRect = RectF()
    private val langBtnRect = RectF()
    private var syncBtnPressed = false
    private var langBtnPressed = false

    // ── Translation data ──────────────────────────────────────────────────────

    private var sourceBitmap: Bitmap? = null
    private val translatedBlocks = mutableListOf<TranslationManager.TranslatedBlock>()
    private var isTranslating  = false
    private var hasError       = false
    private var statusMessage  = ""

    // ── Language cycling ──────────────────────────────────────────────────────

    private val languages get() = translationManager.supportedLanguages
    private var langIndex = 0   // index into supportedLanguages

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 15, 23, 42)
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 20, 32, 54)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 56, 189, 248)
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(242, 10, 18, 38)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 224, 242, 254)
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 100, 116, 139)
        textSize = 22f
    }
    private val btnBgPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 56, 189, 248)
        textSize = 26f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val translatedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 240, 249, 255)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val loadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        textAlign = Paint.Align.CENTER
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 100, 116, 139)
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 15, 23, 42)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val scrollBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 56, 189, 248)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Call to set a fresh screenshot. Clears previous state immediately. */
    fun setSourceBitmap(bitmap: Bitmap) {
        sourceBitmap     = bitmap
        translatedBlocks.clear()
        contentScrollY   = 0f
        maxContentScrollY = 0f
        hasError         = false
        statusMessage    = ""
        invalidate()
    }

    /**
     * Clears any previous results, shows a loading state, then kicks off
     * recognition + translation. The overlay re-draws automatically when done.
     */
    fun startTranslation() {
        val bmp = sourceBitmap ?: return
        isTranslating = true
        hasError      = false
        statusMessage = ""

        // ✅ Clean overwrite: always clear old blocks before starting
        translatedBlocks.clear()
        contentScrollY = 0f
        invalidate()

        translationManager.recognizeAndTranslate(bmp) { blocks ->
            isTranslating = false
            translatedBlocks.clear()   // clear again on callback (thread safety)
            if (blocks.isEmpty()) {
                hasError = true
            } else {
                translatedBlocks.addAll(blocks)
                // Estimate total content height for scroll limit
                val bmp2 = sourceBitmap
                if (bmp2 != null) {
                    val scale = min(
                        overlayWidth / bmp2.width,
                        (overlayHeight - headerHeight) / bmp2.height
                    )
                    maxContentScrollY = max(0f, bmp2.height * scale - (overlayHeight - headerHeight))
                }
            }
            invalidate()
        }
    }

    /** Programmatically change the target language (called from Activity). */
    fun setTargetLanguage(code: String) {
        val idx = languages.indexOfFirst { it.code == code }
        if (idx >= 0) langIndex = idx
        translationManager.setTargetLanguage(code)
        // Automatically re-translate with the new language
        if (sourceBitmap != null && !isTranslating) startTranslation()
    }

    fun getCurrentLanguage(): TranslationManager.Language = languages[langIndex]

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val right  = overlayLeft + overlayWidth
        val bottom = overlayTop  + overlayHeight
        val outerRect = RectF(overlayLeft, overlayTop, right, bottom)

        // Drop shadow
        canvas.drawRoundRect(
            RectF(overlayLeft + 10, overlayTop + 10, right + 10, bottom + 10),
            cornerRadius, cornerRadius, shadowPaint
        )

        // Background
        canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, bgPaint)

        // ── Content area ─────────────────────────────────────────────────────
        val contentTop = overlayTop + headerHeight
        sourceBitmap?.let { bmp ->
            val availW = overlayWidth
            val availH = overlayHeight - headerHeight
            val scale  = min(availW / bmp.width, availH / bmp.height)

            val scaledW = bmp.width  * scale
            val scaledH = bmp.height * scale
            val offsetX = overlayLeft + (availW - scaledW) / 2f
            // Apply vertical scroll offset
            val offsetY = contentTop  + (availH - scaledH) / 2f - contentScrollY

            canvas.save()
            canvas.clipRect(RectF(overlayLeft, contentTop, right, bottom))

            // Dimmed screenshot
            canvas.drawBitmap(bmp, null,
                RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH),
                Paint().apply { alpha = 160 })

            // ✅ All translated blocks — iterate every block, clear before draw
            for (block in translatedBlocks) {
                val bx    = offsetX + block.bounds.left   * scale
                val by    = offsetY + block.bounds.top    * scale
                val bxEnd = offsetX + block.bounds.right  * scale
                val byEnd = offsetY + block.bounds.bottom * scale
                val blockH = byEnd - by
                val blockW = bxEnd - bx

                // Mask original text region
                canvas.drawRoundRect(RectF(bx, by, bxEnd, byEnd), 6f, 6f, maskPaint)

                // Draw translated text sized to fit the block
                translatedTextPaint.textSize =
                    fitTextSize(block.translatedText, blockW - 8f, blockH, 12f, 36f)
                translatedTextPaint.textAlign = Paint.Align.LEFT

                drawWrappedText(
                    canvas, block.translatedText,
                    bx + 4f, by + translatedTextPaint.textSize,
                    blockW - 8f, translatedTextPaint.textSize,
                    translatedTextPaint, byEnd
                )
            }

            canvas.restore()

            // Scroll bar (show only if content overflows)
            drawScrollBar(canvas, contentTop, bottom, scaledH, availH)
        }

        // ── Header ───────────────────────────────────────────────────────────
        canvas.save()
        canvas.clipRect(RectF(overlayLeft, overlayTop, right, overlayTop + headerHeight))
        canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, headerPaint)
        canvas.restore()

        // Title
        canvas.drawText("طبقة الترجمة", overlayLeft + 24f, overlayTop + 52f, titlePaint)
        canvas.drawText(
            when {
                isTranslating -> "جارٍ الترجمة..."
                statusMessage.isNotEmpty() -> statusMessage
                else -> "${translatedBlocks.size} كتلة نصية"
            },
            overlayLeft + 24f, overlayTop + 78f, subtitlePaint
        )

        // ── Language button ───────────────────────────────────────────────────
        val langRight = right - 196f
        val langLeft  = langRight - 110f
        langBtnRect.set(langLeft, overlayTop + 20f, langRight, overlayTop + 70f)
        btnBgPaint.color = if (langBtnPressed)
            Color.argb(220, 56, 189, 248) else Color.argb(160, 20, 60, 100)
        canvas.drawRoundRect(langBtnRect, 14f, 14f, btnBgPaint)
        canvas.drawText(
            "🌐 ${languages[langIndex].label}",
            langBtnRect.centerX(), langBtnRect.centerY() + 10f,
            btnTextPaint.apply {
                color = if (langBtnPressed) Color.argb(255, 15, 23, 42)
                        else Color.argb(255, 56, 189, 248)
            }
        )

        // ── Sync button ───────────────────────────────────────────────────────
        val syncRight = right - 18f
        val syncLeft  = syncRight - 168f
        syncBtnRect.set(syncLeft, overlayTop + 20f, syncRight, overlayTop + 70f)
        btnBgPaint.color = if (syncBtnPressed)
            Color.argb(220, 56, 189, 248) else Color.argb(160, 12, 74, 110)
        canvas.drawRoundRect(syncBtnRect, 14f, 14f, btnBgPaint)
        canvas.drawText(
            "⟳ تحديث",
            syncBtnRect.centerX(), syncBtnRect.centerY() + 10f,
            btnTextPaint.apply {
                color = if (syncBtnPressed) Color.argb(255, 15, 23, 42)
                        else Color.argb(255, 56, 189, 248)
            }
        )

        // ── Border ────────────────────────────────────────────────────────────
        canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, borderPaint)

        // ── State overlays ────────────────────────────────────────────────────
        val cx = overlayLeft + overlayWidth / 2f
        val cy = contentTop  + (overlayHeight - headerHeight) / 2f
        when {
            isTranslating -> {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 15, 23, 42) }
                canvas.drawRoundRect(RectF(cx - 180f, cy - 55f, cx + 180f, cy + 55f), 18f, 18f, bg)
                canvas.drawText("جارٍ الترجمة...", cx, cy + 14f, loadingPaint)
            }
            sourceBitmap == null ->
                canvas.drawText("اضغط تحديث لالتقاط الصفحة", cx, cy, emptyPaint)
            hasError ->
                canvas.drawText("لم يُعثر على نص في الصفحة", cx, cy, emptyPaint)
        }

        // ── Resize handle (bottom-right) ─────────────────────────────────────
        val hLeft = right  - handleSize
        val hTop  = bottom - handleSize
        canvas.drawRoundRect(RectF(hLeft, hTop, right, bottom), cornerRadius, cornerRadius, handlePaint)
        canvas.drawLine(hLeft + 16f, bottom - 16f, right - 16f, bottom - 16f, arrowPaint)
        canvas.drawLine(right - 16f, hTop  + 16f,  right - 16f, bottom - 16f, arrowPaint)
    }

    private fun drawScrollBar(
        canvas: Canvas,
        contentTop: Float,
        bottom: Float,
        contentH: Float,
        viewportH: Float
    ) {
        if (maxContentScrollY <= 0f || contentH <= viewportH) return

        val trackH    = bottom - contentTop
        val barH      = max(40f, trackH * (viewportH / contentH))
        val barTop    = contentTop + (contentScrollY / maxContentScrollY) * (trackH - barH)
        val barRight  = overlayLeft + overlayWidth - 6f
        val barLeft   = barRight - 8f
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barTop + barH), 4f, 4f, scrollBarPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val right  = overlayLeft + overlayWidth
        val bottom = overlayTop  + overlayHeight
        val contentTop = overlayTop + headerHeight

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)

                when {
                    // Sync button
                    syncBtnRect.contains(x, y) -> {
                        syncBtnPressed = true; invalidate(); return true
                    }
                    // Language button
                    langBtnRect.contains(x, y) -> {
                        langBtnPressed = true; invalidate(); return true
                    }
                    // Resize handle
                    x > right - handleSize && y > bottom - handleSize ->
                        touchMode = TouchMode.RESIZE
                    // Content area scroll
                    x in overlayLeft..right && y in contentTop..bottom ->
                        touchMode = TouchMode.SCROLL
                    // Header drag
                    y in overlayTop..(overlayTop + headerHeight) && x in overlayLeft..right ->
                        touchMode = TouchMode.DRAG
                    else -> touchMode = TouchMode.NONE
                }
                lastTouchX = x; lastTouchY = y
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                when (touchMode) {
                    TouchMode.DRAG -> {
                        overlayLeft = max(0f, min(overlayLeft + dx, width  - overlayWidth))
                        overlayTop  = max(0f, min(overlayTop  + dy, height - overlayHeight))
                    }
                    TouchMode.RESIZE -> {
                        overlayWidth  = max(minWidth,  min(overlayWidth  + dx, width  - overlayLeft))
                        overlayHeight = max(minHeight, min(overlayHeight + dy, height - overlayTop))
                    }
                    TouchMode.SCROLL -> {
                        // Scroll content (invert dy: swipe up = scroll down)
                        contentScrollY = max(0f, min(contentScrollY - dy, maxContentScrollY))
                    }
                    TouchMode.NONE -> {}
                }
                lastTouchX = x; lastTouchY = y
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when {
                    syncBtnPressed -> {
                        syncBtnPressed = false; invalidate()
                        if (syncBtnRect.contains(x, y)) onSyncRequested()
                    }
                    langBtnPressed -> {
                        langBtnPressed = false; invalidate()
                        if (langBtnRect.contains(x, y)) cycleLanguage()
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                touchMode = TouchMode.NONE
            }
        }
        return true
    }

    private fun cycleLanguage() {
        langIndex = (langIndex + 1) % languages.size
        val lang = languages[langIndex]
        statusMessage = "⟳ ${lang.displayName}"
        translationManager.setTargetLanguage(lang.code)
        if (sourceBitmap != null) startTranslation()
        else invalidate()
    }

    // ── Text helpers ──────────────────────────────────────────────────────────

    private fun fitTextSize(
        text: String, maxWidth: Float, maxHeight: Float,
        minSize: Float, maxSize: Float
    ): Float {
        var size  = maxSize
        val paint = Paint().apply { textSize = size }
        while (size > minSize) {
            val lines = wrappedLineCount(text, maxWidth, paint)
            if (lines * size * 1.25f <= maxHeight) break
            size -= 2f; paint.textSize = size
        }
        return size
    }

    private fun wrappedLineCount(text: String, maxWidth: Float, paint: Paint): Int {
        var count = 0; var line = ""
        for (word in text.split(" ")) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) > maxWidth && line.isNotEmpty()) {
                count++; line = word
            } else { line = test }
        }
        if (line.isNotEmpty()) count++
        return count
    }

    private fun drawWrappedText(
        canvas: Canvas, text: String,
        x: Float, startY: Float, maxWidth: Float,
        lineHeight: Float, paint: Paint, maxY: Float
    ) {
        var y = startY; var line = ""
        for (word in text.split(" ")) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) > maxWidth && line.isNotEmpty()) {
                if (y <= maxY) canvas.drawText(line, x, y, paint)
                y += lineHeight * 1.25f; line = word
            } else { line = test }
        }
        if (line.isNotEmpty() && y <= maxY) canvas.drawText(line, x, y, paint)
    }
}
