package com.translator.webview

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

// ── Data models ────────────────────────────────────────────────────────────

data class MangaCard(
    val title: String,
    val coverUrl: String,
    val mangaUrl: String,
    val genre: String = "",
    val latestChapter: String = ""
)

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

// ── ScrapingEngine ─────────────────────────────────────────────────────────

class ScrapingEngine {

    companion object {
        private const val TAG = "ScrapingEngine"
        private val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        // Known catalog page paths per popular domains
        private val CATALOG_PATHS = listOf(
            "/manga", "/manga-list", "/series", "/comics",
            "/all-manga", "/manga-directory", "/catalog",
            "/library", "/titles", "/browse", "/"
        )

        // Selectors for manga cards on catalog / home pages
        private val MANGA_CARD_SELECTORS = listOf(
            ".manga-item",          // generic
            ".post-item",           // WordPress manga themes
            ".listupd .bs",         // madara
            ".page-item-detail",    // madara v2
            ".manga-list .manga",   // mangalek style
            ".book-item",
            ".manga-entry",
            "article.manga",
            ".comic-item",
            ".series-item",
            ".manga-card",
            ".novel-item",
            ".c-tabs-item__content"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── Public: Manga catalog ──────────────────────────────────────────────

    /**
     * Given a domain (e.g. "mangalek.com" or "https://mangalek.com"),
     * scrapes the site catalog and returns a list of MangaCards for the grid.
     */
    suspend fun fetchMangaList(
        domain: String,
        onProgress: (Int, String) -> Unit
    ): List<MangaCard> = withContext(Dispatchers.IO) {

        val base = normalizeDomain(domain)
        onProgress(5, "Connecting to $base…")

        // Try catalog paths until we find a page with manga cards
        for ((index, path) in CATALOG_PATHS.withIndex()) {
            val progress = 5 + (index * 60 / CATALOG_PATHS.size)
            val url = "$base$path"
            try {
                onProgress(progress, "Scanning $url…")
                val doc = fetchDocument(url)
                val cards = parseMangaCards(doc, base)
                if (cards.isNotEmpty()) {
                    onProgress(100, "Found ${cards.size} titles!")
                    return@withContext cards
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed $url: ${e.message}")
            }
        }

        // Fallback: parse the domain root and extract any manga links
        onProgress(90, "Trying root page…")
        try {
            val doc = fetchDocument(base)
            val cards = parseMangaCards(doc, base)
            onProgress(100, "Done — ${cards.size} titles found")
            return@withContext cards
        } catch (e: Exception) {
            Log.e(TAG, "Root page failed", e)
        }

        emptyList()
    }

    private fun parseMangaCards(doc: Document, base: String): List<MangaCard> {
        for (sel in MANGA_CARD_SELECTORS) {
            val els = doc.select(sel)
            if (els.size < 3) continue   // need at least a few items to be a valid catalog

            val cards = els.mapNotNull { el ->
                val anchor = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href   = anchor.attr("href").trim()
                val url    = if (href.startsWith("http")) href else "$base${href.trimStart('/')}"
                val title  = (el.selectFirst(".manga-name, .title, h3, h4, .name, .series-title, a")
                    ?.text()?.trim() ?: anchor.attr("title").trim()).ifBlank { return@mapNotNull null }
                val cover  = el.selectFirst("img")?.let { img ->
                    (img.attr("data-src").ifBlank { img.attr("src") }).trim()
                } ?: ""
                val genre  = el.selectFirst(".genre, .manga-type, .type")?.text()?.trim() ?: ""
                val latest = el.selectFirst(".chapter, .latest-chapter, .chapter-item a, .uta a")?.text()?.trim() ?: ""
                MangaCard(title, cover, url, genre, latest)
            }
            if (cards.isNotEmpty()) return cards
        }

        // Last-resort: pull any anchor with a cover image and reasonable URL
        return doc.select("a[href]:has(img)").mapNotNull { anchor ->
            val href = anchor.attr("href").trim()
            if (!href.contains("manga", ignoreCase = true) &&
                !href.contains("series", ignoreCase = true) &&
                !href.contains("comic", ignoreCase = true)) return@mapNotNull null
            val url   = if (href.startsWith("http")) href else "$base${href.trimStart('/')}"
            val title = anchor.attr("title").trim().ifBlank { anchor.text().trim() }.ifBlank { return@mapNotNull null }
            val cover = anchor.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") }).trim()
            } ?: ""
            MangaCard(title, cover, url)
        }.distinctBy { it.mangaUrl }.take(60)
    }

    // ── Public: Single manga metadata ──────────────────────────────────────

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

    // ── Public: Chapter images ─────────────────────────────────────────────

    suspend fun downloadChapter(
        chapter: ChapterInfo,
        onProgress: (Int) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        onProgress(10)
        val doc = fetchDocument(chapter.url)
        onProgress(40)

        val images = extractChapterImages(doc, chapter.url)
        onProgress(70)

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
            chapterInfo  = chapter,
            textContent  = textContent,
            imageUrls    = images,
            isNovel      = images.isEmpty()
        )
    }

    // ── Image extraction: multi-strategy ──────────────────────────────────

    fun extractChapterImages(doc: Document, pageUrl: String): List<String> {
        val base = pageUrl.split("/").take(3).joinToString("/")

        // Ordered priority of selectors for manga reader pages
        val strategies = listOf(
            // Madara / WP-Manga
            "div.reading-content img",
            ".reading-content img[src]",
            ".reading-content img[data-src]",
            // ReaderArea (mangalek style)
            "#readerarea img",
            "#readerarea source",
            // Generic chapter containers
            ".chapter-images img",
            ".chapter-content img",
            "#chapter-content img",
            ".page-break img",
            ".page-break source",
            // Comic/webtoon sites
            ".comic-image img",
            ".webtoon-image img",
            "#viewer img",
            ".viewer img",
            // Fallback: all images with chapter/page in URL
            "img[src*='chapter']",
            "img[src*='page']",
            "img[src*='manga']",
            "img[src*='.jpg']",
            "img[src*='.png']",
            "img[src*='.webp']",
            "img[data-src*='chapter']",
            "img[data-src*='page']",
            "img[data-src]"
        )

        for (sel in strategies) {
            val imgs = doc.select(sel)
                .map { el ->
                    val src = el.attr("data-src").ifBlank {
                        el.attr("data-lazy-src").ifBlank {
                            el.attr("data-original").ifBlank {
                                el.attr("src")
                            }
                        }
                    }.trim()
                    // Resolve relative URLs
                    when {
                        src.startsWith("http") -> src
                        src.startsWith("//")   -> "https:$src"
                        src.startsWith("/")    -> "$base$src"
                        else -> src
                    }
                }
                .filter { url ->
                    url.isNotBlank() &&
                    !url.contains("logo", ignoreCase = true) &&
                    !url.contains("icon", ignoreCase = true) &&
                    !url.contains("avatar", ignoreCase = true) &&
                    (url.contains(".jpg", ignoreCase = true) ||
                     url.contains(".jpeg", ignoreCase = true) ||
                     url.contains(".png", ignoreCase = true) ||
                     url.contains(".webp", ignoreCase = true) ||
                     url.contains(".gif", ignoreCase = true))
                }
                .distinct()

            // Need at least 2 images to be considered a valid manga page set
            if (imgs.size >= 2) {
                Log.d(TAG, "Found ${imgs.size} images via selector: $sel")
                return imgs
            }
        }

        // JSON-embedded image list (some sites store images in JS variables)
        val scriptImages = extractImagesFromScripts(doc.html(), base)
        if (scriptImages.size >= 2) return scriptImages

        return emptyList()
    }

    private fun extractImagesFromScripts(html: String, base: String): List<String> {
        val patterns = listOf(
            Regex(""""(https?://[^"]+\.(?:jpg|jpeg|png|webp|gif))"""", RegexOption.IGNORE_CASE),
            Regex("""'(https?://[^']+\.(?:jpg|jpeg|png|webp|gif))'""", RegexOption.IGNORE_CASE),
            Regex("""src:\s*["'](https?://[^"']+\.(?:jpg|jpeg|png|webp|gif))["']""", RegexOption.IGNORE_CASE)
        )
        val results = mutableSetOf<String>()
        for (pattern in patterns) {
            pattern.findAll(html).forEach { results.add(it.groupValues[1]) }
        }
        return results.toList()
    }

    // ── Generic metadata scraper ───────────────────────────────────────────

    private fun scrapeGeneric(doc: Document, baseUrl: String): MangaMetadata {
        val title       = resolveTitle(doc)
        val description = resolveDescription(doc)
        val genre       = resolveGenre(doc)
        val coverUrl    = resolveCover(doc)
        val chapters    = resolveChapters(doc, baseUrl)
        Log.d(TAG, "Scraped: \"$title\" | ${chapters.size} chapters")
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
        return "Manga"
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
        } ?: doc.select("a[href]").filter { el ->
            val href = el.attr("href")
            val txt  = el.text()
            href.contains("chapter", ignoreCase = true) ||
            href.contains("/ch-", ignoreCase = true) ||
            txt.matches(Regex(".*[Cc]hapter\\s*\\d+.*")) ||
            txt.matches(Regex("\\s*\\d+(\\.\\d+)?\\s*"))
        }.take(100)

        return chapterEls.mapIndexed { index, el ->
            val href = el.attr("href").trim()
            val fullUrl = when {
                href.startsWith("http") -> href
                href.startsWith("/")    -> "$base$href"
                else                    -> "$base/$href"
            }
            val num      = index + 1
            val rawTitle = el.text().trim().ifBlank { "Chapter $num" }
            ChapterInfo(rawTitle, fullUrl, num)
        }.reversed()
    }

    // ── HTTP ───────────────────────────────────────────────────────────────

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ar,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        return Jsoup.parse(body, url)
    }

    private fun normalizeDomain(input: String): String {
        val s = input.trim()
        return when {
            s.startsWith("https://") || s.startsWith("http://") ->
                s.trimEnd('/')
            else -> "https://${s.trimEnd('/')}"
        }
    }
}
