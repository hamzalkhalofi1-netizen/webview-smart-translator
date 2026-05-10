package com.translator.webview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.translator.webview.databinding.ActivityDownloaderBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloaderBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val scraper = ScrapingEngine()
    private lateinit var gemini: GeminiTranslator

    private var currentMetadata: MangaMetadata? = null
    private var downloadResults = mutableListOf<DownloadResult>()
    private lateinit var chapterAdapter: ChapterAdapter

    private val prefs by lazy { getSharedPreferences("yomuai_prefs", Context.MODE_PRIVATE) }

    companion object {
        private const val TAG = "DownloaderActivity"
        private const val KEY_DOMAINS = "saved_domains"
        const val EXTRA_CHAPTER_CONTENT = "extra_chapter_content"
        const val EXTRA_CHAPTER_IMAGES  = "extra_chapter_images"
        const val EXTRA_CHAPTER_TITLE   = "extra_chapter_title"
        const val EXTRA_IS_NOVEL        = "extra_is_novel"
        const val EXTRA_PREFILL_URL     = "extra_prefill_url"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gemini = GeminiTranslator(BuildConfig.GEMINI_API_KEY)

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        loadSavedDomains()

        // Pre-fill URL if launched from MangaBrowseActivity
        val prefillUrl = intent.getStringExtra(EXTRA_PREFILL_URL)
        if (!prefillUrl.isNullOrBlank()) {
            binding.etSiteUrl.setText(prefillUrl)
            fetchSite(prefillUrl)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupRecyclerView() {
        chapterAdapter = ChapterAdapter { chapter -> openChapterInReader(chapter) }
        binding.rvChapters.apply {
            layoutManager = LinearLayoutManager(this@DownloaderActivity)
            adapter = chapterAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupButtons() {
        binding.btnFetchSite.setOnClickListener {
            val url = binding.etSiteUrl.text.toString().trim()
            if (!URLUtil.isValidUrl(url)) {
                Toast.makeText(this, getString(R.string.invalid_url), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchSite(url)
        }

        binding.btnDownloadAll.setOnClickListener {
            val meta = currentMetadata ?: return@setOnClickListener
            downloadAllChapters(meta)
        }

        binding.btnOpenReader.setOnClickListener {
            val result = downloadResults.firstOrNull()
            if (result != null) openReaderWithResult(result)
            else Toast.makeText(this, "Download chapters first", Toast.LENGTH_SHORT).show()
        }

        // Domain importer — Add button
        binding.btnAddDomain.setOnClickListener {
            val raw = binding.etDomain.text.toString().trim()
                .removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (raw.isBlank()) return@setOnClickListener
            saveDomain(raw)
            binding.etDomain.setText("")
        }
    }

    // ── Domain Importer ───────────────────────────────────────────────────────

    private fun getSavedDomains(): MutableList<String> {
        val stored = prefs.getString(KEY_DOMAINS, "") ?: ""
        return if (stored.isBlank()) mutableListOf()
        else stored.split(",").filter { it.isNotBlank() }.toMutableList()
    }

    private fun saveDomain(domain: String) {
        val list = getSavedDomains()
        if (!list.contains(domain)) {
            list.add(0, domain)
            prefs.edit().putString(KEY_DOMAINS, list.joinToString(",")).apply()
            loadSavedDomains()
        }
    }

    private fun removeDomain(domain: String) {
        val list = getSavedDomains()
        list.remove(domain)
        prefs.edit().putString(KEY_DOMAINS, list.joinToString(",")).apply()
        loadSavedDomains()
    }

    private fun loadSavedDomains() {
        binding.chipGroupDomains.removeAllViews()
        val domains = getSavedDomains()

        if (domains.isEmpty()) {
            binding.cardDomainImporter.visibility = View.GONE
            return
        }

        binding.cardDomainImporter.visibility = View.VISIBLE
        domains.forEach { domain ->
            val chip = Chip(this).apply {
                text = domain
                isCloseIconVisible = true
                isClickable = true
                isFocusable = true
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#1CFFFFFF")
                )
                setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#444444")
                )
                chipStrokeWidth = resources.getDimension(R.dimen.chip_stroke_width)
                closeIconTint = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#888888")
                )

                setOnClickListener {
                    binding.etSiteUrl.setText("https://$domain/")
                    binding.etSiteUrl.setSelection(binding.etSiteUrl.text?.length ?: 0)
                }
                setOnCloseIconClickListener {
                    removeDomain(domain)
                }
            }
            binding.chipGroupDomains.addView(chip)
        }
    }

    // ── Fetch ─────────────────────────────────────────────────────────────────

    private fun fetchSite(url: String) {
        showProgress(true, getString(R.string.fetching_metadata), 0)
        binding.cardMetadata.visibility = View.GONE
        binding.layoutActions.visibility = View.GONE
        binding.cardGeminiBadge.visibility = View.GONE
        chapterAdapter.submitList(emptyList())

        // Auto-save the domain
        try {
            val host = android.net.Uri.parse(url).host
            if (!host.isNullOrBlank()) saveDomain(host)
        } catch (_: Exception) {}

        scope.launch {
            try {
                val meta = withContext(Dispatchers.IO) {
                    scraper.fetchMetadata(url) { progress, msg ->
                        launch(Dispatchers.Main) { updateProgress(progress / 2, msg) }
                    }
                }

                updateProgress(50, getString(R.string.translating_metadata))

                val targetLang = resolveTargetLanguage()
                val (tTitle, tGenre, tDesc) = withContext(Dispatchers.IO) {
                    gemini.translateMetadata(meta.title, meta.genre, meta.description,
                        targetLanguage = targetLang)
                }

                updateProgress(100, "Done!")

                currentMetadata = meta.copy(title = tTitle, genre = tGenre, description = tDesc)
                showMetadata(currentMetadata!!)
                showProgress(false)
                binding.cardGeminiBadge.visibility = View.VISIBLE

            } catch (e: Exception) {
                Log.e(TAG, "Fetch failed", e)
                showProgress(false)
                Toast.makeText(this@DownloaderActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resolveTargetLanguage(): String {
        val langCode = prefs.getString("translate_lang", "ar") ?: "ar"
        return when (langCode) {
            "ar" -> "Arabic"
            "fr" -> "French"
            "es" -> "Spanish"
            "ja" -> "Japanese"
            "tr" -> "Turkish"
            "id" -> "Indonesian"
            else -> "English"
        }
    }

    private fun showMetadata(meta: MangaMetadata) {
        binding.tvTitle.text       = meta.title
        binding.tvGenre.text       = meta.genre
        binding.tvDescription.text = meta.description
        binding.chipChapterCount.text = "${meta.chapters.size} ${getString(R.string.label_chapters)}"
        binding.cardMetadata.visibility = View.VISIBLE
        binding.layoutActions.visibility = View.VISIBLE

        if (meta.chapters.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_chapters_found), Toast.LENGTH_SHORT).show()
        } else {
            chapterAdapter.submitList(meta.chapters)
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun downloadAllChapters(meta: MangaMetadata) {
        if (meta.chapters.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_chapters_found), Toast.LENGTH_SHORT).show()
            return
        }

        downloadResults.clear()
        val total = meta.chapters.size
        showProgress(true, getString(R.string.downloading_chapters), 0)
        binding.layoutActions.visibility = View.GONE
        val targetLang = resolveTargetLanguage()

        scope.launch {
            try {
                meta.chapters.forEachIndexed { index, chapter ->
                    val chapProgress = (index.toFloat() / total * 100).toInt()
                    updateProgress(chapProgress, getString(R.string.chapter_progress, index + 1, total))

                    val result = withContext(Dispatchers.IO) {
                        scraper.downloadChapter(chapter) { p ->
                            launch(Dispatchers.Main) {
                                updateProgress((chapProgress + p / total).coerceIn(0, 99), "")
                            }
                        }
                    }

                    if (result.isNovel && result.textContent.isNotBlank()) {
                        updateProgress(chapProgress, "${getString(R.string.gemini_translating)} ${index + 1}…")
                        val translated = withContext(Dispatchers.IO) {
                            gemini.translateChapterContent(result.textContent, targetLang)
                        }
                        downloadResults.add(result.copy(textContent = translated))
                    } else {
                        downloadResults.add(result)
                    }

                    chapterAdapter.markDownloaded(chapter.number)
                }

                updateProgress(100, getString(R.string.download_complete))
                showProgress(false)
                binding.layoutActions.visibility = View.VISIBLE
                Toast.makeText(this@DownloaderActivity,
                    getString(R.string.download_complete), Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                showProgress(false)
                binding.layoutActions.visibility = View.VISIBLE
                Toast.makeText(this@DownloaderActivity,
                    "Download error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openChapterInReader(chapter: ChapterInfo) {
        val result = downloadResults.find { it.chapterInfo.number == chapter.number }
        if (result != null) {
            openReaderWithResult(result)
        } else {
            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { scraper.downloadChapter(chapter) {} }
                    openReaderWithResult(r)
                } catch (e: Exception) {
                    Toast.makeText(this@DownloaderActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openReaderWithResult(result: DownloadResult) {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(EXTRA_CHAPTER_TITLE, result.chapterInfo.title)
            putExtra(EXTRA_IS_NOVEL, result.isNovel)
            putExtra(EXTRA_CHAPTER_CONTENT, result.textContent)
            if (result.imageUrls.isNotEmpty())
                putStringArrayListExtra(EXTRA_CHAPTER_IMAGES, ArrayList(result.imageUrls.take(50)))
        }
        startActivity(intent)
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    private fun showProgress(visible: Boolean, message: String = "", progress: Int = 0) {
        binding.layoutProgress.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            binding.tvProgressStatus.text = message
            binding.progressBar.progress = progress
            binding.tvProgressPercent.text = "$progress%"
        }
    }

    private fun updateProgress(progress: Int, message: String) {
        if (binding.layoutProgress.visibility == View.VISIBLE) {
            if (message.isNotBlank()) binding.tvProgressStatus.text = message
            binding.progressBar.progress = progress
            binding.tvProgressPercent.text = "$progress%"
        }
    }

    // ── Chapter Adapter ───────────────────────────────────────────────────────

    inner class ChapterAdapter(
        private val onClick: (ChapterInfo) -> Unit
    ) : RecyclerView.Adapter<ChapterAdapter.VH>() {

        private val items = mutableListOf<ChapterInfo>()
        private val downloaded = mutableSetOf<Int>()

        fun submitList(list: List<ChapterInfo>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        fun markDownloaded(num: Int) {
            downloaded.add(num)
            val idx = items.indexOfFirst { it.number == num }
            if (idx >= 0) notifyItemChanged(idx)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chapter, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val chapter = items[position]
            holder.tvNum.text   = "${chapter.number}"
            holder.tvTitle.text = chapter.title
            val isDownloaded = downloaded.contains(chapter.number)
            holder.ivStatus.setImageResource(R.drawable.ic_download)
            val tint = if (isDownloaded)
                android.graphics.Color.parseColor("#FFFFFF")
            else
                android.graphics.Color.parseColor("#666666")
            holder.ivStatus.setColorFilter(tint)
            holder.itemView.setOnClickListener { onClick(chapter) }
        }

        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvNum: TextView     = view.findViewById(R.id.tvChapterNum)
            val tvTitle: TextView   = view.findViewById(R.id.tvChapterTitle)
            val ivStatus: ImageView = view.findViewById(R.id.ivDownloadStatus)
        }
    }
}
