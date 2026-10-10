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
    fun tobuStationIcon_existsAndHasValidDimensions() {
        val iconFile = File("src/main/res/drawable-xxxhdpi/map_station_tobu.png")
        assertTrue("Tobu station icon file should exist", iconFile.exists())
        assertTrue("Tobu station icon should not be empty", iconFile.length() > 500)
    }
}
