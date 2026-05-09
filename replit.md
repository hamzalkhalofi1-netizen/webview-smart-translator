# WebView Smart Translator — Native Android (Kotlin + ML Kit)

## Project Overview

This is a native Android application written in Kotlin. It opens a full WebView (Google) with a floating translation overlay that works entirely inside an Activity — no system-level permissions required.

### Features
- Full WebView browser (Google.com) with JavaScript and DomStorage enabled
- Screen capture via `PixelCopy` (API 26+) or `WebView.draw()` as fallback
- On-device OCR via ML Kit Text Recognition (works offline after install)
- On-device translation (EN→AR and 8 other languages) via ML Kit Translation
- Draggable, resizable floating overlay inside the Activity
- Refresh button to re-capture and re-translate the page

### Tech Stack
- **Language**: Kotlin 1.9.22
- **Build System**: Gradle 8.4 (wrapper included)
- **Min SDK**: API 26 (Android 8.0 Oreo)
- **Target SDK**: API 34 (Android 14)
- **JDK**: 17
- **Libraries**: ML Kit Text Recognition, ML Kit Translate, AndroidX WebKit, Material, Coroutines

### Project Structure
```
app/src/main/
├── java/com/translator/webview/
│   ├── MainActivity.kt        ← Main screen: WebView + FAB + frame capture
│   ├── OverlayView.kt         ← Custom floating view: draw + drag + resize
│   ├── TranslationManager.kt ← ML Kit OCR + Translation wrapper
│   ├── TranslationService.kt ← Background translation service
│   ├── NotificationHelper.kt ← Notification support
│   └── TranslatorApp.kt      ← Application class
├── res/
│   ├── layout/activity_main.xml
│   ├── layout/view_translate_bubble.xml
│   ├── values/strings.xml
│   ├── values/colors.xml
│   ├── values/themes.xml
│   ├── values/attrs.xml
│   └── drawable/
└── AndroidManifest.xml
```

## Running This Project

This is a **native Android app** — it cannot run as a web server and has no browser preview. To build and run it:

1. Open the project in **Android Studio** (Hedgehog 2023.1.1 or newer)
2. Wait for Gradle sync to complete
3. Connect an Android device (USB debugging enabled) or start an emulator
4. Press **Run ▶** or `Shift+F10`

**Build APK:**
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

## Customization

- **Change translation language**: Edit `TranslationManager.kt` → `targetLanguageCode`
- **Change start URL**: Edit `MainActivity.kt` → `loadUrl("https://www.google.com")`
- **Change overlay colors**: Edit `res/values/colors.xml`

## User Preferences

- Maintain existing Kotlin/Android project structure
