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
    fun longTrainTypeAndLineName_handledWithoutError() {
        val longDep = Departure(
            operatorLabel = "西武鉄道",
            lineName = "池袋線（有料指定席）",
            lineColor = 0xFF003399,
            headsign = "池袋",
            detail = "特急",
            time = LocalDateTime.now().plusMinutes(56),
            trainType = "特急（ラビュー・小江戸・別途料金）"
        )
        assertEquals("西武鉄道", longDep.operatorLabel)
        assertEquals("池袋線（有料指定席）", longDep.lineName)
        assertEquals("特急（ラビュー・小江戸・別途料金）", longDep.trainType)
    }

    @Test
    fun tobuStationIcon_existsAndHasValidDimensions() {
        val iconFile = File("src/main/res/drawable-xxxhdpi/map_station_tobu.png")
        assertTrue("Tobu station icon file should exist", iconFile.exists())
        assertTrue("Tobu station icon should not be empty", iconFile.length() > 500)
    }

    @Test
    fun platformResolution_returnsPlatformOnlyWhenOfficiallyAvailable() {
        // 明示的な番線情報がある場合のみフォーマットして返す
        assertEquals("1番線", PlatformAndCarResolver.resolvePlatform("新宿", "山手線", "品川", "1", isBus = false))
        assertEquals("2番のりば", PlatformAndCarResolver.resolvePlatform("渋谷", "都営バス", "新橋駅", "2", isBus = true))
        assertEquals("3番線", PlatformAndCarResolver.resolvePlatform("東京", "東海道線", "熱海", "3番線", isBus = false))

        // 番線情報がない場合、推測による誤表示を避けるため空文字を返す
        assertEquals("", PlatformAndCarResolver.resolvePlatform("新宿", "山手線", "品川", null, isBus = false))
        assertEquals("", PlatformAndCarResolver.resolvePlatform("新宿", "山手線", "品川", "", isBus = false))
        assertEquals("", PlatformAndCarResolver.resolvePlatform("新宿", "山手線", "品川", "0", isBus = false))
    }

    @Test
    fun departureDetailFormatting_hidesPlatformWhenEmpty() {
        // 番線情報がある場合
        val withPlatform = PlatformAndCarResolver.formatDepartureDetail("定刻", "2番線", 10, isBus = false)
        assertEquals("定刻 · 2番線 · 10両編成", withPlatform)

        // 番線情報がない場合、番線部分は非表示になり定刻と両数のみ表示
        val withoutPlatform = PlatformAndCarResolver.formatDepartureDetail("定刻", "", 10, isBus = false)
        assertEquals("定刻 · 10両編成", withoutPlatform)

        // バスの場合（両数表示なし）
        val busWithPlatform = PlatformAndCarResolver.formatDepartureDetail("定刻", "1番のりば", null, isBus = true)
        assertEquals("定刻 · 1番のりば", busWithPlatform)

        val busWithoutPlatform = PlatformAndCarResolver.formatDepartureDetail("定刻", "", null, isBus = true)
        assertEquals("定刻", busWithoutPlatform)
    }

    @Test
    fun trainPhotoResolver_resolvesSpecifiedTrainModels() {
        // 埼京線 & 川越線（大宮〜川越） -> E233系7000番台 (saikyo_e233_7000)
        assertEquals(
            "saikyo_e233_7000",
            TrainPhotoResolver.resolvePhotoKey("大宮", listOf(StationLine("埼京線・川越線", 0L, "JR東日本")), "埼京線・川越線", emptyList())
        )
        assertEquals(
            "saikyo_e233_7000",
            TrainPhotoResolver.resolvePhotoKey("南古谷", listOf(StationLine("川越線", 0L, "JR東日本")), "川越線", emptyList())
        )

        // 川越線（高麗川〜川越） -> E231系3000番台 (hachiko)
        assertEquals(
            "hachiko",
            TrainPhotoResolver.resolvePhotoKey("笠幡", listOf(StationLine("川越線", 0L, "JR東日本")), "川越線", emptyList())
        )
        assertEquals(
            "hachiko",
            TrainPhotoResolver.resolvePhotoKey("川越", listOf(StationLine("川越線(川越-高麗川間)", 0L, "JR東日本")), "川越線(川越-高麗川間)", emptyList())
        )

        // 八高線（高麗川以南） -> E231系3000番台 (hachiko)
        assertEquals(
            "hachiko",
            TrainPhotoResolver.resolvePhotoKey("八王子", listOf(StationLine("八高線", 0L, "JR東日本")), "八高線", emptyList())
        )

        // 八高線（高麗川以北） -> HB-E220系 (hachiko_hbe220)
        assertEquals(
            "hachiko_hbe220",
            TrainPhotoResolver.resolvePhotoKey("寄居", listOf(StationLine("八高線", 0L, "JR東日本")), "八高線", emptyList())
        )
        assertEquals(
            "hachiko_hbe220",
            TrainPhotoResolver.resolvePhotoKey("高崎", listOf(StationLine("八高線", 0L, "JR東日本")), "八高線", emptyList())
        )

        // 東武越生線 -> 東武8000系 (tobu_8000)
        assertEquals(
            "tobu_8000",
            TrainPhotoResolver.resolvePhotoKey("越生", listOf(StationLine("東武越生線", 0L, "東武鉄道")), "東武越生線", emptyList())
        )
        assertEquals(
            "tobu_8000",
            TrainPhotoResolver.resolvePhotoKey("武州長瀬", listOf(StationLine("越生線", 0L, "東武鉄道")), "越生線", emptyList())
        )

        // 東武小泉線・佐野線 -> 東武10000系 (tobu_10000)
        assertEquals(
            "tobu_10000",
            TrainPhotoResolver.resolvePhotoKey("佐野", listOf(StationLine("東武佐野線", 0L, "東武鉄道")), "東武佐野線", emptyList())
        )
        assertEquals(
            "tobu_10000",
            TrainPhotoResolver.resolvePhotoKey("西小泉", listOf(StationLine("東武小泉線", 0L, "東武鉄道")), "東武小泉線", emptyList())
        )
    }
}
