package com.translator.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.translator.webview.databinding.ActivityReaderBinding

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        loadContent()
        setupNavButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val chapterTitle = intent.getStringExtra(DownloaderActivity.EXTRA_CHAPTER_TITLE)
            ?: getString(R.string.reader_title)
        supportActionBar?.title = chapterTitle
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadContent() {
        val isNovel   = intent.getBooleanExtra(DownloaderActivity.EXTRA_IS_NOVEL, false)
        val content   = intent.getStringExtra(DownloaderActivity.EXTRA_CHAPTER_CONTENT) ?: ""
        val imageUrls = intent.getStringArrayListExtra(DownloaderActivity.EXTRA_CHAPTER_IMAGES) ?: arrayListOf()

        if (content.isBlank() && imageUrls.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            return
        }

        binding.layoutLoading.visibility = View.VISIBLE

        with(binding.readerWebView) {
            settings.apply {
                javaScriptEnabled    = true
                domStorageEnabled    = true
                loadWithOverviewMode = true
                useWideViewPort      = true
                builtInZoomControls  = true
                displayZoomControls  = false
                setSupportZoom(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    binding.layoutLoading.visibility = View.GONE
                }
            }
            val html = if (isNovel) buildNovelHtml(content) else buildMangaHtml(imageUrls)
            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }

    // ── Manga HTML — monochrome, full-width vertical scroll ───────────────

    private fun buildMangaHtml(imageUrls: List<String>): String {
        val imgs = if (imageUrls.isNotEmpty()) {
            imageUrls.joinToString("\n") { url ->
                """<div class="page"><img src="$url" alt="" loading="lazy" onerror="this.style.opacity='0.2'"></div>"""
            }
        } else {
            "<p class='empty'>No images found for this chapter.</p>"
        }

        return """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body {
    background: #000000;
    width: 100%;
    overflow-x: hidden;
  }
  body {
    display: flex;
    flex-direction: column;
    align-items: center;
  }
  .page {
    width: 100%;
    max-width: 100vw;
    display: block;
    margin-bottom: 1px;
    background: #000;
    text-align: center;
  }
  .page img {
    width: 100%;
    max-width: 100%;
    height: auto;
    display: block;
    image-rendering: -webkit-optimize-contrast;
  }
  .empty {
    color: #555;
    font-family: sans-serif;
    text-align: center;
    padding: 60px 24px;
    font-size: 14px;
  }
  /* Thin white divider between pages */
  .page + .page { border-top: 1px solid #111; }
  ::-webkit-scrollbar { width: 2px; }
  ::-webkit-scrollbar-track { background: #000; }
  ::-webkit-scrollbar-thumb { background: #444; }
</style>
</head>
<body>
$imgs
</body>
</html>"""
    }

    // ── Novel HTML — monochrome typography ────────────────────────────────

    private fun buildNovelHtml(text: String): String {
        val paragraphs = text.split("\n\n").joinToString("") { para ->
            "<p>${para.trim().replace("\n", "<br>")}</p>"
        }
        return """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  body {
    background: #000000;
    color: #DDDDDD;
    font-family: Georgia, 'Times New Roman', serif;
    font-size: 17px;
    line-height: 1.9;
    padding: 24px 18px 72px;
    margin: 0;
    direction: auto;
  }
  p {
    margin: 0 0 1.4em;
    text-align: justify;
  }
  /* Chapter divider lines */
  hr { border: none; border-top: 1px solid #222; margin: 2em 0; }
  ::-webkit-scrollbar { width: 3px; }
  ::-webkit-scrollbar-track { background: #000; }
  ::-webkit-scrollbar-thumb { background: #444; border-radius: 2px; }
</style>
</head>
<body>
$paragraphs
</body>
</html>"""
    }

    // ── Navigation FABs ───────────────────────────────────────────────────

    private fun setupNavButtons() {
        binding.fabPrevChapter.setOnClickListener {
            if (binding.readerWebView.canGoBack()) binding.readerWebView.goBack()
            else onBackPressedDispatcher.onBackPressed()
        }
        binding.fabNextChapter.setOnClickListener {
            if (binding.readerWebView.canGoForward()) binding.readerWebView.goForward()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { binding.readerWebView.destroy() } catch (_: Exception) {}
    }
}
