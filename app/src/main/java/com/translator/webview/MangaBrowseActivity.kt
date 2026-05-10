package com.translator.webview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.google.android.material.chip.Chip
import com.translator.webview.databinding.ActivityMangaBrowseBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaBrowseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMangaBrowseBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scraper = ScrapingEngine()
    private lateinit var gridAdapter: MangaGridAdapter

    private val quickDomains = listOf(
        "mangalek.com", "manganelo.com", "mangadex.org",
        "toonily.com",  "mangasee123.com"
    )

    companion object {
        private const val TAG = "MangaBrowse"
        const val EXTRA_MANGA_URL = "extra_manga_url"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMangaBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupGrid()
        setupDomainInput()
        setupQuickDomains()
        showEmpty(true)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupGrid() {
        gridAdapter = MangaGridAdapter { card ->
            val intent = Intent(this, DownloaderActivity::class.java).apply {
                putExtra(DownloaderActivity.EXTRA_PREFILL_URL, card.mangaUrl)
            }
            startActivity(intent)
        }
        binding.rvMangaGrid.apply {
            layoutManager = GridLayoutManager(this@MangaBrowseActivity, 2)
            adapter = gridAdapter
            isNestedScrollingEnabled = false
            setHasFixedSize(false)
        }
    }

    private fun setupDomainInput() {
        binding.btnBrowse.setOnClickListener {
            val domain = binding.etDomain.text.toString().trim()
            if (domain.isBlank()) {
                Toast.makeText(this, "Enter a domain first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchMangaList(domain)
        }
        binding.etDomain.setOnEditorActionListener { _, _, _ ->
            binding.btnBrowse.performClick(); true
        }
    }

    private fun setupQuickDomains() {
        quickDomains.forEach { domain ->
            val chip = Chip(this).apply {
                text = domain
                isClickable = true
                isFocusable = true
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#1CFFFFFF")
                )
                setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#444444")
                )
                chipStrokeWidth = 1f
                textSize = 11f
                setOnClickListener {
                    binding.etDomain.setText(domain)
                    fetchMangaList(domain)
                }
            }
            binding.llQuickDomains.addView(chip)
        }
    }

    private fun fetchMangaList(domain: String) {
        showEmpty(false)
        showProgress(true, "Connecting…", 0)
        binding.layoutResultHeader.visibility = View.GONE
        gridAdapter.submitList(emptyList())

        scope.launch {
            try {
                val cards = withContext(Dispatchers.IO) {
                    scraper.fetchMangaList(domain) { progress, msg ->
                        launch(Dispatchers.Main) { showProgress(true, msg, progress) }
                    }
                }

                showProgress(false)

                if (cards.isEmpty()) {
                    Toast.makeText(
                        this@MangaBrowseActivity,
                        "No manga found on $domain — try the Downloader with a specific URL",
                        Toast.LENGTH_LONG
                    ).show()
                    showEmpty(true)
                } else {
                    binding.layoutResultHeader.visibility = View.VISIBLE
                    binding.tvResultCount.text = "${cards.size} titles found on $domain"
                    gridAdapter.submitList(cards)
                }
            } catch (e: Exception) {
                showProgress(false)
                showEmpty(true)
                Toast.makeText(
                    this@MangaBrowseActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showProgress(visible: Boolean, msg: String = "", progress: Int = 0) {
        binding.layoutProgress.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            binding.tvProgressMsg.text = msg
            binding.progressBar.progress = progress
        }
    }

    private fun showEmpty(visible: Boolean) {
        binding.layoutEmpty.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ── Grid Adapter ───────────────────────────────────────────────────────

    inner class MangaGridAdapter(
        private val onClick: (MangaCard) -> Unit
    ) : RecyclerView.Adapter<MangaGridAdapter.VH>() {

        private val items = mutableListOf<MangaCard>()

        fun submitList(list: List<MangaCard>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_manga_grid, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val card = items[position]
            holder.tvTitle.text = card.title
            holder.tvGenre.text = card.genre.ifBlank { "Manga" }

            if (card.latestChapter.isNotBlank()) {
                holder.tvLatest.visibility = View.VISIBLE
                holder.tvLatest.text = card.latestChapter
            } else {
                holder.tvLatest.visibility = View.GONE
            }

            // Load cover with Coil
            if (card.coverUrl.isNotBlank()) {
                holder.ivCover.load(card.coverUrl) {
                    crossfade(true)
                    diskCachePolicy(CachePolicy.ENABLED)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    placeholder(R.drawable.ic_reader)
                    error(R.drawable.ic_reader)
                }
            } else {
                holder.ivCover.setImageResource(R.drawable.ic_reader)
            }

            holder.itemView.setOnClickListener { onClick(card) }
        }

        override fun getItemCount() = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivCover: ImageView  = view.findViewById(R.id.ivMangaCover)
            val tvTitle: TextView   = view.findViewById(R.id.tvMangaTitle)
            val tvGenre: TextView   = view.findViewById(R.id.tvMangaGenre)
            val tvLatest: TextView  = view.findViewById(R.id.tvLatestChapter)
        }
    }
}
