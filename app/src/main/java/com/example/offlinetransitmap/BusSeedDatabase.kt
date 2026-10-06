package com.example.offlinetransitmap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.File

internal object BusSeedDatabase {
    fun build(context: Context, zip: File, output: File, feed: BusFeed, check: () -> Unit): BusFeedInfo {
        SQLiteDatabase.openOrCreateDatabase(output,null).use { db ->
            db.execSQL("PRAGMA journal_mode=DELETE")
            val schema=context.assets.open("bootstrap/bus-schema.sql").bufferedReader().use { it.readText() }
            schema.split(';').filter { it.isNotBlank() }.forEach { db.execSQL(it) }
            val statements=mutableMapOf<String,SQLiteStatement>()
            db.beginTransaction()
            val info: BusFeedInfo
            try {
                info=BusGtfsImporter.convert(zip,feed,GtfsSink { table,values ->
                    val statement=statements.getOrPut(table) { db.compileStatement("INSERT INTO $table VALUES (${values.joinToString(",") { "?" }})") }
                    statement.clearBindings()
                    values.forEachIndexed { i,value -> when(value) {
                        null -> statement.bindNull(i+1)
                        is Double -> statement.bindDouble(i+1,value)
                        is Number -> statement.bindLong(i+1,value.toLong())
                        else -> statement.bindString(i+1,value.toString())
                    } }
                    statement.executeInsert()
                },check)
                check()
                db.execSQL("CREATE UNIQUE INDEX seed_trip_seq ON stop_times(trip,seq)")
                db.execSQL("CREATE INDEX seed_station ON stop_times(station_id,dep_sec)")
                db.execSQL("CREATE UNIQUE INDEX seed_fares ON fares(route_id,from_zone,to_zone)")
                db.rawQuery("SELECT 1 FROM stop_times a JOIN stop_times b ON b.trip=a.trip AND b.seq=(SELECT MAX(seq) FROM stop_times WHERE trip=a.trip AND seq<a.seq) WHERE a.arr_sec<b.dep_sec LIMIT 1",null).use { require(!it.moveToFirst()) { "便の時刻が逆転しています" } }
                db.execSQL("UPDATE trips SET headsign=(SELECT s.name FROM stop_times st JOIN stops s USING(stop_id) WHERE st.trip=trips.trip_no ORDER BY seq DESC LIMIT 1) WHERE headsign='' OR headsign IS NULL")
                db.setTransactionSuccessful()
            } finally { db.endTransaction();statements.values.forEach { it.close() } }
            return info
        }
    }
}
