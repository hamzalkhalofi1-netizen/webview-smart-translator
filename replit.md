# YomuAI — AI-Powered Manga & Novel Reader (Android / Kotlin)

## Project Overview

**YomuAI** is a native Android application written in Kotlin that combines a full-featured WebView browser, an ML Kit translation overlay, a Manga/Novel downloader with real-time progress, Google Gemini AI context-aware translation, and a smart reader — all with a premium dark theme and a modern rounded Navigation Drawer.

---

## Phase Status

| Phase | Status | Features |
|-------|--------|----------|
| Phase 1 | ✅ Complete | WebView browser, ML Kit OCR + translation, floating overlay, dark theme |
| Phase 2 | ✅ Complete | Gemini AI, Manga/Novel downloader, Smart Reader, Navigation Drawer, About screen |

---

## Tech Stack

| Tool | Version |
|------|---------|
| Language | Kotlin 1.9.22 |
| Build | Gradle 8.4 |
| Min SDK | API 26 (Android 8.0) |
| Target SDK | API 34 (Android 14) |
| JDK | 17 |
| AI | Google Gemini 1.5 Flash |
| Scraping | OkHttp 4.12 + Jsoup 1.17 |
| On-device AI | ML Kit Text Recognition + Translate |

---

## Project Structure

```
app/src/main/
├── java/com/translator/webview/
│   ├── MainActivity.kt         ← Main screen: WebView + Navigation Drawer
│   ├── DownloaderActivity.kt   ← Manga/Novel downloader with real-time progress
│   ├── ReaderActivity.kt       ← Smart reader (manga pages + novel text)
│   ├── AboutActivity.kt        ← About screen with links
│   ├── GeminiTranslator.kt     ← Gemini 1.5 Flash REST API integration
│   ├── ScrapingEngine.kt       ← Generic HTML scraper (OkHttp + Jsoup)
│   ├── OverlayView.kt          ← Custom floating translation overlay
│   ├── TranslationManager.kt   ← ML Kit OCR + Translation wrapper
│   ├── TranslationService.kt   ← Background foreground service
│   ├── NotificationHelper.kt   ← Notification channel builder
│   └── TranslatorApp.kt        ← Application class + crash handler
├── res/
│   ├── layout/
│   │   ├── activity_main.xml        ← DrawerLayout + WebView
│   │   ├── activity_downloader.xml  ← Downloader UI with progress
│   │   ├── activity_reader.xml      ← Smart reader with nav FABs
│   │   ├── activity_about.xml       ← About screen
│   │   ├── nav_header_main.xml      ← Drawer header (logo + title)
│   │   ├── item_chapter.xml         ← Chapter list row
│   │   └── view_translate_bubble.xml
│   ├── menu/nav_menu.xml            ← Drawer navigation menu
│   ├── drawable/                    ← Icons + rounded drawer background
│   ├── values/strings.xml           ← All strings (English)
│   ├── values/themes.xml            ← Theme.YomuAI (Material3 DayNight)
│   ├── values/colors.xml            ← Dark blue premium palette
│   └── xml/network_security_config.xml
└── AndroidManifest.xml
```

---

## Building the APK

### Option 1: GitHub Actions (Automated CI)
Push to GitHub → Actions tab runs automatically → Download `YomuAI-debug.apk` from the Artifacts section.

**Add your Gemini API key as a GitHub Secret:**
- Repository → Settings → Secrets → `GEMINI_API_KEY`

### Option 2: Android Studio (Local)
1. Open in Android Studio Hedgehog 2023.1.1+
2. Gradle sync completes automatically
3. `Build → Build APK(s)` or press **▶**
4. APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## Gemini API Key

Stored securely in `local.properties` (git-ignored):
```
GEMINI_API_KEY=AIzaSy...
```
Injected at build time via `BuildConfig.GEMINI_API_KEY`.

---

## Phase 2 Feature Details

### Navigation Drawer
- Rounded right-edge drawer (`bg_drawer.xml` with `topRightRadius=32dp`)
- Header with YomuAI logo, title, subtitle
- Menu: Home Browser · Manga/Novel Downloader · Reader · About

### Manga/Novel Downloader (`DownloaderActivity`)
- Input any manga/novel site URL
- Generic HTML scraper extracts: title, genre, description, cover, chapter list
- Real-time progress bar 0→100% during fetch and download
- Gemini AI translates all metadata (title, genre, description) in one batched call
- Per-chapter download with Gemini AI content translation for novels
- Chapters shown in RecyclerView with ✓ indicator when downloaded
- Tap any chapter to open it immediately in the Reader

### Gemini AI Integration (`GeminiTranslator`)
- Uses Gemini 1.5 Flash REST API over OkHttp
- `translateMetadata()` — batched JSON call for title/genre/description
- `translateText()` — single field with custom context prompt
- `translateChapterContent()` — chunks long text and translates paragraph-by-paragraph
- Temperature: 0.2 (accurate), response format: JSON for metadata

### Smart Reader (`ReaderActivity`)
- Manga mode: renders image pages in a full-width vertical scroll HTML view
- Novel mode: renders translated text with serif font, dark background, 1.9 line height
- Prev/Next chapter FABs
- Lazy image loading with error handling

### About Screen (`AboutActivity`)
- YomuAI vector logo
- Version 2.0 badge
- "Powered by Google Gemini AI" chip
- Tappable links: Email · YouTube · Twitter/X · Discord

---

## User Preferences
- Maintain existing Kotlin/Android project structure
- Package: `com.translator.webview` (unchanged for APK signing continuity)
- App name: **YomuAI** everywhere
