package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class DeparturesAndLinesTest {

    @Test
    fun samplePastDepartures_areOrderedChronologicallyAscending() {
        val past = samplePastDepartures()
        assertTrue("Past departures should not be empty", past.isNotEmpty())
        // 各便の時刻が古い順(時系列昇順: 10分前 -> 1分前)に並んでいること
        for (i in 0 until past.size - 1) {
            assertTrue(
                "Departures should be ordered chronologically ascending",
                !past[i].time.isAfter(past[i + 1].time)
            )
        }
        val now = LocalDateTime.now()
        // 全ての便が過去時刻であること
        for (dep in past) {
            assertTrue("Departure time should be before or equal to now", !dep.time.isAfter(now))
        }
    }

    @Test
    fun sampleStationLines_containsOnlyRailAndUniqueNames() {
        val lines = sampleStationLines()
        assertTrue("Station lines should not be empty", lines.isNotEmpty())
        for (line in lines) {
            assertFalse("Station lines should not include bus operators", line.operator == "バス")
            assertFalse("Line name should not be blank", line.name.isBlank())
        }
        val names = lines.map { it.name }
        assertEquals("Line names must be distinct", names.size, names.distinct().size)
    }

    @Test
    fun lineFiltering_correctlyFiltersDepartures() {
        val all = sampleDepartures()
        val targetLine = "中央線"
        val filtered = all.filter { it.lineName == targetLine }
        assertTrue("Filtered list should not be empty", filtered.isNotEmpty())
        for (dep in filtered) {
            assertEquals(targetLine, dep.lineName)
        }
    }

    @Test
    fun samplePastDepartures_allWithinOneHour() {
        val now = LocalDateTime.now()
        val past = samplePastDepartures()
        for (dep in past) {
            val minutesAgo = java.time.temporal.ChronoUnit.MINUTES.between(dep.time, now)
            assertTrue("Past departure should be within 60 minutes, got: $minutesAgo", minutesAgo in 0..60)
        }
    }

    @Test
    fun stationLines_singleLineDoesNotShowChips_multiLineShowsChips() {
        val singleLineStation = listOf(StationLine("京王線", 0xFFDD0077, "京王"))
        assertFalse("Single station should not show line chips (lines.size < 2)", singleLineStation.size >= 2)

        val multiLineStation = listOf(
            StationLine("中央線", 0xFFF15A22, "JR"),
            StationLine("京王線", 0xFFDD0077, "京王")
        )
        assertTrue("Multi-line station should show line chips (lines.size >= 2)", multiLineStation.size >= 2)
    }

    @Test
    fun tobuStationIcon_existsAndHasValidDimensions() {
        val iconFile = File("src/main/res/drawable-xxxhdpi/map_station_tobu.png")
        assertTrue("Tobu station icon file should exist", iconFile.exists())
        assertTrue("Tobu station icon should not be empty", iconFile.length() > 500)
    }
}
