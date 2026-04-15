package com.guberdev.places.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guberdev.places.data.api.GooglePlacesApi
import com.guberdev.places.data.api.GpCircle
import com.guberdev.places.data.api.GpLatLng
import com.guberdev.places.data.api.GpLocationBias
import com.guberdev.places.data.api.GpTextSearchRequest
import com.guberdev.places.data.api.PlacesApi
import com.guberdev.places.data.model.PlaceRecommendation
import com.guberdev.places.data.model.ProviderModelsResponse
import com.guberdev.places.data.model.RecommendationRequest
import com.guberdev.places.data.model.RecommendationResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PlacesUiState {
    object Initial : PlacesUiState()
    object Loading : PlacesUiState()
    data class Success(val response: RecommendationResponse) : PlacesUiState()
    data class Error(val message: String) : PlacesUiState()
}

class PlacesViewModel : ViewModel() {
    private val api = PlacesApi.create()
    private val googlePlacesApi = GooglePlacesApi.create()

    private val _uiState = MutableStateFlow<PlacesUiState>(PlacesUiState.Initial)
    val uiState: StateFlow<PlacesUiState> = _uiState.asStateFlow()

    fun searchPlaces(
        query: String?,
        lat: Double?,
        lng: Double?,
        category: String,
        radiusMeters: Int = 1000,
        maxResults: Int = 10,
        forceRefresh: Boolean = false,
        userApiKeys: Map<String, String>? = null
    ) {
        viewModelScope.launch {
            _uiState.value = PlacesUiState.Loading
            try {
                var finalLat = lat
                var finalLng = lng
                var finalAddr = query?.takeIf { it.isNotBlank() }

                if (lat == null && lng == null && query != null) {
                    val split = query.split(",")
                    if (split.size == 2) {
                        val parsedLat = split[0].trim().toDoubleOrNull()
                        val parsedLng = split[1].trim().toDoubleOrNull()
                        if (parsedLat != null && parsedLng != null) {
                            finalLat = parsedLat
                            finalLng = parsedLng
                            finalAddr = null
                        }
                    }
                }

                val request = RecommendationRequest(
                    address = finalAddr,
                    latitude = finalLat,
                    longitude = finalLng,
                    categories = listOf(category),
                    maxResults = maxResults,
                    radiusMeters = radiusMeters,
                    forceRefresh = forceRefresh,
                    userApiKeys = userApiKeys
                )
                val response = api.getRecommendations(request)

                val googleApiKey = userApiKeys?.get("GooglePlaces")?.takeIf { it.isNotBlank() }
                val enriched = if (googleApiKey != null) {
                    enrichWithGoogleRatings(response, googleApiKey)
                } else {
                    response
                }

                _uiState.value = PlacesUiState.Success(enriched)
            } catch (e: Exception) {
                _uiState.value = PlacesUiState.Error(e.message ?: "An unknown error occurred")
            }
        }
    }

    private suspend fun enrichWithGoogleRatings(
        response: RecommendationResponse,
        apiKey: String
    ): RecommendationResponse = coroutineScope {
        val enriched = response.recommendations.map { place ->
            async {
                try {
                    val bias = if (place.latitude != null && place.longitude != null) {
                        GpLocationBias(GpCircle(GpLatLng(place.latitude, place.longitude), 200.0))
                    } else null
                    val result = googlePlacesApi.searchText(
                        apiKey = apiKey,
                        fieldMask = "places.id,places.rating,places.userRatingCount,places.websiteUri",
                        request = GpTextSearchRequest(
                            textQuery = place.name,
                            locationBias = bias,
                            maxResultCount = 1
                        )
                    )
                    val gp = result.places?.firstOrNull()
                    if (gp != null && (gp.rating != null || gp.websiteUri != null)) {
                        place.copy(
                            rating = gp.rating ?: place.rating,
                            userRatingsTotal = gp.userRatingCount ?: place.userRatingsTotal,
                            websiteUri = gp.websiteUri ?: place.websiteUri
                        )
                    } else place
                } catch (e: Exception) {
                    place
                }
            }
        }.awaitAll()
        response.copy(recommendations = enriched)
    }

    suspend fun getModels(provider: String, apiKey: String?, endpoint: String?): ProviderModelsResponse {
        return api.getProviderModels(provider, apiKey, endpoint)
    }
}
