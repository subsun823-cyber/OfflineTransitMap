package com.example.offlinetransitmap

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

class BusUpdateIntegrationTest {
    @Test fun pendingUpdatesPreserveBaseAndAreNotDowngradedToBundledSeed() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=Files.createTempDirectory(app.cacheDir.toPath(),"bus-update-test").toFile()
        val key=root.name
        val context=object : ContextWrapper(app) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?)=File(root,type ?: "external").apply { mkdirs() }
            override fun getSharedPreferences(name: String,mode: Int)=app.getSharedPreferences("$key-$name",mode)
        }
        try {
            OfflineDataSetup.prepare(context) {}
            val target=File(context.getExternalFilesDir("timetable"),"timetable.db")
            val before=BusUpdateDownload.hash(target)
            val feed=BusUpdates.feeds(context).single { it.id=="keio-bus" }
            val url="https://example.invalid/keio.zip"
            BusUpdates.storage(context).edit().putString("keio-bus.url",url).commit()
            val pending=File(context.filesDir,"bus-updates").apply { mkdirs() }
            val seed=File(pending,"keio-bus.db")
            val meta=File(pending,"keio-bus.json")
            val today=LocalDate.now(ZoneId.of("Asia/Tokyo"))
            fun day(value: LocalDate)=value.format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
            val end=day(today.plusYears(2))
            fun metadata(start: Int,hash: String=BusUpdateDownload.hash(seed))=JSONObject()
                .put("url",url).put("hash","updated-gtfs").put("seedHash",hash).put("start",start).put("end",end)
            GZIPInputStream(context.assets.open("bootstrap/keio-bus.bundle")).use { input -> seed.outputStream().use { input.copyTo(it) } }
            meta.writeText(metadata(day(today.plusDays(1))).toString())
            assertTrue(BusUpdates.applyPending(context,target).isEmpty())
            assertEquals(before,BusUpdateDownload.hash(target));assertTrue(meta.isFile)
            meta.writeText(metadata(day(today.minusDays(1)),"bad-hash").toString())
            assertTrue(BusUpdates.applyPending(context,target).isNotEmpty())
            assertEquals(before,BusUpdateDownload.hash(target));assertFalse(meta.exists())
            GZIPInputStream(context.assets.open("bootstrap/keio-bus.bundle")).use { input -> seed.outputStream().use { input.copyTo(it) } }
            SQLiteDatabase.openDatabase(seed.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE calendar SET end_date=?",arrayOf(end));db.execSQL("UPDATE feeds SET end_date=?",arrayOf(end))
                db.execSQL("UPDATE routes SET name='更新された系統' WHERE route_id='GTFS_KEIO_BUS:2'")
            }
            meta.writeText(metadata(day(today.minusDays(1))).toString())
            assertTrue(BusUpdates.applyPending(context,target).isEmpty())
            assertEquals("updated-gtfs",KeioDatabase.version(target,"update.keio-bus"))
            OfflineDataSetup.prepare(context) {}
            assertEquals("updated-gtfs",KeioDatabase.version(target,"update.keio-bus"))
            SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT name FROM routes WHERE route_id='GTFS_KEIO_BUS:2'",null).use { assertTrue(it.moveToFirst());assertEquals("更新された系統",it.getString(0)) }
                db.rawQuery("SELECT COUNT(*) FROM trips WHERE substr(trip_id,1,3)='JR_' OR substr(trip_id,1,3)='NT_'",null).use { it.moveToFirst();assertEquals(30482,it.getInt(0)) }
            }
            assertFalse(meta.exists())
        } finally {
            BusUpdates.storage(context).edit().clear().commit()
            root.deleteRecursively()
        }
    }
}
