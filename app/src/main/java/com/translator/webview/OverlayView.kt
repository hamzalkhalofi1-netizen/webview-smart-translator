package com.translator.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * A custom View rendered as a floating, draggable, resizable overlay
 * on top of the WebView — entirely within the Activity's window
 * (no SYSTEM_ALERT_WINDOW permission needed).
 *
 * Rendering pipeline:
 *   1. Draw a scaled-down copy of the captured WebView bitmap.
 *   2. For each translated block, paint a mask rect over the original
 *      text region, then draw the translated text at the same coordinates.
 *   3. Provide drag (header area) and resize (bottom-right corner handle).
 */
@SuppressLint("ClickableViewAccessibility")
class OverlayView @JvmOverloads constructor(
    context: Context,
    private val translationManager: TranslationManager,
    private val onSyncRequested: () -> Unit,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── Geometry ─────────────────────────────────────────────────────────────

    private var overlayLeft   = 40f
    private var overlayTop    = 120f
    private var overlayWidth  = 900f
    private var overlayHeight = 660f

    private val headerHeight  = 90f
    private val handleSize    = 70f
    private val cornerRadius  = 28f
    private val minWidth      = 320f
    private val minHeight     = 220f

    // ── Touch ────────────────────────────────────────────────────────────────

    private enum class TouchMode { NONE, DRAG, RESIZE }

    private var touchMode = TouchMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val syncBtnRect = RectF()
    private var syncBtnPressed = false

    // ── Data ─────────────────────────────────────────────────────────────────

    private var sourceBitmap: Bitmap? = null
    private val translatedBlocks = mutableListOf<TranslationManager.TranslatedBlock>()
    private var isTranslating = false
    private var hasError = false

    // ── Paints ───────────────────────────────────────────────────────────────

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
    private val syncBgPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val syncTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    // ── Public API ───────────────────────────────────────────────────────────

    fun setSourceBitmap(bitmap: Bitmap) {
        sourceBitmap = bitmap
        translatedBlocks.clear()
        hasError = false
        invalidate()
    }

    fun startTranslation() {
        val bmp = sourceBitmap ?: return
        isTranslating = true
        hasError = false
        translatedBlocks.clear()
        invalidate()

        translationManager.recognizeAndTranslate(bmp) { blocks ->
            isTranslating = false
            if (blocks.isEmpty()) {
                hasError = true
            } else {
                translatedBlocks.addAll(blocks)
            }
            invalidate()
        }
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

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

        // Main background
        canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, bgPaint)

        // ── Bitmap + translated blocks ──────────────────────────────────────
        val contentTop = overlayTop + headerHeight
        sourceBitmap?.let { bmp ->
            val availW = overlayWidth
            val availH = overlayHeight - headerHeight
            val scaleX = availW / bmp.width
            val scaleY = availH / bmp.height
            val scale  = min(scaleX, scaleY)

            val scaledW  = bmp.width  * scale
            val scaledH  = bmp.height * scale
            val offsetX  = overlayLeft + (availW - scaledW) / 2f
            val offsetY  = contentTop  + (availH - scaledH) / 2f
            val dstRect  = RectF(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)

            // Clip to content area
            canvas.save()
            canvas.clipRect(RectF(overlayLeft, contentTop, right, bottom))

            // Draw dimmed original screenshot
            canvas.drawBitmap(bmp, null, dstRect, Paint().apply { alpha = 160 })

            // Overlay translated blocks
            for (block in translatedBlocks) {
                val bx    = offsetX + block.bounds.left  * scale
                val by    = offsetY + block.bounds.top   * scale
                val bxEnd = offsetX + block.bounds.right * scale
                val byEnd = offsetY + block.bounds.bottom* scale
                val blockH = byEnd - by
                val blockW = bxEnd - bx

                // Mask original text
                val blockRect = RectF(bx, by, bxEnd, byEnd)
                canvas.drawRoundRect(blockRect, 6f, 6f, maskPaint)

                // Draw translated text, auto-sizing to fit the block
                translatedTextPaint.textSize =
                    fitTextSize(block.translatedText, blockW - 8f, blockH, 12f, 36f)
                translatedTextPaint.textAlign = Paint.Align.LEFT

                drawWrappedText(
                    canvas,
                    block.translatedText,
                    bx + 4f,
                    by + translatedTextPaint.textSize,
                    blockW - 8f,
                    translatedTextPaint.textSize,
                    translatedTextPaint,
                    byEnd
                )
            }

            canvas.restore()
        }

        // ── Header ──────────────────────────────────────────────────────────
        canvas.save()
        canvas.clipRect(RectF(overlayLeft, overlayTop, right, overlayTop + headerHeight))
        canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, headerPaint)
        canvas.restore()

        // Title
        canvas.drawText("طبقة الترجمة", overlayLeft + 24f, overlayTop + 52f, titlePaint)
        canvas.drawText(
            if (isTranslating) "جارٍ الترجمة..." else "${translatedBlocks.size} كتلة نصية",
            overlayLeft + 24f,
            overlayTop + 78f,
            subtitlePaint
        )

        // Sync button
        val syncRight = right - 18f
        val syncLeft  = syncRight - 160f
        syncBtnRect.set(syncLeft, overlayTop + 20f, syncRight, overlayTop + 70f)
        syncBgPaint.color = if (syncBtnPressed)
            Color.argb(220, 56, 189, 248)
        else
            Color.argb(160, 12, 74, 110)
        canvas.drawRoundRect(syncBtnRect, 14f, 14f, syncBgPaint)
        canvas.drawText(
            "⟳ تحديث",
            syncBtnRect.centerX(),
            syncBtnRect.centerY() + 10f,
            syncTextPaint.apply {
                color = if (syncBtnPressed) Color.argb(255, 15, 23, 42)
                        else Color.argb(255, 56, 189, 248)
            }
        )

        // ── Border ──────────────────────────────────────────────────────────
        canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, borderPaint)

        // ── Loading / empty state ────────────────────────────────────────────
        if (isTranslating) {
            val cx = overlayLeft + overlayWidth / 2f
            val cy = contentTop  + (overlayHeight - headerHeight) / 2f
            val loadBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 15, 23, 42) }
            canvas.drawRoundRect(RectF(cx - 160f, cy - 55f, cx + 160f, cy + 55f), 18f, 18f, loadBg)
            canvas.drawText("جارٍ الترجمة...", cx, cy + 14f, loadingPaint)
        } else if (sourceBitmap == null) {
            val cx = overlayLeft + overlayWidth / 2f
            val cy = contentTop  + (overlayHeight - headerHeight) / 2f
            canvas.drawText("اضغط تحديث لالتقاط الصفحة وترجمتها", cx, cy, emptyPaint)
        } else if (hasError) {
            val cx = overlayLeft + overlayWidth / 2f
            val cy = contentTop  + (overlayHeight - headerHeight) / 2f
            canvas.drawText("لم يُعثر على نص في الصفحة", cx, cy, emptyPaint)
        }

        // ── Resize handle (bottom-right) ─────────────────────────────────────
        val hLeft = right  - handleSize
        val hTop  = bottom - handleSize
        canvas.drawRoundRect(
            RectF(hLeft, hTop, right, bottom),
            cornerRadius, cornerRadius, handlePaint
        )
        canvas.drawLine(hLeft + 16f, bottom - 16f, right - 16f, bottom - 16f, arrowPaint)
        canvas.drawLine(right - 16f, hTop + 16f,   right - 16f, bottom - 16f, arrowPaint)
    }

    // ── Touch handling ───────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val right  = overlayLeft + overlayWidth
        val bottom = overlayTop  + overlayHeight

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Sync button
                if (syncBtnRect.contains(x, y)) {
                    syncBtnPressed = true
                    invalidate()
                    return true
                }
                // Resize handle
                touchMode = when {
                    x > right - handleSize && y > bottom - handleSize -> TouchMode.RESIZE
                    y < overlayTop + headerHeight && x in overlayLeft..right -> TouchMode.DRAG
                    x in overlayLeft..right && y in overlayTop..bottom -> TouchMode.DRAG
                    else -> TouchMode.NONE
                }
                lastTouchX = x
                lastTouchY = y
            }

            MotionEvent.ACTION_MOVE -> {
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
                    TouchMode.NONE -> {}
                }
                lastTouchX = x
                lastTouchY = y
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (syncBtnPressed) {
                    syncBtnPressed = false
                    invalidate()
                    if (syncBtnRect.contains(x, y)) {
                        onSyncRequested()
                    }
                }
                touchMode = TouchMode.NONE
            }
        }
        return true
    }

    // ── Text helpers ─────────────────────────────────────────────────────────

    private fun fitTextSize(
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        minSize: Float,
        maxSize: Float
    ): Float {
        var size = maxSize
        val paint = Paint().apply { textSize = size }
        while (size > minSize) {
            val lines = wrappedLineCount(text, maxWidth, paint)
            if (lines * size * 1.25f <= maxHeight) break
            size -= 2f
            paint.textSize = size
        }
        return size
    }

    private fun wrappedLineCount(text: String, maxWidth: Float, paint: Paint): Int {
        var count = 0
        var line  = ""
        for (word in text.split(" ")) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) > maxWidth && line.isNotEmpty()) {
                count++
                line = word
            } else {
                line = test
            }
        }
        if (line.isNotEmpty()) count++
        return count
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float,
        paint: Paint,
        maxY: Float
    ) {
        var y    = startY
        var line = ""
        for (word in text.split(" ")) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) > maxWidth && line.isNotEmpty()) {
                if (y <= maxY) canvas.drawText(line, x, y, paint)
                y   += lineHeight * 1.25f
                line = word
            } else {
                line = test
            }
        }
        if (line.isNotEmpty() && y <= maxY) canvas.drawText(line, x, y, paint)
    }
}
