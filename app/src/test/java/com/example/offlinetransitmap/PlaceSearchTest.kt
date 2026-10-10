package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchTest {

    @Test
    fun testFormatDistance() {
        assertEquals("約350m", formatDistance(350))
        assertEquals("約999m", formatDistance(999))
        assertEquals("約1.0km", formatDistance(1000))
        assertEquals("約1.2km", formatDistance(1234))
        assertEquals("約15.0km", formatDistance(15000))
    }

    @Test
    fun testFormatDuration() {
        assertEquals("徒歩約5分", formatDuration(5))
        assertEquals("徒歩約59分", formatDuration(59))
        assertEquals("徒歩約1時間", formatDuration(60))
        assertEquals("徒歩約1時間15分", formatDuration(75))
        assertEquals("徒歩約2時間", formatDuration(120))
    }

    @Test
    fun testPresetPlaceSearchByName() {
        val searcher = PlaceSearcher(null)
        // 東京タワーの検索（漢字）
        val results = searcher.search("東京タワー")
        assertTrue(results.isNotEmpty())
        assertEquals("東京タワー", results[0].place.name)
        assertEquals(PlaceCategory.LANDMARK, results[0].place.category)

        // ひらがな検索
        val hiraResults = searcher.search("すかいつりー")
        assertTrue(hiraResults.isNotEmpty())
        assertEquals("東京スカイツリー", hiraResults[0].place.name)
    }

    @Test
    fun testSearchWithCurrentLocationCalculatesDistanceAndDuration() {
        val searcher = PlaceSearcher(null)
        // 芝公園付近の座標
        val curLat = 35.655
        val curLon = 139.748

        val results = searcher.search("東京タワー", currentLat = curLat, currentLon = curLon)
        assertTrue(results.isNotEmpty())
        val first = results[0]
        assertNotNull(first.distanceMeters)
        assertNotNull(first.durationMinutes)
        assertTrue(first.distanceMeters!! < 1000)
        assertTrue(first.distanceText.startsWith("約"))
        assertTrue(first.durationText.startsWith("徒歩約"))
    }

    @Test
    fun testCategoryFilter() {
        val searcher = PlaceSearcher(null)
        val parkResults = searcher.search("", categoryFilter = PlaceCategory.PARK)
        assertTrue(parkResults.isNotEmpty())
        assertTrue(parkResults.all { it.place.category == PlaceCategory.PARK })
    }
}
