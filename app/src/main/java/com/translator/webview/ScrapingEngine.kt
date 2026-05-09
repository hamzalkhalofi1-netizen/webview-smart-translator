package com.translator.webview

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

data class MangaMetadata(
    val title: String,
    val genre: String,
    val description: String,
    val coverUrl: String,
    val siteUrl: String,
    val chapters: List<ChapterInfo>
)

data class ChapterInfo(
    val title: String,
    val url: String,
    val number: Int
)

data class DownloadResult(
    val chapterInfo: ChapterInfo,
    val textContent: String,
    val imageUrls: List<String>,
    val isNovel: Boolean
)

class ScrapingEngine {

    companion object {
        private const val TAG = "ScrapingEngine"
        private val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── Public API ─────────────────────────────────────────────────────────────

    suspend fun fetchMetadata(
        url: String,
        onProgress: (Int, String) -> Unit
    ): MangaMetadata = withContext(Dispatchers.IO) {
        onProgress(10, "Connecting to site…")
        val doc = fetchDocument(url)
        onProgress(50, "Parsing metadata…")
        val metadata = scrapeGeneric(doc, url)
        onProgress(100, "Metadata fetched!")
        metadata
    }

    suspend fun downloadChapter(
        chapter: ChapterInfo,
        onProgress: (Int) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        onProgress(10)
        val doc = fetchDocument(chapter.url)
        onProgress(40)

        // Try image selectors first (manga mode)
        val imageSelectors = listOf(
            "img.wp-manga-chapter-img",
            ".reading-content img",
            "#readerarea img",
            ".chapter-content img",
            ".page-break img",
            ".manga-reader img",
            "img[src*='chapter']",
            "img[src*='page']"
        )

        val images = imageSelectors
            .flatMap { doc.select(it) }
            .map { el -> el.attr("src").ifBlank { el.attr("data-src") } }
            .filter { url ->
                url.isNotBlank() && (
                    url.contains(".jpg", ignoreCase = true) ||
                    url.contains(".jpeg", ignoreCase = true) ||
                    url.contains(".png", ignoreCase = true) ||
                    url.contains(".webp", ignoreCase = true)
                )
            }
            .distinct()

        onProgress(70)

        // If no images found, treat as novel (text mode)
        val textContent = if (images.isEmpty()) {
            val textSelectors = listOf(
                ".chapter-content", "#chapter-content", ".content",
                ".text-chapter", ".novel-content", "#novel-content",
                "article .entry-content", ".reading-content",
                ".chapter-text", "main article"
            )
            textSelectors.firstNotNullOfOrNull { sel ->
                doc.selectFirst(sel)?.text()?.takeIf { it.length > 50 }
            } ?: doc.body()?.text() ?: ""
        } else ""

        onProgress(100)

        DownloadResult(
            chapterInfo = chapter,
            textContent = textContent,
            imageUrls = images,
            isNovel = images.isEmpty()
        )
    }

    // ── Generic scraper ────────────────────────────────────────────────────────

    private fun scrapeGeneric(doc: Document, baseUrl: String): MangaMetadata {
        val title = resolveTitle(doc)
        val description = resolveDescription(doc)
        val genre = resolveGenre(doc)
        val coverUrl = resolveCover(doc)
        val chapters = resolveChapters(doc, baseUrl)

        Log.d(TAG, "Scraped: \"$title\" | ${chapters.size} chapters | cover=$coverUrl")
        return MangaMetadata(title, genre, description, coverUrl, baseUrl, chapters)
    }

    private fun resolveTitle(doc: Document): String {
        val selectors = listOf(
            "h1.entry-title", "h1.post-title", "h1.manga-title",
            ".novel-title h1", ".manga-name", "[itemprop='name']",
            "h1", "title"
        )
        return selectors.firstNotNullOfOrNull { sel ->
            doc.selectFirst(sel)?.text()?.trim()?.takeIf { it.isNotBlank() }
        } ?: doc.title().substringBefore(" - ").substringBefore(" | ").trim()
    }

    private fun resolveDescription(doc: Document): String {
        val selectors = listOf(
            ".description-summary p", ".summary__content p",
            "[itemprop='description']", ".synopsis", ".summary",
            ".manga-description", ".novel-description", ".entry-content p",
            "meta[name='description']", "meta[property='og:description']"
        )
        return selectors.firstNotNullOfOrNull { sel ->
            val el = doc.selectFirst(sel)
            if (sel.startsWith("meta")) el?.attr("content")?.takeIf { it.isNotBlank() }
            else el?.text()?.takeIf { it.isNotBlank() }
        } ?: "No description available."
    }

    private fun resolveGenre(doc: Document): String {
        val selectors = listOf(
            ".genres-content a", ".genre a", ".tags a",
            "[itemprop='genre']", ".category a", ".manga-genres a",
            ".genre-item", ".tag"
        )
        for (sel in selectors) {
            val items = doc.select(sel).take(5).map { it.text().trim() }.filter { it.isNotBlank() }
            if (items.isNotEmpty()) return items.joinToString(" • ")
        }
        return "Manga / Novel"
    }

    private fun resolveCover(doc: Document): String {
        val selectors = listOf(
            ".summary_image img", ".manga-thumbnail img",
            "[itemprop='image']", ".cover img", ".book-cover img",
            "meta[property='og:image']", ".novel-cover img"
        )
        return selectors.firstNotNullOfOrNull { sel ->
            val el = doc.selectFirst(sel)
            if (sel.startsWith("meta")) el?.attr("content")?.takeIf { it.isNotBlank() }
            else (el?.attr("src") ?: el?.attr("data-src"))?.takeIf { it.isNotBlank() }
        } ?: ""
    }

    private fun resolveChapters(doc: Document, baseUrl: String): List<ChapterInfo> {
        val base = baseUrl.split("/").take(3).joinToString("/")

        val selectors = listOf(
            ".wp-manga-chapter a", ".chapter-list a", ".chapters a",
            ".chapter-link a", ".chapter_list a", ".row-content-chapter a",
            "li.chapter a", ".listing-chapters_wrap a", "a[href*='chapter']"
        )

        val chapterEls = selectors.firstNotNullOfOrNull { sel ->
            val els = doc.select(sel)
            if (els.size > 0) els.take(200) else null
        } ?: run {
            // Fallback: any links that look like chapters
            doc.select("a[href]").filter { el ->
                val href = el.attr("href")
                val txt = el.text()
                href.contains("chapter", ignoreCase = true) ||
                href.contains("/ch-", ignoreCase = true) ||
                txt.matches(Regex(".*[Cc]hapter\\s*\\d+.*")) ||
                txt.matches(Regex("\\s*\\d+(\\.\\d+)?\\s*"))
            }.take(100)
        }

        return chapterEls.mapIndexed { index, el ->
            val href = el.attr("href").trim()
            val fullUrl = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> "$base$href"
                else -> "$base/$href"
            }
            val chapterNum = index + 1
            val rawTitle = el.text().trim().ifBlank { "Chapter $chapterNum" }
            ChapterInfo(rawTitle, fullUrl, chapterNum)
        }.reversed() // Sites usually list newest first; reverse to show oldest first
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Cache-Control", "no-cache")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        return Jsoup.parse(body, url)
    }
}
