package com.translator.webview

import android.app.Application
import android.util.Log

class TranslatorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            Log.e(TAG, "╔══════════════════════════════════════╗")
            Log.e(TAG, "║        UNCAUGHT EXCEPTION            ║")
            Log.e(TAG, "╠══════════════════════════════════════╣")
            Log.e(TAG, "║ Thread : ${thread.name}")
            Log.e(TAG, "║ Type   : ${ex.javaClass.simpleName}")
            Log.e(TAG, "║ Message: ${ex.message}")
            Log.e(TAG, "║ Cause  : ${ex.cause?.javaClass?.simpleName} — ${ex.cause?.message}")
            Log.e(TAG, "╠══ Stack Trace ════════════════════════╣")
            ex.stackTrace.take(20).forEach { Log.e(TAG, "║   at $it") }
            ex.cause?.let { cause ->
                Log.e(TAG, "╠══ Caused By ══════════════════════════╣")
                cause.stackTrace.take(10).forEach { Log.e(TAG, "║   at $it") }
            }
            Log.e(TAG, "╚══════════════════════════════════════╝")

            // Delegate to the default handler so Android can generate a crash report
            defaultHandler?.uncaughtException(thread, ex)
        }
    }

    companion object {
        private const val TAG = "WebViewTranslator"
    }
}
