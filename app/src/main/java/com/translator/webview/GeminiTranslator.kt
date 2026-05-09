package com.translator.webview

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiTranslator(private val apiKey: String) {

    companion object {
        private const val TAG = "GeminiTranslator"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Translates [text] to [targetLanguage] using Gemini AI with context-aware,
     * natural translation — avoids literal word-for-word errors common with
     * basic machine translation.
     */
    suspend fun translateText(
        text: String,
        targetLanguage: String = "Arabic",
        context: String = "manga/novel"
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext text

        val prompt = buildString {
            append("You are an expert $context translator. ")
            append("Translate the following text to $targetLanguage with natural, context-aware translation. ")
            append("Preserve the tone, nuance, cultural references, and character names. ")
            append("Return ONLY the translated text — no explanations, no notes, no markdown.\n\n")
            append(text)
        }

        try {
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                    put("maxOutputTokens", 4096)
                    put("topP", 0.8)
                })
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext text

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API error ${response.code}: $responseBody")
                return@withContext text
            }

            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) return@withContext text

            val translated = candidates
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            Log.d(TAG, "Translated ${text.length} chars → ${translated.length} chars")
            translated

        } catch (e: Exception) {
            Log.e(TAG, "Gemini translation failed", e)
            text
        }
    }

    /**
     * Translates manga/novel metadata fields (title, genre, description)
     * in a single batched call to reduce API round-trips.
     */
    suspend fun translateMetadata(
        title: String,
        genre: String,
        description: String,
        targetLanguage: String = "Arabic"
    ): Triple<String, String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Triple(title, genre, description)

        val prompt = buildString {
            append("You are an expert manga/novel metadata translator. ")
            append("Translate the following fields to $targetLanguage naturally and accurately. ")
            append("Return ONLY a JSON object with keys: title, genre, description. No extra text.\n\n")
            append("TITLE: $title\n")
            append("GENRE: $genre\n")
            append("DESCRIPTION: $description")
        }

        try {
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 1024)
                    put("responseMimeType", "application/json")
                })
            }.toString()

            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Triple(title, genre, description)

            val body = response.body?.string() ?: return@withContext Triple(title, genre, description)
            val json = JSONObject(body)
            val raw = json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            val result = JSONObject(raw)
            Triple(
                result.optString("title", title),
                result.optString("genre", genre),
                result.optString("description", description)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Metadata translation failed", e)
            Triple(title, genre, description)
        }
    }

    /**
     * Translates a full chapter's text content with proper paragraph preservation.
     */
    suspend fun translateChapterContent(
        content: String,
        targetLanguage: String = "Arabic"
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || content.isBlank()) return@withContext content

        // Split into manageable chunks (Gemini has token limits)
        val chunks = splitIntoChunks(content, 3000)
        val translated = StringBuilder()

        chunks.forEach { chunk ->
            val result = translateText(chunk, targetLanguage, "novel")
            translated.append(result).append("\n\n")
        }

        translated.toString().trim()
    }

    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val paragraphs = text.split("\n\n")
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (para in paragraphs) {
            if (current.length + para.length > maxLen && current.isNotEmpty()) {
                chunks.add(current.toString())
                current.clear()
            }
            current.append(para).append("\n\n")
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }
}
