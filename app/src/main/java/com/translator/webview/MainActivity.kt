package com.translator.webview

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.translator.webview.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var translationManager: TranslationManager

    private var overlayView: OverlayView? = null
    private var isOverlayActive = false
    private var overlayInitialized = false

    // ── Permission launcher ───────────────────────────────────────────────────

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User returned from Settings — recheck and update UI
        if (canDrawOverlays()) {
            ensureOverlayInitialized()
            Toast.makeText(this, getString(R.string.perm_granted), Toast.LENGTH_SHORT).show()
        }
        updatePermissionUI()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        translationManager = TranslationManager(this)

        setupWebView()
        setupButtons()
        updatePermissionUI()

        // Show rationale dialog on first launch if permission missing
        if (!canDrawOverlays()) {
            showPermissionRationaleDialog()
        } else {
            ensureOverlayInitialized()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
        if (canDrawOverlays() && !overlayInitialized) {
            ensureOverlayInitialized()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        translationManager.close()
        binding.webView.destroy()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack()
        else super.onBackPressed()
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.perm_overlay_title))
            .setMessage(getString(R.string.perm_overlay_message))
            .setIcon(android.R.drawable.ic_dialog_info)
            .setPositiveButton(getString(R.string.btn_open_settings)) { _, _ ->
                openOverlaySettings()
            }
            .setNegativeButton(getString(R.string.btn_skip)) { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.perm_skipped_hint), Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun updatePermissionUI() {
        val granted = canDrawOverlays()
        binding.permissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
        binding.fabTranslate.isEnabled = granted
        binding.fabTranslate.alpha      = if (granted) 1.0f else 0.4f
        if (granted) {
            binding.fabTranslate.text = getString(R.string.btn_start_translator)
        }
    }

    // ── Overlay initialization ────────────────────────────────────────────────

    private fun ensureOverlayInitialized() {
        if (overlayInitialized) return
        val view = OverlayView(this, translationManager) {
            captureAndTranslate(silent = false)
        }
        view.visibility = View.GONE
        binding.overlayContainer.addView(view)
        overlayView = view
        overlayInitialized = true
    }

    // ── Button wiring ─────────────────────────────────────────────────────────

    private fun setupButtons() {
        // "Grant Permission" button inside the banner
        binding.btnGrantPermission.setOnClickListener {
            showPermissionRationaleDialog()
        }

        // FAB — acts as "Start / Stop Translator"
        binding.fabTranslate.setOnClickListener {
            if (!canDrawOverlays()) {
                showPermissionRationaleDialog()
                return@setOnClickListener
            }
            ensureOverlayInitialized()
            toggleOverlay()
        }
    }

    // ── WebView ───────────────────────────────────────────────────────────────

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

    // ── Overlay toggle ────────────────────────────────────────────────────────

    private fun toggleOverlay() {
        if (!overlayInitialized) return
        if (!isOverlayActive) {
            captureAndTranslate(silent = false)
        } else {
            overlayView?.visibility = View.GONE
            binding.fabTranslate.text = getString(R.string.btn_start_translator)
            isOverlayActive = false
        }
    }

    // ── Capture + Translate ───────────────────────────────────────────────────

    private fun captureAndTranslate(silent: Boolean) {
        if (!silent) binding.fabTranslate.isEnabled = false

        captureWebView { bitmap ->
            binding.fabTranslate.isEnabled = canDrawOverlays()
            if (bitmap != null) {
                overlayView?.setSourceBitmap(bitmap)
                overlayView?.startTranslation()
                overlayView?.visibility = View.VISIBLE
                binding.fabTranslate.text = getString(R.string.fab_close)
                isOverlayActive = true
            } else {
                Toast.makeText(this, getString(R.string.capture_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                        location[0], location[1],
                        location[0] + webView.width, location[1] + webView.height
                    ),
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) callback(bitmap)
                        else fallbackCapture(webView, callback)
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
}
