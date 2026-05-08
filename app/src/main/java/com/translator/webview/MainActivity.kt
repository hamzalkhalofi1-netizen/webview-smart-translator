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
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.translator.webview.databinding.ActivityMainBinding

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var translationManager: TranslationManager? = null

    private var overlayView: OverlayView? = null
    private var isOverlayActive  = false
    private var overlayInitialized = false

    // ── Permission launcher ───────────────────────────────────────────────────

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (canDrawOverlays()) {
            ensureOverlayInitialized()
            Toast.makeText(this, getString(R.string.perm_granted), Toast.LENGTH_SHORT).show()
        }
        updatePermissionUI()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: layout inflation failed — ${e.message}", e)
            finish()
            return
        }

        try {
            translationManager = TranslationManager(this)
        } catch (e: Exception) {
            Log.e(TAG, "TranslationManager init failed — ${e.message}", e)
            // App can still run — translate button just won't work
        }

        setupWebView()
        setupBackPress()
        setupButtons()
        updatePermissionUI()

        if (!canDrawOverlays()) {
            // Show rationale dialog; don't block the launch
            binding.root.post { showPermissionRationaleDialog() }
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
        try { translationManager?.close() } catch (e: Exception) { /* ignore */ }
        try { binding.webView.destroy()   } catch (e: Exception) { /* ignore */ }
    }

    // ── Back press (API-34 safe) ──────────────────────────────────────────────

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun showPermissionRationaleDialog() {
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.perm_overlay_title))
                .setMessage(getString(R.string.perm_overlay_message))
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton(getString(R.string.btn_open_settings)) { _, _ -> openOverlaySettings() }
                .setNegativeButton(getString(R.string.btn_skip)) { dialog, _ ->
                    dialog.dismiss()
                    Toast.makeText(this, getString(R.string.perm_skipped_hint), Toast.LENGTH_LONG).show()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show permission dialog: ${e.message}", e)
        }
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open overlay settings: ${e.message}", e)
            Toast.makeText(this, "لا يمكن فتح الإعدادات. يرجى المنح يدوياً.", Toast.LENGTH_LONG).show()
        }
    }

    private fun updatePermissionUI() {
        val granted = canDrawOverlays()
        binding.permissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
        binding.fabTranslate.isEnabled = granted
        binding.fabTranslate.alpha     = if (granted) 1.0f else 0.4f
        if (granted && !isOverlayActive) {
            binding.fabTranslate.text = getString(R.string.btn_start_translator)
        }
    }

    // ── Overlay initialization ────────────────────────────────────────────────

    private fun ensureOverlayInitialized() {
        if (overlayInitialized) return
        val mgr = translationManager ?: run {
            Log.w(TAG, "Cannot init overlay — TranslationManager is null")
            return
        }
        try {
            val view = OverlayView(this, mgr) {
                captureAndTranslate(silent = false)
            }
            view.visibility = View.GONE
            binding.overlayContainer.addView(view)
            overlayView = view
            overlayInitialized = true
            Log.d(TAG, "OverlayView initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OverlayView: ${e.message}", e)
        }
    }

    // ── Button wiring ─────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnGrantPermission.setOnClickListener {
            showPermissionRationaleDialog()
        }

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
        try {
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
                        try { binding.progressBar.visibility = View.VISIBLE } catch (_: Exception) {}
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        try {
                            binding.progressBar.visibility = View.GONE
                            if (isOverlayActive) {
                                binding.root.postDelayed({ captureAndTranslate(silent = true) }, 600)
                            }
                        } catch (_: Exception) {}
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        try { binding.progressBar.progress = newProgress } catch (_: Exception) {}
                    }
                }

                loadUrl("https://www.google.com")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebView setup failed: ${e.message}", e)
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

    private fun captureWebView(callback: (Bitmap?) -> Unit) {
        val webView = binding.webView
        if (webView.width == 0 || webView.height == 0) { callback(null); return }

        val bitmap = try {
            Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture bitmap: ${e.message}")
            callback(null)
            return
        }

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
                Log.w(TAG, "PixelCopy failed, using fallback: ${e.message}")
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
            Log.e(TAG, "Fallback capture failed: ${e.message}")
            callback(null)
        }
    }
}
