package com.translator.webview

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {
    const val PREFS    = "yomuai_prefs"
    const val KEY_LANG = "app_language"
    const val LANG_EN  = "en"
    const val LANG_AR  = "ar"

    fun setLocale(ctx: Context, lang: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()
    }

    fun setLanguage(ctx: Context, lang: String) = setLocale(ctx, lang)

    fun getLanguage(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, LANG_EN) ?: LANG_EN

    fun wrap(ctx: Context): Context {
        val lang   = getLanguage(ctx)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        return ctx.createConfigurationContext(config)
    }
}
