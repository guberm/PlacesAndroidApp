package com.guberdev.places.data.api

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// ── Request ──────────────────────────────────────────────────────────────────

data class GpTextSearchRequest(
    val textQuery: String,
    val locationBias: GpLocationBias? = null,
    val maxResultCount: Int = 1
)

data class GpLocationBias(val circle: GpCircle)
data class GpCircle(val center: GpLatLng, val radius: Double = 200.0)
data class GpLatLng(val latitude: Double, val longitude: Double)

// ── Response ─────────────────────────────────────────────────────────────────

data class GpTextSearchResponse(
    val places: List<GpPlace>? = null
)

data class GpPlace(
    val id: String? = null,
    val rating: Double? = null,
    @SerializedName("userRatingCount") val userRatingCount: Int? = null,
    val websiteUri: String? = null,
    val displayName: GpDisplayName? = null
)

data class GpDisplayName(val text: String? = null)

// ── Interface ─────────────────────────────────────────────────────────────────

interface GooglePlacesApi {
    @POST("v1/places:searchText")
    suspend fun searchText(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Header("X-Goog-FieldMask") fieldMask: String = "places.id,places.rating,places.userRatingCount,places.websiteUri,places.displayName",
        @Body request: GpTextSearchRequest
    ): GpTextSearchResponse

    companion object {
        fun create(): GooglePlacesApi {
            val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE }
            val client = OkHttpClient.Builder()
                .addInterceptor(logger)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl("https://places.googleapis.com/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GooglePlacesApi::class.java)
        }
    }
}
