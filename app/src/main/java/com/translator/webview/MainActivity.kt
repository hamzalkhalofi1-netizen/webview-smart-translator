package com.translator.webview

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.translator.webview.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var overlayView: OverlayView
    private lateinit var translationManager: TranslationManager

    private var isOverlayActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        translationManager = TranslationManager(this)

        setupWebView()
        setupOverlay()
        setupFab()
    }

    // ── WebView ──────────────────────────────────────────────────────────────

    private fun setupWebView() {
        with(binding.webView) {
            settings.apply {
                javaScriptEnabled         = true
                domStorageEnabled         = true
                loadWithOverviewMode      = true
                useWideViewPort           = true
                builtInZoomControls       = true
                displayZoomControls       = false
                setSupportZoom(true)
                cacheMode                 = WebSettings.LOAD_DEFAULT
                mixedContentMode          = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString           = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    binding.progressBar.visibility = View.VISIBLE
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    binding.progressBar.visibility = View.GONE
                    // Auto-refresh translation if overlay is active
                    if (isOverlayActive) {
                        binding.root.postDelayed({ captureAndTranslate(silent = true) }, 500)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.progressBar.progress = newProgress
                }
            }

            loadUrl("https://www.google.com")
        }
    }

    // ── Overlay ──────────────────────────────────────────────────────────────

    private fun setupOverlay() {
        overlayView = OverlayView(this, translationManager) {
            // Sync callback: user tapped "تحديث" inside the overlay
            captureAndTranslate(silent = false)
        }
        overlayView.visibility = View.GONE
        binding.overlayContainer.addView(overlayView)
    }

    // ── FAB ──────────────────────────────────────────────────────────────────

    private fun setupFab() {
        binding.fabTranslate.setOnClickListener {
            toggleOverlay()
        }
    }

    private fun toggleOverlay() {
        if (!isOverlayActive) {
            captureAndTranslate(silent = false)
        } else {
            overlayView.visibility = View.GONE
            binding.fabTranslate.text = getString(R.string.fab_translate)
            isOverlayActive = false
        }
    }

    // ── Capture + Translate ──────────────────────────────────────────────────

    private fun captureAndTranslate(silent: Boolean) {
        if (!silent) binding.fabTranslate.isEnabled = false

        captureWebView { bitmap ->
            binding.fabTranslate.isEnabled = true
            if (bitmap != null) {
                overlayView.setSourceBitmap(bitmap)
                overlayView.startTranslation()
                overlayView.visibility = View.VISIBLE
                binding.fabTranslate.text = getString(R.string.fab_close)
                isOverlayActive = true
            } else {
                Toast.makeText(this, "فشل التقاط الشاشة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Captures the WebView's currently rendered frame.
     * Uses PixelCopy (API 26+) for hardware-accelerated content;
     * falls back to WebView.draw() for software-rendered pages.
     */
    fun captureWebView(callback: (Bitmap?) -> Unit) {
        val webView = binding.webView
        if (webView.width == 0 || webView.height == 0) {
            callback(null)
            return
        }

        val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val location = IntArray(2)
            webView.getLocationInWindow(location)
            try {
                PixelCopy.request(
                    window,
                    android.graphics.Rect(
                        location[0],
                        location[1],
                        location[0] + webView.width,
                        location[1] + webView.height
                    ),
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            callback(bitmap)
                        } else {
                            fallbackCapture(webView, callback)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                fallbackCapture(webView, callback)
            }
        } else {
            fallbackCapture(webView, callback)
        }
    }

    private fun fallbackCapture(webView: WebView, callback: (Bitmap?) -> Unit) {
        try {
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            webView.draw(canvas)
            callback(bitmap)
        } catch (e: Exception) {
            callback(null)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        translationManager.close()
        binding.webView.destroy()
    }
}
