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

/**
 * Gemini 1.5 Flash — Context-Aware Manga/Novel Translator
 *
 * Features:
 *  • Gender-aware translation (masculine/feminine pronouns in Arabic)
 *  • Battle-scene detection — injects an energetic tone for action sequences
 *  • SFX (sound-effect) translation — keeps impact words punchy
 *  • Chunked chapter translation for long novel content
 */
class GeminiTranslator(private val apiKey: String) {

    companion object {
        private const val TAG = "GeminiTranslator"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

        private val BATTLE_KEYWORDS = setOf(
            "attack", "fight", "punch", "kick", "power", "energy", "slash",
            "dodge", "block", "sword", "blade", "explosion", "destroy",
            "kill", "die", "blood", "wound", "scream", "roar", "rage",
            "BOOM", "CRASH", "POW", "WHAM", "SMASH", "BANG", "ZAP",
            "أضرب", "قاتل", "هجوم", "انفجار", "دماء", "صرخ"
        )

        private val SFX_PATTERN = Regex(
            "\\b([A-Z]{2,}(?:[!*]+)?|[A-Z][a-z]*[!*]{2,})\\b"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Gender setting (set from onboarding prefs) ─────────────────────────

    var characterGender: String = "male"   // "male" | "female"

    // ── Core translate ─────────────────────────────────────────────────────

    suspend fun translateText(
        text: String,
        targetLanguage: String = "Arabic",
        context: String = "manga"
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext text

        val isBattle = isBattleScene(text)
        val hasSfx   = SFX_PATTERN.containsMatchIn(text)

        val prompt = buildTranslationPrompt(text, targetLanguage, context, isBattle, hasSfx)
        callGemini(prompt, temperature = if (isBattle) 0.4f else 0.2f) ?: text
    }

    /**
     * Translates OCR-captured manga panel text with gender + battle awareness.
     * Use this when translating text captured via ML Kit from the screen.
     */
    suspend fun translateMangaPanel(
        ocrText: String,
        targetLanguage: String = "Arabic",
        speakerGender: String = characterGender
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || ocrText.isBlank()) return@withContext ocrText

        val isBattle = isBattleScene(ocrText)
        val genderNote = when (speakerGender.lowercase()) {
            "female" -> "The speaker is female — use feminine verb/pronoun forms in Arabic (e.g., هي/أنتِ/فعلتِ)."
            else     -> "The speaker is male — use masculine verb/pronoun forms in Arabic (e.g., هو/أنتَ/فعلتَ)."
        }

        val prompt = buildString {
            append("You are an expert manga panel translator specializing in Arabic localization.\n\n")
            append("RULES:\n")
            append("1. $genderNote\n")
            if (isBattle) {
                append("2. This is a BATTLE SCENE — use energetic, powerful Arabic expressions. ")
                append("Use exclamation marks liberally! Convey urgency and impact!\n")
            }
            append("3. Translate SFX (BOOM, CRASH, POW, etc.) to punchy Arabic equivalents ")
            append("or keep them in ALL-CAPS romanized form with an Arabic meaning in parentheses.\n")
            append("4. Preserve character names as-is. Do NOT add explanations.\n")
            append("5. Return ONLY the translated text — no notes, no markdown.\n\n")
            append("OCR TEXT TO TRANSLATE:\n")
            append(ocrText)
        }

        callGemini(prompt, temperature = if (isBattle) 0.45f else 0.2f) ?: ocrText
    }

    /**
     * Translates manga/novel metadata fields in one batched call.
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
            append("Translate to $targetLanguage naturally and accurately. ")
            append("Return ONLY a JSON object with keys: title, genre, description. No extra text.\n\n")
            append("TITLE: $title\n")
            append("GENRE: $genre\n")
            append("DESCRIPTION: $description")
        }

        try {
            val requestJson = buildRequestBody(prompt, temperature = 0.1f, jsonMode = true)
            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Triple(title, genre, description)

            val body = response.body?.string() ?: return@withContext Triple(title, genre, description)
            val json = JSONObject(body)
            val raw = json.getJSONArray("candidates")
                .getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0)
                .getString("text").trim()
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
     * Translates a full novel chapter with gender + context awareness.
     */
    suspend fun translateChapterContent(
        content: String,
        targetLanguage: String = "Arabic"
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || content.isBlank()) return@withContext content
        val chunks = splitIntoChunks(content, 3000)
        val translated = StringBuilder()
        chunks.forEach { chunk ->
            val result = translateText(chunk, targetLanguage, "novel")
            translated.append(result).append("\n\n")
        }
        translated.toString().trim()
    }

    // ── Prompt builders ────────────────────────────────────────────────────

    private fun buildTranslationPrompt(
        text: String,
        targetLanguage: String,
        context: String,
        isBattle: Boolean,
        hasSfx: Boolean
    ): String = buildString {
        append("You are an expert $context translator specializing in $targetLanguage localization.\n\n")

        val genderInstr = when (characterGender.lowercase()) {
            "female" -> "Use feminine Arabic forms (هي/أنتِ) when addressing or describing the protagonist."
            else     -> "Use masculine Arabic forms (هو/أنتَ) when addressing or describing the protagonist."
        }
        append("GENDER RULE: $genderInstr\n")

        if (isBattle) {
            append("TONE: This is a BATTLE/ACTION scene! Use ENERGETIC, POWERFUL Arabic. ")
            append("Convey excitement and urgency. Use exclamation marks! Be dramatic!\n")
        }
        if (hasSfx) {
            append("SFX: Translate sound effects to Arabic equivalents (e.g., BOOM→بووم!, CRASH→كراش!, POW→باو!). ")
            append("Keep them short, punchy, all-caps or with شدة marks.\n")
        }

        append("RULES: Preserve character names. No explanations. No markdown. Return ONLY the translation.\n\n")
        append("TEXT:\n")
        append(text)
    }

    // ── HTTP helper ────────────────────────────────────────────────────────

    private fun callGemini(prompt: String, temperature: Float = 0.2f): String? {
        return try {
            val body = buildRequestBody(prompt, temperature)
            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API ${response.code}: $respBody")
                return null
            }

            val json = JSONObject(respBody)
            val candidates = json.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null

            candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call failed", e)
            null
        }
    }

    private fun buildRequestBody(
        prompt: String,
        temperature: Float = 0.2f,
        jsonMode: Boolean = false
    ): String {
        return JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
                put("maxOutputTokens", 4096)
                put("topP", 0.8)
                if (jsonMode) put("responseMimeType", "application/json")
            })
        }.toString()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun isBattleScene(text: String): Boolean {
        val lower = text.lowercase()
        return BATTLE_KEYWORDS.count { lower.contains(it.lowercase()) } >= 2 ||
               SFX_PATTERN.findAll(text).count() >= 2
    }

    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val paragraphs = text.split("\n\n")
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (para in paragraphs) {
            if (current.length + para.length > maxLen && current.isNotEmpty()) {
                chunks.add(current.toString()); current.clear()
            }
            current.append(para).append("\n\n")
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }
}
