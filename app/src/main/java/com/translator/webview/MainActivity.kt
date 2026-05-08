package com.translator.webview

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.translator.webview.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var overlayView: OverlayView? = null
    private var translationManager: TranslationManager? = null
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isOverlayActive = false

    private var translationService: TranslationService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            try {
                translationService = (binder as TranslationService.LocalBinder).getService()
                isServiceBound = true
                updateServiceButton()
            } catch (e: Exception) {
                Log.e(TAG, "Service connection error", e)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            translationService = null
            isServiceBound = false
            updateServiceButton()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY_PERMISSION = 1001
    }

    // ── onCreate ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install crash logger first — before anything else
        installGlobalExceptionHandler()
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            NotificationHelper.createChannel(this)
            checkOverlayPermissionThenInit()
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: onCreate crashed", e)
            showFatalError(e)
        }
    }

    private fun showFatalError(e: Exception) {
        try {
            // Last-resort: show an alert so the user can report the exact message
            AlertDialog.Builder(this)
                .setTitle("خطأ في التشغيل")
                .setMessage("حدث خطأ غير متوقع:\n${e.javaClass.simpleName}: ${e.message}\n\nيرجى إبلاغ المطور بهذه الرسالة.")
                .setPositiveButton("حسناً", null)
                .show()
        } catch (ignored: Exception) {
            // If even AlertDialog fails, Toast as absolute last resort
            try {
                Toast.makeText(this, "Fatal: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    // ── Crash logger ──────────────────────────────────────────────────────────

    private fun installGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT on '${thread.name}': ${throwable.javaClass.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // ── Permission ────────────────────────────────────────────────────────────

    private fun checkOverlayPermissionThenInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.overlay_permission_title))
                .setMessage(getString(R.string.overlay_permission_message))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.overlay_permission_go_to_settings)) { _, _ ->
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        REQUEST_OVERLAY_PERMISSION
                    )
                }
                .setNegativeButton(getString(R.string.overlay_permission_skip)) { _, _ -> initApp() }
                .show()
        } else {
            initApp()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
            if (!granted) Toast.makeText(this, getString(R.string.overlay_permission_denied_warning), Toast.LENGTH_LONG).show()
            initApp()
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private fun initApp() {
        try {
            setupWebView()
            setupFab()
            setupServiceButton()
            initTranslationManagerAsync()
        } catch (e: Exception) {
            Log.e(TAG, "initApp failed", e)
            showFatalError(e)
        }
    }

    private fun initTranslationManagerAsync() {
        activityScope.launch(Dispatchers.IO) {
            try {
                val mgr = TranslationManager(this@MainActivity)
                launch(Dispatchers.Main) {
                    try {
                        translationManager = mgr
                        setupOverlay()
                        Log.d(TAG, "TranslationManager ready")
                    } catch (e: Exception) {
                        Log.e(TAG, "setupOverlay failed", e)
                        Toast.makeText(this@MainActivity, "خطأ في الإعداد: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TranslationManager init failed", e)
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "${getString(R.string.model_downloading)}\n${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── WebView ───────────────────────────────────────────────────────────────

    private fun setupWebView() {
        with(binding.webView) {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    try { binding.progressBar.visibility = View.VISIBLE } catch (_: Exception) {}
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    try {
                        binding.progressBar.visibility = View.GONE
                        if (isOverlayActive) binding.root.postDelayed({ captureAndTranslate(silent = true) }, 500)
                        if (isServiceBound) binding.root.postDelayed({ captureAndSendToService() }, 600)
                    } catch (e: Exception) { Log.w(TAG, "onPageFinished error", e) }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    try { binding.progressBar.progress = newProgress } catch (_: Exception) {}
                }
            }
            loadUrl("https://www.google.com")
        }
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun setupOverlay() {
        try {
            val mgr = translationManager ?: return
            val ov = OverlayView(this, mgr) { captureAndTranslate(silent = false) }
            ov.visibility = View.GONE
            binding.overlayContainer.addView(ov)
            overlayView = ov
        } catch (e: Exception) {
            Log.e(TAG, "setupOverlay error", e)
        }
    }

    // ── FAB ───────────────────────────────────────────────────────────────────

    private fun setupFab() {
        binding.fabTranslate.setOnClickListener {
            try {
                if (translationManager == null) {
                    Toast.makeText(this, getString(R.string.model_downloading), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                toggleOverlay()
            } catch (e: Exception) {
                Log.e(TAG, "FAB click error", e)
                Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleOverlay() {
        if (!isOverlayActive) captureAndTranslate(silent = false)
        else {
            overlayView?.visibility = View.GONE
            binding.fabTranslate.text = getString(R.string.fab_translate)
            isOverlayActive = false
        }
    }

    // ── Service button ────────────────────────────────────────────────────────

    private fun setupServiceButton() {
        binding.btnService.setOnClickListener {
            try {
                if (isServiceBound) stopTranslationService() else startTranslationService()
            } catch (e: Exception) {
                Log.e(TAG, "Service button error", e)
                Toast.makeText(this, "خطأ في الخدمة: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        updateServiceButton()
    }

    private fun startTranslationService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.overlay_permission_denied_warning), Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, TranslationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopTranslationService() {
        if (isServiceBound) { try { unbindService(serviceConnection) } catch (_: Exception) {} ; isServiceBound = false; translationService = null }
        stopService(Intent(this, TranslationService::class.java))
        updateServiceButton()
    }

    private fun updateServiceButton() {
        try {
            binding.btnService.text = if (isServiceBound) getString(R.string.btn_stop_service) else getString(R.string.btn_start_service)
        } catch (_: Exception) {}
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    private fun captureAndTranslate(silent: Boolean) {
        try {
            val ov = overlayView ?: return
            if (!silent) binding.fabTranslate.isEnabled = false
            captureWebView { bitmap ->
                try {
                    binding.fabTranslate.isEnabled = true
                    if (bitmap != null) {
                        ov.setSourceBitmap(bitmap)
                        ov.startTranslation()
                        ov.visibility = View.VISIBLE
                        binding.fabTranslate.text = getString(R.string.fab_close)
                        isOverlayActive = true
                    } else {
                        Toast.makeText(this, getString(R.string.capture_failed), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "captureAndTranslate callback error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureAndTranslate error", e)
        }
    }

    private fun captureAndSendToService() {
        try { captureWebView { bitmap -> if (bitmap != null) translationService?.updateBitmap(bitmap) } }
        catch (e: Exception) { Log.w(TAG, "captureAndSendToService error", e) }
    }

    fun captureWebView(callback: (Bitmap?) -> Unit) {
        try {
            val webView = binding.webView
            if (webView.width == 0 || webView.height == 0) { callback(null); return }
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val loc = IntArray(2); webView.getLocationInWindow(loc)
                try {
                    PixelCopy.request(window,
                        android.graphics.Rect(loc[0], loc[1], loc[0] + webView.width, loc[1] + webView.height),
                        bitmap,
                        { result -> if (result == PixelCopy.SUCCESS) callback(bitmap) else fallbackCapture(webView, callback) },
                        Handler(Looper.getMainLooper()))
                } catch (e: Exception) { fallbackCapture(webView, callback) }
            } else { fallbackCapture(webView, callback) }
        } catch (e: Exception) { Log.e(TAG, "captureWebView error", e); callback(null) }
    }

    private fun fallbackCapture(webView: WebView, callback: (Bitmap?) -> Unit) {
        try {
            val bmp = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            webView.draw(Canvas(bmp)); callback(bmp)
        } catch (e: Exception) { Log.e(TAG, "fallbackCapture error", e); callback(null) }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        try {
            if (!isServiceBound) bindService(Intent(this, TranslationService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try { if (isServiceBound) { unbindService(serviceConnection); isServiceBound = false } } catch (_: Exception) {}
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { translationManager?.close() } catch (_: Exception) {}
        try { binding.webView.destroy() } catch (_: Exception) {}
    }
}
