package com.guberdev.places.data.api

import com.guberdev.places.data.model.ProviderModelsResponse
import com.guberdev.places.data.model.RecommendationRequest
import com.guberdev.places.data.model.RecommendationResponse
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface PlacesApi {
    @POST("recommendations")
    suspend fun getRecommendations(@Body request: RecommendationRequest): RecommendationResponse

    @GET("providers/models")
    suspend fun getProviderModels(
        @Query("provider") provider: String,
        @Query("apiKey") apiKey: String?,
        @Query("endpoint") endpoint: String? = null
    ): ProviderModelsResponse

    companion object {
        private const val BASE_URL = "https://places.guber.dev/api/"

        fun create(): PlacesApi {
            val logger = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
            val client = OkHttpClient.Builder()
                .addInterceptor(logger)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(PlacesApi::class.java)
        }
    }
}
