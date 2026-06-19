package com.guberdev.places.data.model

internal fun normalizeWebsiteUri(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (trimmed.equals("null", ignoreCase = true) || trimmed.equals("none found", ignoreCase = true)) return null
    if (":" in trimmed && "://" !in trimmed) return null
    val url = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    return url.takeIf { uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() }
}

data class RecommendationRequest(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val categories: List<String> = listOf("All"),
    val subcategory: String? = null,
    val maxResults: Int = 10,
    val radiusMeters: Int = 1000,
    val forceRefresh: Boolean = false
)

data class RecommendationResponse(
    val latitude: Double,
    val longitude: Double,
    val resolvedAddress: String?,
    val category: String,
    val categories: List<String>,
    val recommendations: List<PlaceRecommendation>,
    val fromCache: Boolean
)

data class PlaceRecommendation(
    val name: String,
    val description: String,
    val category: String,
    val confidenceScore: Double,
    val confidenceLevel: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val sourceProvider: String,
    val highlights: List<String>,
    val whyRecommended: String?,
    val rating: Double? = null,
    val userRatingsTotal: Int? = null,
    val phoneNumber: String? = null,
    val websiteUri: String? = null,
    val coordsVerified: Boolean = false
)

data class AddressSuggestion(
    val displayName: String,   // used to fill the search field on selection
    val shortLine: String,     // e.g. "540 Stream Cres, Oakville"
    val secondLine: String,    // e.g. "Ontario, Canada"
    val latitude: Double,
    val longitude: Double
)
