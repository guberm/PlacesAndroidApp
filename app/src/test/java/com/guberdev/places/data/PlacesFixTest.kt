package com.guberdev.places.data

import com.guberdev.places.data.model.PlaceRecommendation
import com.guberdev.places.data.model.hasDisplayAddress
import com.guberdev.places.data.model.normalizeWebsiteUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesFixTest {
    @Test
    fun allUsesPlacesApiBeforeOsmFallback() {
        assertTrue(usesPlacesApi("All"))
    }

    @Test
    fun addresslessPlacesAreHidden() {
        assertFalse(hasDisplayAddress(place(address = null)))
        assertFalse(hasDisplayAddress(place(address = "")))
        assertTrue(hasDisplayAddress(place(address = "532 Stream Crescent")))
    }

    @Test
    fun websiteUriIsNormalizedOrDropped() {
        assertEquals("https://example.com", normalizeWebsiteUri(" example.com "))
        assertEquals("https://example.com/path", normalizeWebsiteUri("https://example.com/path"))
        assertNull(normalizeWebsiteUri(""))
        assertNull(normalizeWebsiteUri("None Found"))
        assertNull(normalizeWebsiteUri("mailto:test@example.com"))
    }

    private fun place(address: String?) = PlaceRecommendation(
        name = "Place",
        description = "Description",
        category = "All",
        confidenceScore = 1.0,
        confidenceLevel = "High",
        address = address,
        latitude = null,
        longitude = null,
        sourceProvider = "Test",
        highlights = emptyList(),
        whyRecommended = null
    )
}
