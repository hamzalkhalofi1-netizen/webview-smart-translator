package com.translator.webview

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TranslationService : Service() {

    companion object {
        private const val TAG = "TranslationService"
        const val ACTION_STOP = "com.translator.webview.ACTION_STOP_SERVICE"
    }

    inner class LocalBinder : Binder() {
        fun getService(): TranslationService = this@TranslationService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private lateinit var windowManager: WindowManager
    private var translationManager: TranslationManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlayView: OverlayView? = null
    private var isOverlayVisible = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val mgr = TranslationManager(this@TranslationService)
                    launch(Dispatchers.Main) {
                        translationManager = mgr
                        try {
                            attachBubble()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to attach bubble", e)
                            showError("فشل إنشاء الفقاعة: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TranslationManager init failed", e)
                    launch(Dispatchers.Main) {
                        showError("فشل تهيئة نموذج الترجمة: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service onCreate crashed", e)
            showError("فشل تشغيل الخدمة: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent?.action == ACTION_STOP) {
                stopSelf()
                return START_NOT_STICKY
            }
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.buildNotification(this)
            )
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand crashed", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            detachViews()
            translationManager?.close()
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error", e)
        }
        super.onDestroy()
    }

    // ── Bubble ────────────────────────────────────────────────────────────────

    private fun attachBubble() {
        val view = LayoutInflater.from(this).inflate(R.layout.view_translate_bubble, null)
        bubbleView = view

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 200
        }
        bubbleParams = lp

        view.findViewById<ImageButton>(R.id.btnBubble).setOnClickListener {
            toggleOverlay()
        }
        makeDraggable(view, lp)
        windowManager.addView(view, lp)
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; dragging = false; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (dx * dx + dy * dy > 25) dragging = true
                    if (dragging) { params.x = ix + dx; params.y = iy + dy
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {} }
                    dragging
                }
                MotionEvent.ACTION_UP -> dragging
                else -> false
            }
        }
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun toggleOverlay() {
        if (isOverlayVisible) hideOverlay() else showOverlay()
    }

    private fun showOverlay() {
        try {
            val mgr = translationManager ?: return
            val ov = OverlayView(this, mgr) {
                Log.d(TAG, "Overlay refresh requested from service")
            }
            overlayView = ov
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(ov, lp)
            isOverlayVisible = true
        } catch (e: Exception) {
            Log.e(TAG, "showOverlay failed", e)
        }
    }

    private fun hideOverlay() {
        try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        isOverlayVisible = false
    }

    fun updateBitmap(bitmap: Bitmap) {
        try {
            overlayView?.setSourceBitmap(bitmap)
            overlayView?.startTranslation()
        } catch (e: Exception) {
            Log.e(TAG, "updateBitmap failed", e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun detachViews() {
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubbleView = null
        hideOverlay()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showError(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }
}
