package com.example.offlinetransitmap

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class TimetableDbTest {
    private lateinit var directory: File
    private lateinit var context: ContextWrapper
    private var timetable: TimetableDb? = null
    private val now = LocalDateTime.of(2026, 10, 6, 9, 0)

    @Before
    fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(base.cacheDir.toPath(), "timetable-test-").toFile()
        // 実際の端末に置かれた timetable.db は使用・上書きしない。
        context = object : ContextWrapper(base) {
            override fun getExternalFilesDir(type: String?): File = directory
        }
    }

    @After
    fun tearDown() {
        timetable?.db?.close()
        directory.deleteRecursively()
    }

    private fun openFixture(withTrainType: Boolean = true): TimetableDb {
        SQLiteDatabase.openOrCreateDatabase(File(directory, "timetable.db"), null).use { db ->
            val statements = listOf(
                "CREATE TABLE stations (station_id TEXT, grp TEXT)",
                "CREATE TABLE stops (stop_id TEXT, platform TEXT)",
                "CREATE TABLE routes (route_id TEXT, operator TEXT, name TEXT, color INTEGER, route_type INTEGER)",
                "CREATE TABLE trips (trip_no INTEGER, route_id TEXT, service_id TEXT, headsign TEXT)",
                "CREATE TABLE stop_times (trip INTEGER, seq INTEGER, station_id TEXT, stop_id TEXT, dep_sec INTEGER, headsign TEXT, can_board INTEGER)",
                "CREATE TABLE calendar (service_id TEXT, start_date INTEGER, end_date INTEGER, mon INTEGER, tue INTEGER, wed INTEGER, thu INTEGER, fri INTEGER, sat INTEGER, sun INTEGER)",
                "CREATE TABLE calendar_dates (service_id TEXT, date INTEGER, exception_type INTEGER)",
                "INSERT INTO stations VALUES ('NT_1', 'bus'), ('JR_1301', 'rail'), ('JR_1302', 'rail')",
                "INSERT INTO stops VALUES ('bus_stop', '2'), ('rail_stop_1', '1'), ('rail_stop_2', '3')",
                "INSERT INTO routes VALUES ('bus_route', '西東京バス', '八01', 32768, 3), ('rail_route', 'JR東日本', '中央線', 16711680, 2)",
                "INSERT INTO calendar VALUES ('NT_weekday', 20261001, 20261231, 1, 1, 1, 1, 1, 0, 0), ('JR_weekday', 20260314, 20270312, 1, 1, 1, 1, 1, 0, 0)",
                "INSERT INTO trips VALUES (1, 'bus_route', 'NT_weekday', '八王子駅'), (2, 'rail_route', 'JR_weekday', '東京'), (3, 'rail_route', 'JR_weekday', '高尾'), (4, 'bus_route', 'inactive', '運休便')",
                "INSERT INTO stop_times VALUES (1, 1, 'NT_1', 'bus_stop', 32700, NULL, 1), (2, 1, 'JR_1301', 'rail_stop_1', 33000, NULL, 1), (3, 1, 'JR_1302', 'rail_stop_2', 33300, NULL, 1), (4, 1, 'NT_1', 'bus_stop', 32460, NULL, 1), (1, 2, 'NT_1', 'bus_stop', 33600, NULL, 0)"
            )
            for (sql in statements) db.execSQL(sql)
            if (withTrainType) {
                db.execSQL("ALTER TABLE trips ADD COLUMN train_type TEXT")
                db.execSQL("UPDATE trips SET train_type = '快速' WHERE trip_no = 2")
                db.execSQL("UPDATE trips SET train_type = '普通' WHERE trip_no = 3")
            }
        }
        return requireNotNull(TimetableDb.open(context)).also { timetable = it }
    }

    @Test
    fun busDeparturesRemainVisibleWithTrainTypes() {
        val departures = openFixture().departures("NT_1", now, daysAhead = 0)
        assertEquals(1, departures.size)
        val bus = departures.single()
        assertTrue(bus.isBus)
        assertEquals("", bus.trainType)
        assertEquals("八王子駅", bus.headsign)
        assertEquals("2番のりば", bus.detail)
        assertEquals(now.plusMinutes(5), bus.time)
    }

    @Test
    fun groupedRailDeparturesReadTrainTypesInsteadOfStationIds() {
        val departures = openFixture().departures("JR_1301", now, daysAhead = 0)
        assertEquals(listOf(2L, 3L), departures.map { it.tripNo })
        assertEquals(listOf("快速", "普通"), departures.map { it.trainType })
        assertEquals(listOf("東京", "高尾"), departures.map { it.headsign })
        assertEquals(listOf("1番線", "3番線"), departures.map { it.detail })
        assertEquals(listOf(now.plusMinutes(10), now.plusMinutes(15)), departures.map { it.time })
        assertTrue(departures.all { !it.isBus })
        assertFalse(departures.any { it.trainType.startsWith("JR_") })
    }

    @Test
    fun databaseWithoutTrainTypeStillReturnsBusAndRailDepartures() {
        val db = openFixture(withTrainType = false)
        val bus = db.departures("NT_1", now, daysAhead = 0)
        val rail = db.departures("JR_1301", now, daysAhead = 0)
        assertEquals(listOf(1L), bus.map { it.tripNo })
        assertEquals(listOf(2L, 3L), rail.map { it.tripNo })
        assertTrue((bus + rail).all { it.trainType.isEmpty() })
    }

    private fun executeMapSql(sql: String) {
        SQLiteDatabase.openOrCreateDatabase(File(directory, "timetable.db"), null).use { it.execSQL(sql) }
    }

    private fun openMapFixture(withKind: Boolean = true): TimetableDb {
        val timetable = openFixture()
        executeMapSql("ALTER TABLE stations ADD COLUMN name TEXT DEFAULT '駅名'")
        executeMapSql("ALTER TABLE stations ADD COLUMN lat REAL DEFAULT 35")
        executeMapSql("ALTER TABLE stations ADD COLUMN lon REAL DEFAULT 139")
        if (withKind) {
            executeMapSql("ALTER TABLE stations ADD COLUMN kind TEXT DEFAULT 'bus'")
            executeMapSql("UPDATE stations SET kind = 'rail' WHERE grp = 'rail'")
        }
        return timetable
    }

    private fun mapProperties(timetable: TimetableDb): Map<String, JSONObject> {
        val features = JSONObject(timetable.stationsGeoJson()).getJSONArray("features")
        return (0 until features.length()).associate { i ->
            val props = features.getJSONObject(i).getJSONObject("properties")
            props.getString("id") to props
        }
    }

    @Test fun mapUsesJrOperatorAndKeepsBusClassification() {
        val props = mapProperties(openMapFixture())
        assertEquals(2, props.size)
        assertEquals("station-jr-east", props.getValue("JR_1301").getString("rail_icon"))
        assertEquals("rail", props.getValue("JR_1301").getString("kind"))
        assertEquals("bus", props.getValue("NT_1").getString("kind"))
    }

    @Test fun sharedStationIncludesAlightingOnlyPrivateRailAndIgnoresBusRoutes() {
        val timetable = openMapFixture()
        // バスの乗り入れだけではJR＋私鉄表示にしない。
        executeMapSql("INSERT INTO stop_times VALUES (1, 9, 'JR_1301', 'bus_stop', 35000, NULL, 1)")
        assertEquals("station-jr-east", mapProperties(timetable).getValue("JR_1301").getString("rail_icon"))
        executeMapSql("INSERT INTO stations VALUES ('P_1', 'rail', '駅名', 38, 142, 'rail')")
        executeMapSql("INSERT INTO routes VALUES ('private', '京王電鉄', '高尾線', 0, 2)")
        executeMapSql("INSERT INTO trips VALUES (5, 'private', 'inactive', '', '')")
        executeMapSql("INSERT INTO stop_times VALUES (5, 1, 'P_1', 'private_stop', 35000, NULL, 0)")
        val props = mapProperties(timetable)
        assertEquals(2, props.size)
        assertEquals("station-rail-both", props.getValue("JR_1301").getString("rail_icon"))
        val features = JSONObject(timetable.stationsGeoJson()).getJSONArray("features")
        val rail = (0 until features.length()).map { features.getJSONObject(it) }
            .single { it.getJSONObject("properties").getString("id") == "JR_1301" }
        val coords = rail.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(140.0, coords.getDouble(0), 0.001)
        assertEquals(36.0, coords.getDouble(1), 0.001)
        // 同名でもグループが違う駅は混ぜない。
        executeMapSql("UPDATE stations SET grp = 'private' WHERE station_id = 'P_1'")
        val separate = mapProperties(timetable)
        assertEquals(3, separate.size)
        assertEquals("station-jr-east", separate.getValue("JR_1301").getString("rail_icon"))
        assertEquals("station-rail", separate.getValue("P_1").getString("rail_icon"))
    }

    @Test fun mapWithoutOperatorDetailsKeepsRailVisible() {
        val timetable = openMapFixture()
        executeMapSql("DROP TABLE routes")
        assertEquals("station-rail", mapProperties(timetable).getValue("JR_1301").getString("rail_icon"))
    }

    @Test fun legacyMapWithoutKindKeepsExistingStationBehavior() {
        val props = mapProperties(openMapFixture(withKind = false))
        assertEquals(2, props.size)
        assertTrue(props.values.all { it.getString("kind") == "bus" })
    }
}
