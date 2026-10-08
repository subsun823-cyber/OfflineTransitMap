package com.example.offlinetransitmap

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class KeioDatabaseTest {
    @Test fun realSeedMergesWithoutReplacingExistingTransitAndIsIdempotent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "keio-test").toFile()
        try {
            val seed = File(directory, "seed.db")
            GZIPInputStream(context.assets.open("bootstrap/keio.bundle")).use { input -> seed.outputStream().use { input.copyTo(it) } }
            val target = File(directory,"timetable.db")
            seed.copyTo(target)
            SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                // Same-name, nearby JR station and unrelated bus, including colliding numeric trip ID.
                db.execSQL("INSERT INTO stations VALUES ('JR_FIXTURE','高尾',35.6425,139.2825,'','existing-takao','rail')")
                db.execSQL("INSERT INTO stops VALUES ('JR_FIXTURE','JR_FIXTURE','高尾','1','JR_ZONE')")
                db.execSQL("INSERT INTO routes VALUES ('JR_ROUTE','JR東日本','中央線','中央線',0,2)")
                db.execSQL("INSERT INTO trips VALUES (9000,'JR_TRIP','JR_ROUTE','EXISTING_SERVICE','東京','普通')")
                db.execSQL("INSERT INTO stop_times VALUES (9000,1,'JR_FIXTURE','JR_FIXTURE',36000,36000,NULL,1,1)")
            }
            KeioDatabase.merge(target, seed, "test-version")
            KeioDatabase.merge(target, seed, "test-version")
            KeioDatabase.validate(target)
            assertEquals("test-version",KeioDatabase.version(target))
            val wrapper = object : ContextWrapper(context) { override fun getExternalFilesDir(type: String?): File = directory }
            val timetable = requireNotNull(TimetableDb.open(wrapper))
            timetable.db.use { db ->
                db.rawQuery("SELECT COUNT(*) FROM trips",null).use { it.moveToFirst();assertEquals(835,it.getInt(0)) }
                db.rawQuery("SELECT trip_id FROM trips WHERE trip_no=9000",null).use { assertTrue(it.moveToFirst());assertEquals("JR_TRIP",it.getString(0)) }
                db.rawQuery("SELECT COUNT(*) FROM stop_times st LEFT JOIN trips t ON st.trip=t.trip_no WHERE t.trip_no IS NULL",null).use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
                val features = JSONObject(timetable.stationsGeoJson()).getJSONArray("features")
                val takao = (0 until features.length()).map { features.getJSONObject(it).getJSONObject("properties") }.single { it.getString("name")=="高尾" }
                assertEquals("station-rail-both",takao.getString("rail_icon"))
                assertTrue(RouteSearcher(db).isSupported())
                assertTrue(RouteSearcher(db).searchStations("しぶや").isNotEmpty())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun freshInstallAndSecondStartupNeedNoManualFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "setup-test").toFile()
        try {
            val wrapper = object : ContextWrapper(context) {
                override fun getExternalFilesDir(type: String?): File = File(directory,type ?: "files").apply { mkdirs() }
            }
            val first = OfflineDataSetup.prepare(wrapper) {}
            val file = File(directory,"timetable/timetable.db")
            assertTrue(file.isFile)
            assertFalse(KeioDatabase.isBootstrapOnly(file))
            assertTrue(File(directory,"maps/tokyo.pmtiles").isFile)
            assertNotNull(KeioDatabase.version(file, "keio-bus.version"))
            assertNotNull(KeioDatabase.version(file, "odakyu.version"))
            val version = KeioDatabase.version(file)
            val modified = file.lastModified()
            val second = OfflineDataSetup.prepare(wrapper) {}
            assertEquals(first,second)
            assertEquals(version,KeioDatabase.version(file))
            assertEquals(modified,file.lastModified())
            assertFalse(first.any { it.contains("地図データが未準備") })
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM trips", null).use { it.moveToFirst(); assertEquals(65341,it.getInt(0)) }
                db.rawQuery("SELECT COUNT(*) FROM routes WHERE operator='京王バス'", null).use { it.moveToFirst(); assertEquals(254,it.getInt(0)) }
                db.rawQuery("SELECT COUNT(*) FROM routes WHERE operator='小田急電鉄'", null).use { it.moveToFirst(); assertEquals(6,it.getInt(0)) }
                db.rawQuery("SELECT COUNT(*) FROM stations WHERE station_id LIKE 'ODPT_ODAKYU:%'", null).use { it.moveToFirst(); assertEquals(72,it.getInt(0)) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun odakyuSeedMergesCorrectly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "odakyu-test").toFile()
        try {
            val seed = File(directory, "odakyu-seed.db")
            GZIPInputStream(context.assets.open("bootstrap/odakyu.bundle")).use { input -> seed.outputStream().use { input.copyTo(it) } }
            val target = File(directory, "timetable.db")
            seed.copyTo(target)
            KeioDatabase.merge(target, seed, "test-odakyu-version", KeioDatabase.ODAKYU_PREFIX, "odakyu.version")
            KeioDatabase.validate(target)
            assertEquals("test-odakyu-version", KeioDatabase.version(target, "odakyu.version"))
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM trips WHERE trip_id LIKE 'ODPT_ODAKYU:%'", null).use { it.moveToFirst(); assertEquals(4475, it.getInt(0)) }
                db.rawQuery("SELECT COUNT(*) FROM stations WHERE station_id LIKE 'ODPT_ODAKYU:%'", null).use { it.moveToFirst(); assertEquals(72, it.getInt(0)) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun tokyoMetroSeedMergesCorrectly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "tokyometro-test").toFile()
        try {
            val seed = File(directory, "tokyometro-seed.db")
            GZIPInputStream(context.assets.open("bootstrap/tokyometro.bundle")).use { input -> seed.outputStream().use { input.copyTo(it) } }
            val target = File(directory, "timetable.db")
            seed.copyTo(target)
            KeioDatabase.merge(target, seed, "test-tokyometro-version", KeioDatabase.TOKYO_METRO_PREFIX, "tokyometro.version")
            KeioDatabase.validate(target)
            assertEquals("test-tokyometro-version", KeioDatabase.version(target, "tokyometro.version"))
            SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM trips WHERE trip_id LIKE 'ODPT_TOKYO_METRO:%'", null).use { it.moveToFirst(); assertEquals(9982, it.getInt(0)) }
                db.rawQuery("SELECT COUNT(*) FROM stations WHERE station_id LIKE 'ODPT_TOKYO_METRO:%'", null).use { it.moveToFirst(); assertEquals(186, it.getInt(0)) }
            }
        } finally { directory.deleteRecursively() }
    }
}
