package com.translator.webview

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.URLUtil
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.translator.webview.databinding.ActivityDownloaderBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

class DownloaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloaderBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val scraper = ScrapingEngine()
    private lateinit var gemini: GeminiTranslator

    private var currentMetadata: MangaMetadata? = null
    private var downloadResults = mutableListOf<DownloadResult>()

    private lateinit var chapterAdapter: ChapterAdapter

    companion object {
        private const val TAG = "DownloaderActivity"
        const val EXTRA_CHAPTER_CONTENT = "extra_chapter_content"
        const val EXTRA_CHAPTER_IMAGES  = "extra_chapter_images"
        const val EXTRA_CHAPTER_TITLE   = "extra_chapter_title"
        const val EXTRA_IS_NOVEL        = "extra_is_novel"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gemini = GeminiTranslator(BuildConfig.GEMINI_API_KEY)

        setupToolbar()
        setupRecyclerView()
        setupButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupRecyclerView() {
        chapterAdapter = ChapterAdapter { chapter ->
            openChapterInReader(chapter)
        }
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
    }

    // ── Fetch ─────────────────────────────────────────────────────────────────

    private fun fetchSite(url: String) {
        showProgress(true, getString(R.string.fetching_metadata), 0)
        binding.cardMetadata.visibility = View.GONE
        binding.layoutActions.visibility = View.GONE
        binding.cardGeminiBadge.visibility = View.GONE
        chapterAdapter.submitList(emptyList())

        scope.launch {
            try {
                // 1. Scrape metadata
                val meta = withContext(Dispatchers.IO) {
                    scraper.fetchMetadata(url) { progress, msg ->
                        launch(Dispatchers.Main) {
                            updateProgress(progress / 2, msg)
                        }
                    }
                }

                updateProgress(50, getString(R.string.translating_metadata))

                // 2. Translate metadata with Gemini AI
                val (tTitle, tGenre, tDesc) = withContext(Dispatchers.IO) {
                    gemini.translateMetadata(
                        meta.title, meta.genre, meta.description,
                        targetLanguage = "Arabic"
                    )
                }

                updateProgress(100, "Done!")

                // 3. Update UI
                currentMetadata = meta.copy(
                    title = tTitle,
                    genre = tGenre,
                    description = tDesc
                )

                showMetadata(currentMetadata!!)
                showProgress(false)
                binding.cardGeminiBadge.visibility = View.VISIBLE

            } catch (e: Exception) {
                Log.e(TAG, "Fetch failed", e)
                showProgress(false)
                Toast.makeText(this@DownloaderActivity,
                    "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMetadata(meta: MangaMetadata) {
        binding.tvTitle.text       = meta.title
        binding.tvGenre.text       = meta.genre
        binding.tvDescription.text = meta.description
        binding.chipChapterCount.text = "${meta.chapters.size} chapters found"
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

        scope.launch {
            try {
                meta.chapters.forEachIndexed { index, chapter ->
                    val chapProgress = (index.toFloat() / total * 100).toInt()
                    updateProgress(chapProgress,
                        getString(R.string.chapter_progress, index + 1, total))

                    val result = withContext(Dispatchers.IO) {
                        scraper.downloadChapter(chapter) { p ->
                            launch(Dispatchers.Main) {
                                val overall = chapProgress + (p / total)
                                updateProgress(overall.coerceIn(0, 99), "")
                            }
                        }
                    }

                    // Translate novel content if needed
                    if (result.isNovel && result.textContent.isNotBlank()) {
                        updateProgress(chapProgress, "Gemini AI translating chapter ${index + 1}…")
                        val translatedContent = withContext(Dispatchers.IO) {
                            gemini.translateChapterContent(result.textContent, "Arabic")
                        }
                        downloadResults.add(result.copy(textContent = translatedContent))
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
            // Download on demand
            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) {
                        scraper.downloadChapter(chapter) {}
                    }
                    openReaderWithResult(r)
                } catch (e: Exception) {
                    Toast.makeText(this@DownloaderActivity,
                        "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openReaderWithResult(result: DownloadResult) {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(EXTRA_CHAPTER_TITLE, result.chapterInfo.title)
            putExtra(EXTRA_IS_NOVEL, result.isNovel)
            putExtra(EXTRA_CHAPTER_CONTENT, result.textContent)
            if (result.imageUrls.isNotEmpty()) {
                putStringArrayListExtra(EXTRA_CHAPTER_IMAGES,
                    ArrayList(result.imageUrls.take(50)))
            }
        }
        startActivity(intent)
    }

    // ── Progress helpers ──────────────────────────────────────────────────────

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

    // ── Chapter RecyclerView Adapter ──────────────────────────────────────────

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
            val tintColor = if (isDownloaded)
                android.graphics.Color.parseColor("#4CAF50")
            else
                android.graphics.Color.parseColor("#38BDF8")
            holder.ivStatus.setColorFilter(tintColor)
            holder.itemView.setOnClickListener { onClick(chapter) }
        }

        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvNum: TextView   = view.findViewById(R.id.tvChapterNum)
            val tvTitle: TextView = view.findViewById(R.id.tvChapterTitle)
            val ivStatus: ImageView = view.findViewById(R.id.ivDownloadStatus)
        }
    }
}
