package com.guberdev.places.data

import android.util.Log
import com.guberdev.places.BuildConfig
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
        val subcategory = request.subcategory?.takeIf { it.isNotBlank() }
        val places = searchPlacesApi(lat, lng, request.maxResults, category, subcategory, resolvedAddress)
            .ifEmpty { searchOsmPlaces(lat, lng, request.radiusMeters, request.maxResults, category, subcategory, resolvedAddress) }

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

    private fun searchPlacesApi(
        lat: Double,
        lng: Double,
        maxResults: Int,
        category: String,
        subcategory: String?,
        locationText: String?
    ): List<PlaceRecommendation> {
        val apiKey = BuildConfig.PLACES_API_KEY.takeIf { it.isNotBlank() } ?: return emptyList()
        val location = locationText?.takeIf { it.isNotBlank() } ?: "$lat,$lng"
        val query = "${placesApiQuery(category, subcategory)} $location".trim()
        val payload = JSONObject()
            .put("query", query)
            .put("total", maxResults.coerceIn(1, 20))
            .toString()
            .toRequestBody("application/json".toMediaType())
        return try {
            val req = Request.Builder()
                .url("https://places.guber.dev/api/scrape/sync")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload)
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("LocalEngine", "Places API failed: HTTP ${resp.code}")
                    return emptyList()
                }
                resp.body?.string()
            } ?: return emptyList()
            val rows = JSONObject(body).optJSONArray("result") ?: return emptyList()
            (0 until rows.length()).mapNotNull { i ->
                placeFromPlacesApi(rows.optJSONObject(i) ?: return@mapNotNull null, category)
            }
        } catch (e: Exception) {
            Log.w("LocalEngine", "Places API failed: ${e.message}")
            emptyList()
        }
    }

    private fun placesApiQuery(category: String, subcategory: String?): String {
        val base = when (category) {
            "Restaurant" -> "restaurant"
            "TouristAttraction" -> "tourist attraction"
            "Entertainment" -> "cinema theatre"
            "Shopping" -> "shopping"
            "All" -> "places"
            else -> category.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase()
        }
        val prefix = subcategory?.let { subcategoryQuery(category, it) }
        return listOfNotNull(prefix, base).joinToString(" ")
    }

    private fun subcategoryQuery(category: String, subcategory: String): String = when (category) {
        "TouristAttraction" -> when (subcategory) {
            "Kids" -> "family friendly kids"
            "Sport" -> "sports"
            "Viewpoints" -> "scenic viewpoints"
            else -> subcategory.lowercase()
        }
        "Cafe" -> when (subcategory) {
            "Work Friendly" -> "work friendly"
            else -> subcategory.lowercase()
        }
        "Hotel" -> when (subcategory) {
            "Pet Friendly" -> "pet friendly"
            else -> subcategory.lowercase()
        }
        else -> subcategory.lowercase()
    }

    private fun placeFromPlacesApi(row: JSONObject, category: String): PlaceRecommendation? {
        val name = row.optString("name").takeIf { it.isNotBlank() } ?: return null
        val type = row.optString("place_type").takeIf { it.isNotBlank() } ?: category
        val address = row.optString("address").takeIf { it.isNotBlank() }
        val phone = row.optString("phone_number").takeIf { it.isNotBlank() }
        val website = row.optString("website").takeIf { it.isNotBlank() }
        val rating = row.optDouble("rating", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
        val reviews = row.optInt("reviews_count", -1).takeIf { it >= 0 }
        val open = row.optString("opens_at").takeIf { it.isNotBlank() && it != "..." }
        val highlights = listOfNotNull(
            type,
            open?.let { "Opens: $it" },
            row.optString("store_delivery").takeIf { it == "Yes" }?.let { "Delivery" },
            row.optString("in_store_pickup").takeIf { it == "Yes" }?.let { "Pickup" }
        )
        return PlaceRecommendation(
            name = name,
            description = row.optString("introduction")
                .takeIf { it.isNotBlank() && it != "None Found" }
                ?: "Found in Google Maps as $type${address?.let { " at $it" } ?: ""}.",
            category = category,
            confidenceScore = 0.95,
            confidenceLevel = "High",
            address = address,
            latitude = null,
            longitude = null,
            sourceProvider = "Google Maps",
            highlights = highlights,
            whyRecommended = null,
            rating = rating,
            userRatingsTotal = reviews,
            phoneNumber = phone,
            websiteUri = website,
            coordsVerified = false
        )
    }

    private fun searchOsmPlaces(
        lat: Double,
        lng: Double,
        radiusMeters: Int,
        maxResults: Int,
        category: String,
        subcategory: String?,
        locationText: String?
    ): List<PlaceRecommendation> {
        val filters = osmFilters(category, subcategory)
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
        } ?: return searchPhotonPlaces(lat, lng, radiusMeters, maxResults, category, subcategory, locationText)
        val elements = json.optJSONArray("elements") ?: return searchPhotonPlaces(lat, lng, radiusMeters, maxResults, category, subcategory, locationText)
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
            .ifEmpty { searchPhotonPlaces(lat, lng, radiusMeters, maxResults, category, subcategory, locationText) }
    }

    private fun osmFilters(category: String, subcategory: String?): List<String> = when (category) {
        "Restaurant" -> subcategory?.let { osmRestaurantFilters(it) } ?: listOf("""["amenity"="restaurant"]""")
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

    private fun osmRestaurantFilters(type: String): List<String> = when (type) {
        "Fast Food" -> listOf("""["amenity"~"restaurant|fast_food"]""")
        "Pizza" -> listOf("""["amenity"~"restaurant|fast_food"]["cuisine"~"pizza",i]""")
        "Sushi" -> listOf("""["amenity"="restaurant"]["cuisine"~"sushi|japanese",i]""")
        "Japanese" -> listOf("""["amenity"="restaurant"]["cuisine"~"japanese",i]""")
        "Burgers" -> listOf("""["amenity"~"restaurant|fast_food"]["cuisine"~"burger|american",i]""")
        "Steakhouse" -> listOf("""["amenity"="restaurant"]["cuisine"~"steak|steak_house",i]""")
        "Seafood" -> listOf("""["amenity"="restaurant"]["cuisine"~"seafood",i]""")
        "Chinese" -> listOf("""["amenity"="restaurant"]["cuisine"~"chinese",i]""")
        "Indian" -> listOf("""["amenity"="restaurant"]["cuisine"~"indian",i]""")
        "Mexican" -> listOf("""["amenity"="restaurant"]["cuisine"~"mexican|tex-mex",i]""")
        "Italian" -> listOf("""["amenity"="restaurant"]["cuisine"~"italian",i]""")
        "Thai" -> listOf("""["amenity"="restaurant"]["cuisine"~"thai",i]""")
        "Middle Eastern" -> listOf("""["amenity"="restaurant"]["cuisine"~"middle_eastern|lebanese|mediterranean|turkish|arab",i]""")
        "Vegetarian" -> listOf("""["amenity"="restaurant"]["cuisine"~"vegetarian|vegan",i]""")
        "Breakfast" -> listOf("""["amenity"~"restaurant|cafe"]["cuisine"~"breakfast|brunch",i]""")
        "BBQ" -> listOf("""["amenity"="restaurant"]["cuisine"~"bbq|barbecue",i]""")
        "Sandwich" -> listOf("""["amenity"~"restaurant|fast_food"]["cuisine"~"sandwich|sub",i]""")
        else -> listOf("""["amenity"="restaurant"]""")
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
        val phone = tags.optString("phone").takeIf { it.isNotBlank() }
            ?: tags.optString("contact:phone").takeIf { it.isNotBlank() }
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
            phoneNumber = phone,
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
        subcategory: String?,
        locationText: String?
    ): List<PlaceRecommendation> {
        val query = URLEncoder.encode("${photonQuery(category, subcategory)} ${locationText.orEmpty()}".trim(), "UTF-8")
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
            if (!photonMatchesCategory(props, category)) return@mapNotNull null
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

    private fun photonQuery(category: String, subcategory: String?): String = when (category) {
        "Restaurant" -> subcategory?.let { "${it.lowercase()} restaurant" } ?: "restaurant"
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

    private fun photonMatchesCategory(props: JSONObject, category: String): Boolean {
        val key = props.optString("osm_key")
        val value = props.optString("osm_value")
        return when (category) {
            "Restaurant" -> key == "amenity" && value in setOf("restaurant", "fast_food", "food_court")
            "Cafe" -> key == "amenity" && value == "cafe"
            "TouristAttraction" -> key == "tourism" && value in setOf("attraction", "viewpoint", "theme_park", "zoo", "aquarium")
            "Museum" -> key == "tourism" && value == "museum"
            "Park" -> key == "leisure" && value in setOf("park", "garden")
            "Bar" -> key == "amenity" && value in setOf("bar", "pub")
            "Hotel" -> key == "tourism" && value in setOf("hotel", "hostel", "guest_house", "motel")
            "Shopping" -> key == "shop"
            "Entertainment" -> (key == "amenity" && value in setOf("cinema", "theatre", "arts_centre", "nightclub")) ||
                (key == "leisure" && value in setOf("bowling_alley", "sports_centre", "escape_game"))
            else -> key in setOf("amenity", "tourism", "leisure", "shop")
        }
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
        val phone = props.optString("phone").takeIf { it.isNotBlank() }
            ?: props.optString("contact:phone").takeIf { it.isNotBlank() }
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
            phoneNumber = phone,
            coordsVerified = true
        )
    }

    // ── Geocoding ─────────────────────────────────────────────────────────────

    internal fun geocode(address: String): Pair<Double, Double>? =
        geocodePhoton(address) ?: geocodeNominatim(address)

    private fun geocodePhoton(address: String): Pair<Double, Double>? {
        return try {
            val encoded = URLEncoder.encode(address, "UTF-8")
            val req = Request.Builder()
                .url("https://photon.komoot.io/api/?q=$encoded&limit=1&lang=en")
                .header("User-Agent", "PlacesApp/1.0 (Android)")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return null
            val features = JSONObject(body).optJSONArray("features") ?: return null
            val feature = if (features.length() > 0) features.optJSONObject(0) else null
                ?: return null
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) null else Pair(lat, lon)
        } catch (e: Exception) {
            Log.w("LocalEngine", "Photon geocoding failed: ${e.message}")
            null
        }
    }

    private fun geocodeNominatim(address: String): Pair<Double, Double>? {
        return try {
            val encoded = URLEncoder.encode(address, "UTF-8")
            val req = Request.Builder()
                .url("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=$encoded")
                .header("User-Agent", "PlacesApp/1.0 (Android)")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return null
            val first = JSONArray(body).optJSONObject(0) ?: return null
            val lat = first.optString("lat").toDoubleOrNull()
            val lon = first.optString("lon").toDoubleOrNull()
            if (lat == null || lon == null) null else Pair(lat, lon)
        } catch (e: Exception) {
            Log.w("LocalEngine", "Nominatim geocoding failed: ${e.message}")
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
