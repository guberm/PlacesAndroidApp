package com.guberdev.places.data

import com.guberdev.places.data.model.normalizeWebsiteUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesFixTest {
    @Test
    fun allUsesBroadOsmFallback() {
        assertFalse(usesPlacesApi("All"))
    }

    @Test
    fun websiteUriIsNormalizedOrDropped() {
        assertEquals("https://example.com", normalizeWebsiteUri(" example.com "))
        assertEquals("https://example.com/path", normalizeWebsiteUri("https://example.com/path"))
        assertNull(normalizeWebsiteUri(""))
        assertNull(normalizeWebsiteUri("None Found"))
        assertNull(normalizeWebsiteUri("mailto:test@example.com"))
    }
}
