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
import android.view.MenuItem
import android.view.PixelCopy
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import com.google.android.material.navigation.NavigationView
import com.translator.webview.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

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
            } catch (e: Exception) { Log.e(TAG, "Service bind error", e) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            translationService = null
            isServiceBound = false
            updateServiceButton()
        }
    }

    companion object {
        private const val TAG = "YomuAI"
        private const val REQUEST_OVERLAY_PERMISSION = 1001
    }

    // ── Locale ────────────────────────────────────────────────────────────────

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrap(base))
    }

    // ── onCreate ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        installGlobalExceptionHandler()
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            NotificationHelper.createChannel(this)
            setupToolbarAndDrawer()
            checkOverlayPermissionThenInit()
        } catch (e: Exception) {
            Log.e(TAG, "FATAL onCreate", e)
            showFatalError(e)
        }
    }

    // ── Toolbar + Floating Drawer ─────────────────────────────────────────────

    private fun setupToolbarAndDrawer() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "YomuAI"

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.nav_open, R.string.nav_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        toggle.drawerArrowDrawable.color = getColor(R.color.primary)

        // Dark semi-transparent scrim for the floating drawer effect
        binding.drawerLayout.setScrimColor(0xCC000000.toInt())

        binding.navView.setNavigationItemSelectedListener(this)
        binding.navView.setCheckedItem(R.id.nav_home)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return when (item.itemId) {
            R.id.nav_home -> true
            R.id.nav_downloader -> {
                startActivity(Intent(this, DownloaderActivity::class.java))
                true
            }
            R.id.nav_reader -> {
                startActivity(Intent(this, ReaderActivity::class.java))
                true
            }
            R.id.nav_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            R.id.nav_settings -> {
                showAppLanguageDialog()
                true
            }
            else -> false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            binding.webView.canGoBack() ->
                binding.webView.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ── App Language Dialog ───────────────────────────────────────────────────

    private fun showAppLanguageDialog() {
        val langs = arrayOf(
            getString(R.string.setting_language_en),
            getString(R.string.setting_language_ar)
        )
        val codes = arrayOf("en", "ar")
        val current = LocaleManager.getLanguage(this)
        val idx = codes.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setting_language))
            .setSingleChoiceItems(langs, idx) { dialog, which ->
                dialog.dismiss()
                val selected = codes[which]
                if (selected != current) {
                    LocaleManager.setLanguage(this, selected)
                    recreate()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── Error handling ────────────────────────────────────────────────────────

    private fun showFatalError(e: Exception) {
        try {
            AlertDialog.Builder(this)
                .setTitle("YomuAI — Startup Error")
                .setMessage("${e.javaClass.simpleName}: ${e.message}\n\nPlease contact support.")
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Exception) {
            Toast.makeText(this, "Fatal: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun installGlobalExceptionHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            Log.e(TAG, "UNCAUGHT on '${t.name}': ${ex.javaClass.name}: ${ex.message}", ex)
            default?.uncaughtException(t, ex)
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
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this))
                Toast.makeText(this, getString(R.string.overlay_permission_denied_warning), Toast.LENGTH_LONG).show()
            initApp()
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private fun initApp() {
        try {
            setupWebView()
            setupFab()
            setupServiceButton()
            setupLanguageButton()
            initTranslationManagerAsync()
        } catch (e: Exception) {
            Log.e(TAG, "initApp error", e)
            showFatalError(e)
        }
    }

    private fun initTranslationManagerAsync() {
        activityScope.launch(Dispatchers.IO) {
            try {
                val mgr = TranslationManager(this@MainActivity)
                mgr.onModelStatusChanged = { ready, msg ->
                    if (!ready) Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
                launch(Dispatchers.Main) {
                    translationManager = mgr
                    setupOverlay()
                }
            } catch (e: Exception) {
                Log.e(TAG, "TranslationManager init failed", e)
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity,
                        "${getString(R.string.model_downloading)}: ${e.message}", Toast.LENGTH_LONG).show()
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
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    try { binding.progressBar.visibility = View.VISIBLE } catch (_: Exception) {}
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    try {
                        binding.progressBar.visibility = View.GONE
                        if (isOverlayActive) binding.root.postDelayed({ captureAndTranslate(silent = true) }, 500)
                        if (isServiceBound)  binding.root.postDelayed({ captureAndSendToService() }, 600)
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
        } catch (e: Exception) { Log.e(TAG, "setupOverlay error", e) }
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
            } catch (e: Exception) { Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
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

    // ── Language ──────────────────────────────────────────────────────────────

    private fun setupLanguageButton() {
        try {
            binding.btnLanguage.setOnClickListener { showLanguageDialog() }
        } catch (e: Exception) { Log.w(TAG, "btnLanguage not found", e) }
    }

    fun showLanguageDialog() {
        val mgr = translationManager ?: run {
            Toast.makeText(this, getString(R.string.model_downloading), Toast.LENGTH_SHORT).show()
            return
        }
        val langs = mgr.supportedLanguages
        val currentCode = mgr.getTargetLanguage().code
        val currentIdx  = langs.indexOfFirst { it.code == currentCode }.coerceAtLeast(0)
        val labels = langs.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.language_picker_title))
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                val selected = langs[which]
                mgr.setTargetLanguage(selected.code)
                overlayView?.setTargetLanguage(selected.code)
                Toast.makeText(this,
                    "${getString(R.string.language_changed_to)} ${selected.displayName}",
                    Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── Service ───────────────────────────────────────────────────────────────

    private fun setupServiceButton() {
        try {
            binding.btnService.setOnClickListener {
                try { if (isServiceBound) stopTranslationService() else startTranslationService() }
                catch (e: Exception) { Toast.makeText(this, "Service error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
            updateServiceButton()
        } catch (e: Exception) { Log.w(TAG, "btnService not in layout", e) }
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
        if (isServiceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isServiceBound = false; translationService = null
        }
        stopService(Intent(this, TranslationService::class.java))
        updateServiceButton()
    }

    private fun updateServiceButton() {
        try {
            binding.btnService.text =
                if (isServiceBound) getString(R.string.btn_stop_service)
                else getString(R.string.btn_start_service)
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
                } catch (e: Exception) { Log.e(TAG, "translate callback error", e) }
            }
        } catch (e: Exception) { Log.e(TAG, "captureAndTranslate error", e) }
    }

    private fun captureAndSendToService() {
        try { captureWebView { bmp -> if (bmp != null) translationService?.updateBitmap(bmp) } }
        catch (e: Exception) { Log.w(TAG, "captureAndSendToService error", e) }
    }

    fun captureWebView(callback: (Bitmap?) -> Unit) {
        try {
            val wv = binding.webView
            if (wv.width == 0 || wv.height == 0) { callback(null); return }
            val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val loc = IntArray(2); wv.getLocationInWindow(loc)
                try {
                    PixelCopy.request(window,
                        android.graphics.Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height),
                        bmp,
                        { r -> if (r == PixelCopy.SUCCESS) callback(bmp) else fallbackCapture(wv, callback) },
                        Handler(Looper.getMainLooper()))
                } catch (_: Exception) { fallbackCapture(wv, callback) }
            } else { fallbackCapture(wv, callback) }
        } catch (e: Exception) { Log.e(TAG, "captureWebView error", e); callback(null) }
    }

    private fun fallbackCapture(wv: WebView, callback: (Bitmap?) -> Unit) {
        try {
            val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            wv.draw(Canvas(bmp)); callback(bmp)
        } catch (e: Exception) { Log.e(TAG, "fallbackCapture error", e); callback(null) }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        try {
            if (!isServiceBound)
                bindService(Intent(this, TranslationService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try { if (isServiceBound) { unbindService(serviceConnection); isServiceBound = false } } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { translationManager?.close() } catch (_: Exception) {}
        try { binding.webView.destroy() } catch (_: Exception) {}
    }
}
