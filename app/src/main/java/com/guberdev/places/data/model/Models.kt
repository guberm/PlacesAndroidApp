package com.guberdev.places.data.model

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
