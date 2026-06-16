package com.guberdev.places.data

import android.util.Log
import com.guberdev.places.data.model.AddressSuggestion
import com.guberdev.places.data.model.PlaceRecommendation
import com.guberdev.places.data.model.RecommendationRequest
import com.guberdev.places.data.model.RecommendationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class LocalRecommendationEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun recommend(request: RecommendationRequest): RecommendationResponse = withContext(Dispatchers.IO) {
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

        if (lat == 0.0 && lng == 0.0) {
            throw IllegalStateException("Pick a location first.")
        }

        val callerHasRealAddress = !resolvedAddress.isNullOrBlank()
            && resolvedAddress != "Current Location"
            && resolvedAddress != "My Location"
            && resolvedAddress != "Locating…"
            && !resolvedAddress.matches(Regex("""-?\d+\.\d+,\s*-?\d+\.\d+"""))
        if (!callerHasRealAddress && (lat != 0.0 || lng != 0.0)) {
            val city = reverseGeocode(lat, lng)
            if (city != null) {
                resolvedAddress = city
                Log.d("LocalEngine", "Reverse-geocoded ($lat,$lng) → $city")
            } else {
                resolvedAddress = "${"%.5f".format(lat)}, ${"%.5f".format(lng)}"
                Log.w("LocalEngine", "reverseGeocode failed — using raw coords as location context")
            }
        }

        val category = request.categories.firstOrNull() ?: "All"
        val places = searchOsmPlaces(lat, lng, request.radiusMeters, request.maxResults, category, resolvedAddress)

        RecommendationResponse(
            latitude = lat,
            longitude = lng,
            resolvedAddress = resolvedAddress,
            category = category,
            categories = request.categories,
            recommendations = places,
            fromCache = false
        )
    }

    private fun searchOsmPlaces(
        lat: Double,
        lng: Double,
        radiusMeters: Int,
        maxResults: Int,
        category: String,
        locationText: String?
    ): List<PlaceRecommendation> {
        val filters = osmFilters(category)
        val selectors = filters.joinToString("\n") { filter ->
            "nwr$filter(around:$radiusMeters,$lat,$lng);"
        }
        val query = """
            [out:json][timeout:25];
            (
            $selectors
            );
            out center tags qt ${maxResults * 4};
        """.trimIndent()
        val body = "data=${URLEncoder.encode(query, "UTF-8")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val json = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        ).firstNotNullOfOrNull { url ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PlacesApp/1.0 (Android)")
                    .header("Accept", "application/json")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else JSONObject(resp.body?.string() ?: "{}")
                }
            } catch (e: Exception) {
                Log.w("LocalEngine", "Overpass failed at $url: ${e.message}")
                null
            }
        } ?: return searchPhotonPlaces(lat, lng, radiusMeters, maxResults, category, locationText)
        val elements = json.optJSONArray("elements") ?: return searchPhotonPlaces(lat, lng, radiusMeters, maxResults, category, locationText)
        val places = (0 until elements.length()).mapNotNull { i ->
            val item = elements.optJSONObject(i) ?: return@mapNotNull null
            val tags = item.optJSONObject("tags") ?: return@mapNotNull null
            val name = tags.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val itemLat = item.optDouble("lat", Double.NaN)
                .takeIf { !it.isNaN() }
                ?: item.optJSONObject("center")?.optDouble("lat", Double.NaN)?.takeIf { !it.isNaN() }
                ?: return@mapNotNull null
            val itemLng = item.optDouble("lon", Double.NaN)
                .takeIf { !it.isNaN() }
                ?: item.optJSONObject("center")?.optDouble("lon", Double.NaN)?.takeIf { !it.isNaN() }
                ?: return@mapNotNull null
            val distance = FloatArray(1).also {
                android.location.Location.distanceBetween(lat, lng, itemLat, itemLng, it)
            }[0]
            placeFromOsm(name, tags, itemLat, itemLng, category) to distance
        }
        return places
            .distinctBy { it.first.name.lowercase() to it.first.address.orEmpty().lowercase() }
            .sortedBy { it.second }
            .take(maxResults)
            .map { it.first }
            .ifEmpty { searchPhotonPlaces(lat, lng, radiusMeters, maxResults, category, locationText) }
    }

    private fun osmFilters(category: String): List<String> = when (category) {
        "Restaurant" -> listOf("""["amenity"="restaurant"]""")
        "Cafe" -> listOf("""["amenity"="cafe"]""")
        "TouristAttraction" -> listOf("""["tourism"~"attraction|viewpoint|theme_park|zoo|aquarium"]""")
        "Museum" -> listOf("""["tourism"="museum"]""")
        "Park" -> listOf("""["leisure"="park"]""", """["leisure"="garden"]""")
        "Bar" -> listOf("""["amenity"~"bar|pub"]""")
        "Hotel" -> listOf("""["tourism"~"hotel|hostel|guest_house|motel"]""")
        "Shopping" -> listOf("""["shop"]""")
        "Entertainment" -> listOf("""["amenity"~"cinema|theatre|arts_centre|nightclub"]""", """["leisure"~"bowling_alley|sports_centre|escape_game"]""")
        else -> listOf(
            """["amenity"~"restaurant|cafe|bar|pub|cinema|theatre|arts_centre"]""",
            """["tourism"~"attraction|museum|viewpoint|hotel|hostel|guest_house|motel"]""",
            """["leisure"~"park|garden|sports_centre"]""",
            """["shop"]"""
        )
    }

    private fun placeFromOsm(
        name: String,
        tags: JSONObject,
        lat: Double,
        lng: Double,
        category: String
    ): PlaceRecommendation {
        val type = listOf("amenity", "tourism", "leisure", "shop").firstNotNullOfOrNull {
            tags.optString(it).takeIf { value -> value.isNotBlank() }
        }?.replace('_', ' ') ?: category
        val website = tags.optString("website").takeIf { it.isNotBlank() }
            ?: tags.optString("contact:website").takeIf { it.isNotBlank() }
        val address = osmAddress(tags)
        val highlights = listOfNotNull(
            type.replaceFirstChar { it.uppercase() },
            tags.optString("cuisine").takeIf { it.isNotBlank() }?.replace('_', ' '),
            tags.optString("opening_hours").takeIf { it.isNotBlank() }
        )
        return PlaceRecommendation(
            name = name,
            description = "Found in OpenStreetMap as $type${address?.let { " at $it" } ?: ""}.",
            category = category,
            confidenceScore = 0.85,
            confidenceLevel = "High",
            address = address,
            latitude = lat,
            longitude = lng,
            sourceProvider = "OpenStreetMap",
            highlights = highlights,
            whyRecommended = null,
            websiteUri = website,
            coordsVerified = true
        )
    }

    private fun osmAddress(tags: JSONObject): String? {
        val street = listOfNotNull(
            tags.optString("addr:housenumber").takeIf { it.isNotBlank() },
            tags.optString("addr:street").takeIf { it.isNotBlank() }
        ).joinToString(" ").takeIf { it.isNotBlank() }
        return listOfNotNull(
            street,
            tags.optString("addr:city").takeIf { it.isNotBlank() },
            tags.optString("addr:state").takeIf { it.isNotBlank() },
            tags.optString("addr:country").takeIf { it.isNotBlank() }
        ).joinToString(", ").takeIf { it.isNotBlank() }
    }

    private fun searchPhotonPlaces(
        lat: Double,
        lng: Double,
        radiusMeters: Int,
        maxResults: Int,
        category: String,
        locationText: String?
    ): List<PlaceRecommendation> {
        val query = URLEncoder.encode("${photonQuery(category)} ${locationText.orEmpty()}".trim(), "UTF-8")
        val req = Request.Builder()
            .url("https://photon.komoot.io/api/?q=$query&limit=${maxResults * 3}&lat=$lat&lon=$lng&lang=en")
            .header("User-Agent", "PlacesApp/1.0 (Android)")
            .build()
        val body = client.newCall(req).execute().use { it.body?.string() } ?: return emptyList()
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val feature = features.optJSONObject(i) ?: return@mapNotNull null
            val props = feature.optJSONObject("properties") ?: return@mapNotNull null
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val itemLng = coords.optDouble(0, Double.NaN)
            val itemLat = coords.optDouble(1, Double.NaN)
            val name = props.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (itemLat.isNaN() || itemLng.isNaN()) return@mapNotNull null
            val distance = FloatArray(1).also {
                android.location.Location.distanceBetween(lat, lng, itemLat, itemLng, it)
            }[0]
            if (radiusMeters > 0 && distance > radiusMeters * 1.2f) return@mapNotNull null
            placeFromPhoton(name, props, itemLat, itemLng, category) to distance
        }
            .distinctBy { it.first.name.lowercase() to it.first.address.orEmpty().lowercase() }
            .sortedBy { it.second }
            .take(maxResults)
            .map { it.first }
    }

    private fun photonQuery(category: String): String = when (category) {
        "Restaurant" -> "restaurant"
        "Cafe" -> "cafe"
        "TouristAttraction" -> "tourist attraction"
        "Museum" -> "museum"
        "Park" -> "park"
        "Bar" -> "bar pub"
        "Hotel" -> "hotel"
        "Shopping" -> "shop"
        "Entertainment" -> "cinema theatre"
        else -> "restaurant cafe park museum hotel shop"
    }

    private fun placeFromPhoton(
        name: String,
        props: JSONObject,
        lat: Double,
        lng: Double,
        category: String
    ): PlaceRecommendation {
        val type = props.optString("osm_value").takeIf { it.isNotBlank() }?.replace('_', ' ') ?: category
        val address = listOfNotNull(
            listOfNotNull(
                props.optString("housenumber").takeIf { it.isNotBlank() },
                props.optString("street").takeIf { it.isNotBlank() }
            ).joinToString(" ").takeIf { it.isNotBlank() },
            props.optString("city").takeIf { it.isNotBlank() },
            props.optString("state").takeIf { it.isNotBlank() },
            props.optString("country").takeIf { it.isNotBlank() }
        ).joinToString(", ").takeIf { it.isNotBlank() }
        return PlaceRecommendation(
            name = name,
            description = "Found in OpenStreetMap as $type${address?.let { " at $it" } ?: ""}.",
            category = category,
            confidenceScore = 0.75,
            confidenceLevel = "High",
            address = address,
            latitude = lat,
            longitude = lng,
            sourceProvider = "OpenStreetMap",
            highlights = listOf(type.replaceFirstChar { it.uppercase() }),
            whyRecommended = null,
            coordsVerified = true
        )
    }

    // ── Geocoding ─────────────────────────────────────────────────────────────

    internal fun geocode(address: String): Pair<Double, Double>? {
        return try {
            val encoded = URLEncoder.encode(address, "UTF-8")
            val req = Request.Builder()
                .url("https://photon.komoot.io/api/?q=$encoded&limit=1&lang=en")
                .header("User-Agent", "PlacesApp/1.0 (Android)")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return null
            val feature = JSONObject(body).optJSONArray("features")?.optJSONObject(0) ?: return null
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) null else Pair(lat, lon)
        } catch (e: Exception) {
            Log.e("LocalEngine", "Geocoding failed: ${e.message}")
            null
        }
    }

    // ── Reverse geocoding ─────────────────────────────────────────────────────

    /** Returns "Neighbourhood, City, State, Country" — richer context for the AI prompt. */
    internal fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            val req = Request.Builder()
                .url("https://photon.komoot.io/reverse?lat=$lat&lon=$lng")
                .header("User-Agent", "PlacesApp/1.0 (Android)")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return null
            val props = JSONObject(body).optJSONArray("features")?.optJSONObject(0)
                ?.optJSONObject("properties") ?: return null
            val neighbourhood = props.optString("neighbourhood").takeIf { it.isNotBlank() }
                ?: props.optString("suburb").takeIf { it.isNotBlank() }
                ?: props.optString("quarter").takeIf { it.isNotBlank() }
            val city = props.optString("city").takeIf { it.isNotBlank() }
                ?: props.optString("town").takeIf { it.isNotBlank() }
                ?: props.optString("village").takeIf { it.isNotBlank() }
                ?: props.optString("municipality").takeIf { it.isNotBlank() }
            val state   = props.optString("state").takeIf { it.isNotBlank() }
            val country = props.optString("country").takeIf { it.isNotBlank() }
            listOfNotNull(neighbourhood, city, state, country).joinToString(", ").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e("LocalEngine", "Reverse geocoding failed: ${e.message}")
            null
        }
    }

    /** Address autocomplete via Photon — returns up to 5 suggestions for a partial query string. */
    fun searchAddress(query: String): List<AddressSuggestion> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://photon.komoot.io/api/?q=$encoded&limit=5&lang=en")
                .header("User-Agent", "PlacesApp/1.0 (Android)")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return emptyList()
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            (0 until features.length()).mapNotNull { i ->
                try {
                    val feature = features.getJSONObject(i)
                    val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                        ?: return@mapNotNull null
                    val lon = coords.optDouble(0, Double.NaN)
                    val lat = coords.optDouble(1, Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) return@mapNotNull null

                    val props  = feature.optJSONObject("properties") ?: return@mapNotNull null
                    val house  = props.optString("housenumber").takeIf { it.isNotBlank() }
                    val street = props.optString("street").takeIf { it.isNotBlank() }
                    val name   = props.optString("name").takeIf { it.isNotBlank() }
                    val city   = props.optString("city").takeIf { it.isNotBlank() }
                        ?: props.optString("town").takeIf { it.isNotBlank() }
                        ?: props.optString("village").takeIf { it.isNotBlank() }
                    val state   = props.optString("state").takeIf { it.isNotBlank() }
                    val country = props.optString("country").takeIf { it.isNotBlank() }

                    val streetAddr  = listOfNotNull(house, street).joinToString(" ").takeIf { it.isNotBlank() }
                    val primary     = streetAddr ?: name
                    val shortLine   = listOfNotNull(primary, city).joinToString(", ").ifBlank { name ?: "" }
                    val secondLine  = listOfNotNull(state, country).joinToString(", ")
                    val displayName = listOfNotNull(primary, city, state, country).joinToString(", ")

                    if (displayName.isBlank()) return@mapNotNull null
                    AddressSuggestion(
                        displayName = displayName,
                        shortLine   = shortLine,
                        secondLine  = secondLine,
                        latitude    = lat,
                        longitude   = lon
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            Log.e("LocalEngine", "searchAddress failed: ${e.message}")
            emptyList()
        }
    }

    /** Returns full street address via Photon — accurate house number + street. */
    internal fun reverseGeocodeAddress(lat: Double, lng: Double): String? {
        return try {
            val req = Request.Builder()
                .url("https://photon.komoot.io/reverse?lat=$lat&lon=$lng")
                .header("User-Agent", "PlacesApp/1.0 (Android)")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return null
            val props = JSONObject(body).optJSONArray("features")?.optJSONObject(0)
                ?.optJSONObject("properties") ?: return null

            val house  = props.optString("housenumber").takeIf { it.isNotBlank() }
            val street = props.optString("street").takeIf { it.isNotBlank() }
            val name   = props.optString("name").takeIf { it.isNotBlank() }
            val neighbourhood = props.optString("neighbourhood").takeIf { it.isNotBlank() }
                ?: props.optString("suburb").takeIf { it.isNotBlank() }
            val city   = props.optString("city").takeIf { it.isNotBlank() }
                ?: props.optString("town").takeIf { it.isNotBlank() }
                ?: props.optString("village").takeIf { it.isNotBlank() }
            val state   = props.optString("state").takeIf { it.isNotBlank() }
            val country = props.optString("country").takeIf { it.isNotBlank() }

            val streetAddr = listOfNotNull(house, street).joinToString(" ").takeIf { it.isNotBlank() }

            // With house number → "540 Stream Cres, Oakville, Ontario, Canada"
            // Without → include neighbourhood for context
            val parts = if (house != null) {
                listOfNotNull(streetAddr, city, state, country)
            } else {
                listOfNotNull(streetAddr ?: name, neighbourhood, city, state, country)
            }
            parts.joinToString(", ").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e("LocalEngine", "reverseGeocodeAddress failed: ${e.message}")
            null
        }
    }

}
