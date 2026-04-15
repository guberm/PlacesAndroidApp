package com.guberdev.places.data

import android.util.Log
import com.guberdev.places.data.model.PlaceRecommendation
import com.guberdev.places.data.model.ProviderModel
import com.guberdev.places.data.model.ProviderModelsResponse
import com.guberdev.places.data.model.RecommendationRequest
import com.guberdev.places.data.model.RecommendationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class LocalRecommendationEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun recommend(
        request: RecommendationRequest,
        userApiKeys: Map<String, String>
    ): RecommendationResponse = withContext(Dispatchers.IO) {
        // 1. Resolve coordinates
        var lat = request.latitude ?: 0.0
        var lng = request.longitude ?: 0.0
        var resolvedAddress = request.address

        if ((lat == 0.0 && lng == 0.0) && !request.address.isNullOrBlank()) {
            val geo = geocode(request.address)
            if (geo != null) {
                lat = geo.first
                lng = geo.second
                Log.d("LocalEngine", "Geocoded '${request.address}' → $lat,$lng")
            }
        }

        // 2. Build prompt and call AI
        val categoryLabel = formatCategoryLabel(request.categories)
        val locationDesc = buildLocationDesc(lat, lng, resolvedAddress)
        val prompt = buildGenerationPrompt(locationDesc, categoryLabel, request.radiusMeters)

        val (raw, providerName) = callAi(prompt, userApiKeys)
        Log.d("LocalEngine", "Got response from $providerName (${raw.length} chars)")

        // 3. Parse
        val places = parseRecommendations(raw, providerName, request.categories.firstOrNull() ?: "All")

        RecommendationResponse(
            latitude = lat,
            longitude = lng,
            resolvedAddress = resolvedAddress,
            category = request.categories.firstOrNull() ?: "All",
            categories = request.categories,
            recommendations = places,
            fromCache = false
        )
    }

    suspend fun getModels(
        provider: String,
        apiKey: String?,
        endpoint: String?
    ): ProviderModelsResponse = withContext(Dispatchers.IO) {
        when (provider) {
            "OpenAI" -> fetchOpenAiModels(apiKey)
            "OpenRouter" -> fetchOpenRouterModels(apiKey)
            "Gemini" -> fetchGeminiModels(apiKey)
            "Anthropic" -> ProviderModelsResponse(
                models = listOf(
                    ProviderModel("claude-opus-4-5", "Claude Opus 4.5"),
                    ProviderModel("claude-sonnet-4-5", "Claude Sonnet 4.5"),
                    ProviderModel("claude-haiku-3-5", "Claude Haiku 3.5"),
                    ProviderModel("claude-3-opus-20240229", "Claude 3 Opus"),
                    ProviderModel("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet"),
                    ProviderModel("claude-3-haiku-20240307", "Claude 3 Haiku"),
                ),
                warning = null
            )
            "AzureOpenAI" -> fetchAzureModels(apiKey, endpoint)
            else -> ProviderModelsResponse(emptyList(), "Unknown provider")
        }
    }

    // ── Geocoding ─────────────────────────────────────────────────────────────

    private fun geocode(address: String): Pair<Double, Double>? {
        return try {
            val encoded = URLEncoder.encode(address, "UTF-8")
            val req = Request.Builder()
                .url("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1")
                .header("User-Agent", "PlacesApp/1.0 (Android)")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return null
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            val obj = arr.getJSONObject(0)
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) null else Pair(lat, lon)
        } catch (e: Exception) {
            Log.e("LocalEngine", "Geocoding failed: ${e.message}")
            null
        }
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    private fun buildLocationDesc(lat: Double, lng: Double, address: String?): String {
        val hasCoords = !(lat == 0.0 && lng == 0.0)
        return when {
            hasCoords && address != null -> "coordinates (${"%.5f".format(lat)}, ${"%.5f".format(lng)}) ($address)"
            hasCoords -> "coordinates (${"%.5f".format(lat)}, ${"%.5f".format(lng)})"
            address != null -> address
            else -> "the requested area"
        }
    }

    private fun buildGenerationPrompt(locationDesc: String, categoryLabel: String, radiusMeters: Int): String {
        val radiusDesc = when {
            radiusMeters <= 0 -> ""
            radiusMeters < 1000 -> "within ${radiusMeters}m (${radiusMeters} meters)"
            else -> "within ${radiusMeters / 1000.0}km (${radiusMeters} meters)"
        }
        val radiusRule = if (radiusDesc.isNotEmpty())
            "- ALL places MUST be physically located $radiusDesc of the given coordinates. Do NOT include anything further away."
        else ""
        return """
You are an expert local travel guide. Recommend real, existing $categoryLabel near $locationDesc${if (radiusDesc.isNotEmpty()) ", strictly $radiusDesc" else ""}.

Return ONLY valid JSON with NO additional text, markdown, or explanation:
{
  "recommendations": [
    {
      "name": "string - exact real place name",
      "description": "string - 2-3 sentences about the place",
      "address": "string or null - full street address if known",
      "latitude": number or null,
      "longitude": number or null,
      "confidenceScore": number between 0.0 and 1.0,
      "highlights": ["string", "string", "string"],
      "whyRecommended": "string - why this is a great choice"
    }
  ]
}

Rules:
- Provide 12-15 recommendations
- Only include real, verified existing places
$radiusRule
- Order by distance from the coordinates (closest first)
- Be specific about addresses and coordinates
- confidenceScore should reflect how certain you are this place exists and is relevant
""".trimIndent()
    }

    // ── AI provider dispatch ──────────────────────────────────────────────────

    private fun callAi(prompt: String, keys: Map<String, String>): Pair<String, String> {
        data class Provider(val name: String, val key: String?)
        val ordered = listOf(
            Provider("OpenRouter", keys["OpenRouter"]?.takeIf { it.isNotBlank() }),
            Provider("Gemini",     keys["Gemini"]?.takeIf { it.isNotBlank() }),
            Provider("OpenAI",     keys["OpenAI"]?.takeIf { it.isNotBlank() }),
            Provider("Anthropic",  keys["Anthropic"]?.takeIf { it.isNotBlank() }),
        )
        for (p in ordered) {
            if (p.key == null) continue
            Log.d("LocalEngine", "Using provider ${p.name}")
            val raw = when (p.name) {
                "OpenRouter" -> callOpenAiCompat(
                    prompt, p.key,
                    "https://openrouter.ai/api/v1/chat/completions",
                    keys["OpenRouterModel"] ?: "google/gemini-2.0-flash-001"
                )
                "Gemini"     -> callGemini(prompt, p.key, keys["GeminiModel"] ?: "gemini-2.0-flash")
                "OpenAI"     -> callOpenAiCompat(
                    prompt, p.key,
                    "https://api.openai.com/v1/chat/completions",
                    keys["OpenAIModel"] ?: "gpt-4o"
                )
                "Anthropic"  -> callAnthropic(prompt, p.key, keys["AnthropicModel"] ?: "claude-opus-4-5")
                else -> continue
            }
            return Pair(raw, p.name)
        }

        // AzureOpenAI last (needs endpoint too)
        val azureKey = keys["AzureOpenAI"]?.takeIf { it.isNotBlank() }
        val azureEndpoint = keys["AzureOpenAIEndpoint"]?.takeIf { it.isNotBlank() }
        if (azureKey != null && azureEndpoint != null) {
            val model = keys["AzureOpenAIModel"] ?: "gpt-4o"
            val url = "${azureEndpoint.trimEnd('/')}/openai/deployments/$model/chat/completions?api-version=2024-02-01"
            return Pair(callOpenAiCompat(prompt, azureKey, url, model, authHeader = "api-key"), "AzureOpenAI")
        }

        throw IllegalStateException("No AI API key configured. Please add at least one key in Settings.")
    }

    // ── Individual provider calls ─────────────────────────────────────────────

    private fun callOpenAiCompat(
        prompt: String,
        apiKey: String,
        url: String,
        model: String,
        authHeader: String = "Authorization"
    ): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
            put("max_tokens", 3000)
        }.toString()

        val headerKey = if (authHeader == "api-key") authHeader else "Authorization"
        val headerVal = if (authHeader == "api-key") apiKey else "Bearer $apiKey"

        val req = Request.Builder()
            .url(url)
            .header(headerKey, headerVal)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("${resp.code}: ${resp.body?.string()?.take(200)}")
            val r = resp.body?.string() ?: throw Exception("Empty response")
            JSONObject(r).getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    private fun callGemini(prompt: String, apiKey: String, model: String): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply { put("text", prompt) }))
            }))
            put("generationConfig", JSONObject().apply { put("maxOutputTokens", 3000) })
        }.toString()

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("${resp.code}: ${resp.body?.string()?.take(200)}")
            val r = resp.body?.string() ?: throw Exception("Empty response")
            JSONObject(r)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0)
                .getString("text")
        }
    }

    private fun callAnthropic(prompt: String, apiKey: String, model: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 3000)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }.toString()

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("${resp.code}: ${resp.body?.string()?.take(200)}")
            val r = resp.body?.string() ?: throw Exception("Empty response")
            JSONObject(r).getJSONArray("content").getJSONObject(0).getString("text")
        }
    }

    // ── Model listing ─────────────────────────────────────────────────────────

    private fun fetchOpenAiModels(apiKey: String?): ProviderModelsResponse {
        if (apiKey.isNullOrBlank()) return ProviderModelsResponse(emptyList(), "API key required")
        return try {
            val req = Request.Builder()
                .url("https://api.openai.com/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ProviderModelsResponse(emptyList(), "Error ${resp.code}")
                val arr = JSONObject(resp.body?.string() ?: "").optJSONArray("data") ?: return ProviderModelsResponse(emptyList(), null)
                val models = (0 until arr.length())
                    .map { arr.getJSONObject(it).getString("id") }
                    .filter { it.startsWith("gpt") }
                    .sorted()
                    .map { ProviderModel(it, it) }
                ProviderModelsResponse(models, null)
            }
        } catch (e: Exception) {
            ProviderModelsResponse(emptyList(), e.message)
        }
    }

    private fun fetchOpenRouterModels(apiKey: String?): ProviderModelsResponse {
        if (apiKey.isNullOrBlank()) return ProviderModelsResponse(emptyList(), "API key required")
        return try {
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ProviderModelsResponse(emptyList(), "Error ${resp.code}")
                val arr = JSONObject(resp.body?.string() ?: "").optJSONArray("data") ?: return ProviderModelsResponse(emptyList(), null)
                val models = (0 until arr.length()).map {
                    val obj = arr.getJSONObject(it)
                    ProviderModel(obj.getString("id"), obj.optString("name", obj.getString("id")))
                }
                ProviderModelsResponse(models, null)
            }
        } catch (e: Exception) {
            ProviderModelsResponse(emptyList(), e.message)
        }
    }

    private fun fetchGeminiModels(apiKey: String?): ProviderModelsResponse {
        if (apiKey.isNullOrBlank()) return ProviderModelsResponse(emptyList(), "API key required")
        return try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ProviderModelsResponse(emptyList(), "Error ${resp.code}")
                val arr = JSONObject(resp.body?.string() ?: "").optJSONArray("models") ?: return ProviderModelsResponse(emptyList(), null)
                val models = (0 until arr.length()).mapNotNull {
                    val obj = arr.getJSONObject(it)
                    val id = obj.optString("name").removePrefix("models/")
                    val display = obj.optString("displayName", id)
                    if (id.contains("gemini", ignoreCase = true)) ProviderModel(id, display) else null
                }
                ProviderModelsResponse(models, null)
            }
        } catch (e: Exception) {
            ProviderModelsResponse(emptyList(), e.message)
        }
    }

    private fun fetchAzureModels(apiKey: String?, endpoint: String?): ProviderModelsResponse {
        if (apiKey.isNullOrBlank() || endpoint.isNullOrBlank())
            return ProviderModelsResponse(emptyList(), "API key and endpoint required")
        return try {
            val url = "${endpoint.trimEnd('/')}/openai/deployments?api-version=2024-02-01"
            val req = Request.Builder().url(url).header("api-key", apiKey).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ProviderModelsResponse(emptyList(), "Error ${resp.code}")
                val arr = JSONObject(resp.body?.string() ?: "").optJSONArray("value") ?: return ProviderModelsResponse(emptyList(), null)
                val models = (0 until arr.length()).map {
                    val obj = arr.getJSONObject(it)
                    val id = obj.optString("id", obj.optString("model", "unknown"))
                    ProviderModel(id, id)
                }
                ProviderModelsResponse(models, null)
            }
        } catch (e: Exception) {
            ProviderModelsResponse(emptyList(), e.message)
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private fun parseRecommendations(raw: String, providerName: String, category: String): List<PlaceRecommendation> {
        val json = extractJson(raw)
        if (json.isBlank()) {
            Log.e("LocalEngine", "Could not extract JSON from response: ${raw.take(300)}")
            return emptyList()
        }
        return try {
            val arr = JSONObject(json).optJSONArray("recommendations") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val item = arr.getJSONObject(i)
                    val name = item.optString("name").trim().ifBlank { return@mapNotNull null }
                    val score = item.optDouble("confidenceScore", 0.7).coerceIn(0.0, 1.0)
                    PlaceRecommendation(
                        name = name,
                        description = item.optString("description"),
                        category = category,
                        confidenceScore = score,
                        confidenceLevel = scoreToLevel(score),
                        address = item.optString("address").ifBlank { null },
                        latitude = item.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() },
                        longitude = item.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() },
                        sourceProvider = providerName,
                        highlights = parseStringArray(item.optJSONArray("highlights")),
                        whyRecommended = item.optString("whyRecommended").ifBlank { null }
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            Log.e("LocalEngine", "Parse error: ${e.message}")
            emptyList()
        }
    }

    private fun extractJson(raw: String): String {
        // 1. markdown code block
        val codeBlock = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```").find(raw)
        if (codeBlock != null) return codeBlock.groupValues[1]
        // 2. find { containing "recommendations"
        val idx = raw.lastIndexOf("\"recommendations\"")
        if (idx >= 0) {
            val braceIdx = raw.lastIndexOf('{', idx)
            if (braceIdx >= 0) return raw.substring(braceIdx)
        }
        // 3. first {
        val start = raw.indexOfFirst { it == '{' }
        return if (start >= 0) raw.substring(start) else ""
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
    }

    private fun scoreToLevel(score: Double) = when {
        score >= 0.9 -> "VeryHigh"
        score >= 0.7 -> "High"
        score >= 0.4 -> "Medium"
        else -> "Low"
    }

    private fun formatCategoryLabel(categories: List<String>): String {
        val specific = categories.filter { it != "All" }
        if (specific.isEmpty()) return "interesting places"
        val labels = specific.map { c ->
            when (c) {
                "Restaurant" -> "restaurants"
                "Cafe" -> "cafes"
                "TouristAttraction" -> "tourist attractions"
                "Museum" -> "museums"
                "Park" -> "parks"
                "Bar" -> "bars"
                "Hotel" -> "hotels"
                "Shopping" -> "shopping venues"
                "Entertainment" -> "entertainment venues"
                else -> c.lowercase()
            }
        }
        return if (labels.size == 1) labels[0]
        else labels.dropLast(1).joinToString(", ") + " and " + labels.last()
    }
}
