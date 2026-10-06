package com.example.offlinetransitmap

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

internal object KeioDatabase {
    private const val PREFIX = "ODPT_KEIO:"
    const val BUS_PREFIX = "GTFS_KEIO_BUS:"
    private val columns = linkedMapOf(
        "stations" to "station_id,name,lat,lon,kana,grp,kind",
        "stops" to "stop_id,station_id,name,platform,zone",
        "routes" to "route_id,operator,name,long_name,color,route_type",
        "trips" to "trip_no,trip_id,route_id,service_id,headsign,train_type",
        "stop_times" to "trip,seq,stop_id,station_id,arr_sec,dep_sec,headsign,can_board,can_alight",
        "calendar" to "service_id,start_date,end_date,mon,tue,wed,thu,fri,sat,sun",
        "calendar_dates" to "service_id,date,exception_type",
        "fares" to "route_id,from_zone,to_zone,price"
    )

    fun validate(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { c -> check(c.moveToFirst() && c.getString(0) == "ok") { "時刻表DBが破損しています" } }
            for ((table, names) in columns) db.rawQuery("SELECT $names FROM $table LIMIT 0", null).close()
            db.rawQuery("SELECT key,value FROM meta LIMIT 0", null).close()
            db.rawQuery("SELECT start_date,end_date FROM feeds LIMIT 0", null).close()
        }
    }

    fun version(file: File, versionKey: String = "keio.version"): String? = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='app_data'", null).use { it.moveToFirst() }
        if (!exists) null else db.rawQuery("SELECT value FROM app_data WHERE key=?", arrayOf(versionKey)).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun isBootstrapOnly(file: File): Boolean = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='app_data'", null).use { it.moveToFirst() }
        val marked = exists && db.rawQuery("SELECT 1 FROM app_data WHERE key='base.keio_only' AND value='true'", null).use { it.moveToFirst() }
        marked && !db.rawQuery("SELECT 1 FROM routes WHERE substr(route_id,1,${PREFIX.length}) != '$PREFIX' AND substr(route_id,1,${BUS_PREFIX.length}) != '$BUS_PREFIX' LIMIT 1", null).use { it.moveToFirst() }
    }

    fun markBootstrapOnly(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT OR REPLACE INTO app_data VALUES ('base.keio_only','true')")
        }
    }

    // 呼び出し元が既存DBのコピーを渡す。ライブのDBを書き換えない。
    fun merge(target: File, seed: File, version: String, prefix: String = PREFIX, versionKey: String = "keio.version") {
        require(prefix == PREFIX || prefix == BUS_PREFIX)
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ATTACH DATABASE ? AS keio", arrayOf(seed.path))
            try {
                db.beginTransaction()
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS station_operators (station_id TEXT, operator TEXT, PRIMARY KEY(station_id,operator))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS app_data (key TEXT PRIMARY KEY,value TEXT)")
                    // 以前にこの機能が追加した行だけを更新する。
                    val owned = "substr(trip_id,1,${prefix.length}) = '$prefix'"
                    db.execSQL("DELETE FROM stop_times WHERE trip IN (SELECT trip_no FROM trips WHERE $owned)")
                    db.execSQL("DELETE FROM trips WHERE $owned")
                    for ((table, key) in listOf("stops" to "stop_id", "stations" to "station_id", "routes" to "route_id", "calendar" to "service_id", "calendar_dates" to "service_id", "fares" to "route_id", "station_operators" to "station_id")) {
                        db.execSQL("DELETE FROM $table WHERE substr($key,1,${prefix.length}) = '$prefix'")
                    }
                    val offset = db.rawQuery("SELECT COALESCE(MAX(trip_no),0) FROM trips", null).use { it.moveToFirst(); it.getLong(0) }
                    for ((table, names) in columns) {
                        val selected = names.split(',').joinToString(",") {
                            if ((table == "trips" && it == "trip_no") || (table == "stop_times" && it == "trip")) "$it + $offset" else it
                        }
                        db.execSQL("INSERT INTO $table ($names) SELECT $selected FROM keio.$table")
                    }
                    db.execSQL("INSERT INTO station_operators SELECT station_id,operator FROM keio.station_operators")
                    // 既存の同名・同種・近接する駅／バス停へ合流（従来の700mルール）。
                    db.rawQuery("SELECT station_id,name,lat,lon,grp,kind FROM keio.stations", null).use { rows ->
                        while (rows.moveToNext()) {
                            var group = rows.getString(4)
                            var nearest = 700.0
                            db.rawQuery("SELECT COALESCE(grp,station_id),lat,lon FROM stations WHERE kind=? AND name=? AND substr(station_id,1,${prefix.length}) != '$prefix'", arrayOf(rows.getString(5), rows.getString(1))).use { candidates ->
                                while (candidates.moveToNext()) {
                                    val meters = hypot((rows.getDouble(2)-candidates.getDouble(1))*110540, (rows.getDouble(3)-candidates.getDouble(2))*111320*cos(Math.toRadians(rows.getDouble(2))))
                                    if (meters <= nearest) { nearest = meters; group = candidates.getString(0) }
                                }
                            }
                            val values = ContentValues().apply { put("grp", group) }
                            db.update("stations", values, "station_id=?", arrayOf(rows.getString(0)))
                        }
                    }
                    db.execSQL("INSERT OR REPLACE INTO app_data SELECT key,value FROM keio.app_data")
                    db.execSQL("INSERT OR REPLACE INTO app_data VALUES (?,?)", arrayOf(versionKey, version))
                    // 元のv5 DBはprefix/name、同梱の最小DBはfeed_idを使用する。
                    val feedColumns = mutableSetOf<String>()
                    db.rawQuery("PRAGMA main.table_info(feeds)", null).use { c -> while (c.moveToNext()) feedColumns.add(c.getString(1)) }
                    val feedKey = if ("prefix" in feedColumns) "prefix" else "feed_id"
                    db.rawQuery("SELECT feed_id,start_date,end_date FROM keio.feeds", null).use { feeds ->
                        while (feeds.moveToNext()) {
                            check(feeds.getString(0) == prefix)
                            val values = ContentValues().apply {
                                put(feedKey, feeds.getString(0))
                                put("start_date", feeds.getInt(1)); put("end_date", feeds.getInt(2))
                                if ("name" in feedColumns) put("name", "京王バス")
                            }
                            check(db.insertWithOnConflict("feeds", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                        }
                    }
                    db.execSQL("CREATE INDEX IF NOT EXISTS app_stop_times_station ON stop_times(station_id,dep_sec)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS app_stop_times_trip ON stop_times(trip,seq)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS app_fares_lookup ON fares(route_id,from_zone,to_zone)")
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { db.execSQL("DETACH DATABASE keio") }
        }
        validate(target)
    }
}
