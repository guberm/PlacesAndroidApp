package com.guberdev.places.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guberdev.places.data.LocalRecommendationEngine
import com.guberdev.places.data.api.GooglePlacesApi
import com.guberdev.places.data.api.GpCircle
import com.guberdev.places.data.api.GpLatLng
import com.guberdev.places.data.api.GpLocationBias
import com.guberdev.places.data.api.GpTextSearchRequest
import com.guberdev.places.data.model.AddressSuggestion
import com.guberdev.places.data.model.PlaceRecommendation
import com.guberdev.places.data.model.ProviderModelsResponse
import com.guberdev.places.data.model.RecommendationRequest
import com.guberdev.places.data.model.RecommendationResponse
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class PlacesUiState {
    object Initial : PlacesUiState()
    data class Loading(val slowWarning: Boolean = false) : PlacesUiState()
    data class Success(val response: RecommendationResponse) : PlacesUiState()
    data class Error(val message: String) : PlacesUiState()
}

class PlacesViewModel : ViewModel() {
    private val engine = LocalRecommendationEngine()
    private val googlePlacesApi = GooglePlacesApi.create()

    private val _uiState = MutableStateFlow<PlacesUiState>(PlacesUiState.Initial)
    val uiState: StateFlow<PlacesUiState> = _uiState.asStateFlow()

    private val _suggestions = MutableStateFlow<List<AddressSuggestion>>(emptyList())
    val suggestions: StateFlow<List<AddressSuggestion>> = _suggestions.asStateFlow()
    private var suggestionJob: Job? = null

    fun onSearchQueryChanged(query: String) {
        suggestionJob?.cancel()
        if (query.length < 3) { _suggestions.value = emptyList(); return }
        suggestionJob = viewModelScope.launch {
            delay(500) // debounce
            val results = withContext(Dispatchers.IO) {
                try { engine.searchAddress(query) } catch (e: Exception) { emptyList() }
            }
            _suggestions.value = results
        }
    }

