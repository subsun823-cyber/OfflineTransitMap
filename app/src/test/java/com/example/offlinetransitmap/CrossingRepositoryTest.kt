package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingRepositoryTest {

    @Test
    fun testLevelCrossingItemAndMemoDefaults() {
        val memo = CrossingMemo(
            isFavorite = true,
            visited = true,
            visitedDate = "2026/10/11",
            notes = "東急田園都市線の名所"
        )
        assertTrue(memo.visited)
        assertEquals("2026/10/11", memo.visitedDate)

        val item = LevelCrossingItem(
            id = "c1",
            name = "中川第1号踏切",
            lat = 35.5,
            lon = 139.5,
            detail = "踏切",
            railwayLine = "東急電鉄 田園都市線",
            memo = memo
        )
        assertEquals("東急電鉄 田園都市線", item.railwayLine)
        assertEquals(true, item.memo?.visited)
    }

    @Test
    fun testRailwayLineStatsProgressCalculation() {
        val c1 = LevelCrossingItem("1", "踏切1", 35.0, 139.0, "", memo = CrossingMemo(visited = true))
        val c2 = LevelCrossingItem("2", "踏切2", 35.0, 139.0, "", memo = CrossingMemo(visited = false))
        val c3 = LevelCrossingItem("3", "踏切3", 35.0, 139.0, "", memo = null)
        val c4 = LevelCrossingItem("4", "踏切4", 35.0, 139.0, "", memo = CrossingMemo(visited = true))

        val stats = RailwayLineCrossingStats(
            lineName = "JR南武線",
            totalCount = 4,
            visitedCount = 2,
            crossings = listOf(c1, c2, c3, c4)
        )

        assertEquals(0.5f, stats.progressRatio, 0.001f)
        assertEquals("50.0%", stats.progressPercentText)

        val allVisitedStats = stats.copy(visitedCount = 4)
        assertEquals(1.0f, allVisitedStats.progressRatio, 0.001f)
        assertEquals("100.0%", allVisitedStats.progressPercentText)
    }

    @Test
    fun testOverallConquestStatsCalculation() {
        val line1 = RailwayLineCrossingStats("JR南武線", 100, 25, emptyList())
        val line2 = RailwayLineCrossingStats("西武池袋線", 150, 75, emptyList())

        val overall = OverallConquestStats(
            totalCount = 250,
            visitedCount = 100,
            lineCount = 2,
            lineStats = listOf(line1, line2)
        )

        assertEquals(0.4f, overall.progressRatio, 0.001f)
        assertEquals("40.0%", overall.progressPercentText)
    }

    @Test
    fun testResolveRailwayLineFallback() {
        // timetableDb が null の場合、その他・専用線にフォールバックすること
        val line = CrossingRepository.resolveRailwayLine("新宿第1号踏切", 35.69, 139.70, null)
        assertEquals("その他・専用線", line)
    }
}
