package com.translator.webview

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.translator.webview.databinding.ActivityReaderBinding

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding

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
                javaScriptEnabled     = true
                domStorageEnabled     = true
                loadWithOverviewMode  = true
                useWideViewPort       = true
                builtInZoomControls   = true
                displayZoomControls   = false
                setSupportZoom(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    binding.layoutLoading.visibility = View.GONE
                }
            }

            val html = if (isNovel) buildNovelHtml(content) else buildMangaHtml(imageUrls, content)
            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }

    // ── HTML builders ──────────────────────────────────────────────────────────

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
    background: #0F172A;
    color: #E2E8F0;
    font-family: Georgia, 'Times New Roman', serif;
    font-size: 17px;
    line-height: 1.9;
    padding: 20px 18px 60px;
    margin: 0;
    direction: auto;
  }
  p {
    margin: 0 0 1.4em;
    text-align: justify;
  }
  ::-webkit-scrollbar { width: 4px; }
  ::-webkit-scrollbar-track { background: #1E293B; }
  ::-webkit-scrollbar-thumb { background: #38BDF8; border-radius: 2px; }
</style>
</head>
<body>
$paragraphs
</body>
</html>"""
    }

    private fun buildMangaHtml(imageUrls: List<String>, fallbackText: String): String {
        val imgs = if (imageUrls.isNotEmpty()) {
            imageUrls.joinToString("") { url ->
                """<div class="page">
  <img src="$url" alt="Manga page" loading="lazy"
       onerror="this.style.display='none'">
</div>"""
            }
        } else {
            "<p style='color:#94A3B8;text-align:center;padding:40px 20px;'>No images found — $fallbackText</p>"
        }

        return """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    background: #0F172A;
    display: flex;
    flex-direction: column;
    align-items: center;
  }
  .page {
    width: 100%;
    max-width: 100vw;
    display: flex;
    justify-content: center;
    margin-bottom: 2px;
  }
  .page img {
    width: 100%;
    height: auto;
    display: block;
    object-fit: contain;
  }
  ::-webkit-scrollbar { width: 4px; }
  ::-webkit-scrollbar-track { background: #1E293B; }
  ::-webkit-scrollbar-thumb { background: #38BDF8; border-radius: 2px; }
</style>
</head>
<body>
$imgs
</body>
</html>"""
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

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
