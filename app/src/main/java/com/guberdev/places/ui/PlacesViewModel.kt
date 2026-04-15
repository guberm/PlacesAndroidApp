package com.guberdev.places.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guberdev.places.data.api.PlacesApi
import com.guberdev.places.data.model.ProviderModelsResponse
import com.guberdev.places.data.model.RecommendationRequest
import com.guberdev.places.data.model.RecommendationResponse
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
                // Determine if query is coordinates "lat, lng"
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
                _uiState.value = PlacesUiState.Success(response)
            } catch (e: Exception) {
                _uiState.value = PlacesUiState.Error(e.message ?: "An unknown error occurred")
            }
        }
    }

    suspend fun getModels(provider: String, apiKey: String?, endpoint: String?): ProviderModelsResponse {
        return api.getProviderModels(provider, apiKey, endpoint)
    }
}
