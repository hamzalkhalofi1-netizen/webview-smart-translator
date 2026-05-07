# WebView Smart Translator — Native Android (Kotlin + ML Kit)

## نظرة عامة

تطبيق Android أصيل يفتح متصفح WebView كاملاً (Google) مع طبقة ترجمة عائمة تعمل داخل Activity بدون أي صلاحيات نظام إضافية.

### ميزات التطبيق
- **WebView كامل** يفتح Google.com مع JavaScript وDomStorage مفعّلَين
- **التقاط الإطار** عبر `PixelCopy` (API 26+) أو `WebView.draw()` كـ fallback
- **OCR على الجهاز** عبر ML Kit Text Recognition (بدون إنترنت بعد التثبيت)
- **ترجمة على الجهاز** عبر ML Kit Translation EN→AR (نماذج تُحمَّل مرة واحدة)
- **طبقة عائمة** قابلة للسحب والتغيير في الحجم داخل Activity (لا `SYSTEM_ALERT_WINDOW`)
- **زر تحديث** لإعادة التقاط الصفحة وإعادة الترجمة

---

## متطلبات البناء

| أداة | الإصدار المطلوب |
|------|----------------|
| Android Studio | Hedgehog 2023.1.1 أو أحدث |
| Android SDK | API 26 (Oreo) كحد أدنى |
| Kotlin | 1.9.22 |
| Gradle | 8.4 |
| JDK | 17 |

---

## خطوات الفتح والبناء

```bash
# 1. افتح Android Studio
# 2. File → Open → اختر مجلد المشروع WebViewTranslator
# 3. انتظر Gradle Sync (يحمّل المكتبات تلقائياً)
# 4. وصّل هاتفك بـ USB وفعّل Developer Options + USB Debugging
# 5. اضغط Run ▶ أو Shift+F10
```

**بناء APK للتوزيع:**
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```
المسار: `app/build/outputs/apk/debug/app-debug.apk`

---

## بنية الكود

```
app/src/main/
├── java/com/translator/webview/
│   ├── MainActivity.kt        ← الشاشة الرئيسية: WebView + FAB + التقاط الإطار
│   ├── OverlayView.kt         ← Custom View العائم: رسم + سحب + تغيير حجم
│   └── TranslationManager.kt ← ML Kit OCR + Translation wrapper
├── res/
│   ├── layout/activity_main.xml
│   ├── values/strings.xml
│   ├── values/colors.xml
│   ├── values/themes.xml
│   └── drawable/ic_translate.xml
└── AndroidManifest.xml
```

---

## كيف يعمل التطبيق

```
[ضغط زر "ترجمة"]
       ↓
[PixelCopy / WebView.draw() → Bitmap]
       ↓
[ML Kit Text Recognition → قائمة TextBlock مع إحداثيات]
       ↓
[ML Kit Translator → ترجمة كل كتلة نصية]
       ↓
[OverlayView.onDraw() → رسم Bitmap + إخفاء النص الأصلي + رسم الترجمة في نفس الإحداثيات]
```

---

## أول تشغيل — تحميل نموذج الترجمة

عند أول تشغيل للتطبيق على الجهاز:
- يبدأ ML Kit بتحميل نموذج الترجمة (EN↔AR) تلقائياً عبر Wi-Fi
- حجم النموذج: ~30 MB تقريباً
- بعد التحميل يعمل التطبيق بالكامل **بدون إنترنت**

---

## التخصيص

### تغيير لغة الترجمة
في `TranslationManager.kt`:
```kotlin
// غيّر هذا السطر لتغيير اللغة الهدف
private var targetLanguageCode: String = TranslateLanguage.ARABIC
// مثال لتغييرها إلى الفرنسية:
// private var targetLanguageCode: String = TranslateLanguage.FRENCH
```

### تغيير موقع البداية للمتصفح
في `MainActivity.kt`:
```kotlin
loadUrl("https://www.google.com")  // غيّر هذا الرابط
```

### تغيير ألوان الطبقة العائمة
في `res/values/colors.xml` — كل الألوان موثّقة باسمها.

---

## الصلاحيات المستخدمة

| الصلاحية | السبب |
|---------|-------|
| `INTERNET` | تصفح الويب وتحميل نماذج ML Kit |
| `ACCESS_NETWORK_STATE` | التحقق من الاتصال قبل تحميل النماذج |

> لا يستخدم التطبيق `SYSTEM_ALERT_WINDOW` لأن الطبقة العائمة تعمل داخل Activity فقط.

---

## المكتبات المستخدمة

| المكتبة | الإصدار | الغرض |
|---------|---------|-------|
| `com.google.mlkit:text-recognition` | 16.0.0 | OCR على الجهاز |
| `com.google.mlkit:translate` | 17.0.1 | ترجمة على الجهاز |
| `androidx.webkit:webkit` | 1.10.0 | WebView محسّن |
| `com.google.android.material` | 1.11.0 | ExtendedFAB |
| `kotlinx-coroutines-android` | 1.7.3 | عمليات async |