    fun clearSuggestions() {
        suggestionJob?.cancel()
        _suggestions.value = emptyList()
    }

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
            _uiState.value = PlacesUiState.Loading()
            // After 15s without a response, show a slow-warning to the user
            val slowWarningJob = launch {
                delay(15_000)
                if (_uiState.value is PlacesUiState.Loading) {
                    _uiState.value = PlacesUiState.Loading(slowWarning = true)
                }
            }
            try {
                Log.d("PlacesVM", "searchPlaces START query=$query lat=$lat lng=$lng category=$category radius=$radiusMeters max=$maxResults")
                val startTime = System.currentTimeMillis()
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
                Log.d("PlacesVM", "engine.recommend sending request: $request")
                val response = engine.recommend(request, userApiKeys ?: emptyMap())
                Log.d("PlacesVM", "engine.recommend OK in ${System.currentTimeMillis() - startTime}ms — ${response.recommendations.size} results")

                val googleApiKey = userApiKeys?.get("GooglePlaces")?.takeIf { it.isNotBlank() }
                val enriched = if (googleApiKey != null) {
                    enrichWithGoogleRatings(response, googleApiKey)
                } else {
                    response
                }

                // Hard-filter: remove places whose coordinates fall outside the requested radius.
                val filtered = if (radiusMeters > 0) {
                    val distResults = FloatArray(1)
                    val withDist = enriched.recommendations.map { place ->
                        if (place.latitude != null && place.longitude != null) {
                            android.location.Location.distanceBetween(
                                enriched.latitude, enriched.longitude,
                                place.latitude, place.longitude,
                                distResults
                            )
                            Pair(place, distResults[0])
                        } else Pair(place, 0f)
                    }
                    val within = withDist.filter { (place, distM) ->
                        val keep = place.latitude == null || distM <= radiusMeters * 1.1f
                        if (!keep) Log.d("PlacesVM", "Filtered out '${place.name}' at ${distM.toInt()}m (radius=${radiusMeters}m)")
                        keep
                    }.map { it.first }

                    if (within.isEmpty() && withDist.any { it.first.latitude != null }) {
                        // AI returned nothing nearby — fall back to the closest maxResults, sorted by distance
                        Log.w("PlacesVM", "Radius filter removed all results — falling back to closest $maxResults")
                        val closest = withDist
                            .filter { it.first.latitude != null }
                            .sortedBy { it.second }
                            .take(maxResults)
                            .map { it.first }
                        enriched.copy(recommendations = closest)
                    } else {
                        enriched.copy(recommendations = within)
                    }
                } else enriched

                Log.d("PlacesVM", "searchPlaces DONE in ${System.currentTimeMillis() - startTime}ms — ${filtered.recommendations.size} results after radius filter")

                // Geocode place addresses to get accurate coordinates for distance display
                val geocoded = geocodeAddresses(filtered)

                slowWarningJob.cancel()
                _uiState.value = PlacesUiState.Success(geocoded)
            } catch (e: Exception) {
                Log.e("PlacesVM", "searchPlaces ERROR: ${e.javaClass.simpleName} — ${e.message}", e)
                slowWarningJob.cancel()
                _uiState.value = PlacesUiState.Error(e.message ?: "An unknown error occurred")
            }
        }
    }

    private suspend fun geocodeAddresses(
        response: RecommendationResponse
    ): RecommendationResponse {
        // Photon (komoot.io) — no strict per-second limit; small delay to stay polite.
        val DELAY_MS = 200L
        var needDelay = false
        val updated = response.recommendations.map { place ->
            try {
                when {
                    // Has address — always geocode via Nominatim for accurate coordinates.
                    // AI-provided coordinates are frequently hallucinated; real address geocoding
                    // is the only reliable source for correct distance calculation.
                    !place.address.isNullOrBlank() -> {
                        if (needDelay) delay(DELAY_MS) else needDelay = true
                        val coords = engine.geocode("${place.name}, ${place.address}")
                            ?: engine.geocode(place.address)
                        if (coords != null) {
                            Log.d("PlacesVM", "OSM geocoded '${place.name}' → ${coords.first},${coords.second}")
                            place.copy(latitude = coords.first, longitude = coords.second)
                        } else {
                            // Nominatim failed — fall back to AI coordinates if available
                            Log.w("PlacesVM", "OSM geocode failed for '${place.name}', keeping AI coords")
                            place
                        }
                    }

                    // Has only coordinates — reverse-geocode for address display
                    place.latitude != null && place.longitude != null -> {
                        if (needDelay) delay(DELAY_MS) else needDelay = true
                        val addr = engine.reverseGeocodeAddress(place.latitude, place.longitude)
                        if (addr != null) {
                            Log.d("PlacesVM", "OSM reverse-geocoded '${place.name}' → $addr")
                            place.copy(address = addr)
                        } else place
                    }

                    else -> place
                }
            } catch (e: Exception) {
                Log.w("PlacesVM", "OSM geocode FAILED for '${place.name}': ${e.message}")
                place
            }
        }
        return response.copy(recommendations = updated)
    }

    suspend fun reverseGeocode(lat: Double, lng: Double): String? =
        withContext(Dispatchers.IO) { engine.reverseGeocode(lat, lng) }

    /** Full street-level address for display in the search field. Retries once on failure. */
    suspend fun reverseGeocodeFullAddress(lat: Double, lng: Double): String? =
        withContext(Dispatchers.IO) {
            engine.reverseGeocodeAddress(lat, lng)
                ?: run {
                    delay(1200)
                    engine.reverseGeocodeAddress(lat, lng)
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
                    Log.w("PlacesVM", "enrichWithGoogleRatings FAILED for '${place.name}': ${e.javaClass.simpleName} — ${e.message}")
                    place
                }
            }
        }.awaitAll()
        response.copy(recommendations = enriched)
    }

    suspend fun getModels(provider: String, apiKey: String?, endpoint: String?): ProviderModelsResponse {
        return engine.getModels(provider, apiKey, endpoint)
    }
}
