package com.example.offlinetransitmap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class TimetableDb private constructor(val db: SQLiteDatabase) {

    companion object {
        private val DAY_COLUMNS = arrayOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        // timetable.db を開く。無ければ null。
        // このとき timetable フォルダは、アプリ自身が作る(adb で先に作らない)
        fun open(context: Context): TimetableDb? {
            val dir = context.getExternalFilesDir("timetable") ?: return null
            val file = File(dir, "timetable.db")
            if (!file.exists()) return null
            return try {
                TimetableDb(
                    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    // 地図に出す駅の点(GeoJSON)。同じ名前の駅は1点にまとめ、kind は rail(電車)か bus(バス停)
    fun stationsGeoJson(): String {
        val kindExpr = if (hasStationKind) "MAX(CASE WHEN kind = 'rail' THEN 1 ELSE 0 END)" else "0"
        val railOperators = railOperatorsByGroup()
        val features = JSONArray()
        db.rawQuery(
            "SELECT MIN(station_id), MIN(name), AVG(lat), AVG(lon), $kindExpr, COALESCE(grp, station_id) FROM stations " +
                    "GROUP BY COALESCE(grp, station_id)",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val props = JSONObject()
                    .put("id", c.getString(0))
                    .put("name", c.getString(1))
                    .put("kind", if (c.getInt(4) == 1) "rail" else "bus")
                    .put("rail_icon", railStationIcon(railOperators[c.getString(5)].orEmpty()))
                val geometry = JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(c.getDouble(3)).put(c.getDouble(2)))
                features.put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("properties", props)
                        .put("geometry", geometry)
                )
            }
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    }

    private fun railOperatorsByGroup(): Map<String, Set<String>> {
        if (!hasStationKind) return emptyMap()
        val operators = mutableMapOf<String, MutableSet<String>>()
        if (hasTable("station_operators")) {
            db.rawQuery("SELECT DISTINCT COALESCE(s.grp,s.station_id),o.operator FROM stations s JOIN station_operators o ON o.station_id=s.station_id WHERE s.kind='rail'", null).use { c ->
                while (c.moveToNext()) operators.getOrPut(c.getString(0)) { mutableSetOf() }.add(c.getString(1) ?: "")
            }
        }
        try {
            // 終点・降車専用駅も含める。便数で駅の代表座標が偏らないよう、座標集計とは分ける。
            db.rawQuery(
                """
                SELECT DISTINCT COALESCE(s.grp, s.station_id), r.operator
                FROM stations s
                JOIN stop_times st ON st.station_id = s.station_id
                JOIN trips t ON t.trip_no = st.trip
                JOIN routes r ON r.route_id = t.route_id
                WHERE s.kind = 'rail' AND r.route_type IN (0, 1, 2)
                """.trimIndent(), null
            ).use { c ->
                while (c.moveToNext()) {
                    operators.getOrPut(c.getString(0)) { mutableSetOf() }.add(c.getString(1) ?: "")
                }
            }
        } catch (e: SQLiteException) {
            // 会社情報が読めない旧DBでも、駅は共通の列車アイコンで表示する。
            Log.w("TimetableDb", "Station operators unavailable; using generic rail icons", e)
        }
        return operators
    }

    private fun hasTable(name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)
    ).use { it.moveToFirst() }

    fun stationDataNote(stationId: String): String? {
        if (!hasTable("station_operators")) return null
        val ids = groupStationIds(stationId)
        val placeholders = ids.joinToString(",") { "?" }
        val keio = db.rawQuery("SELECT 1 FROM station_operators WHERE station_id IN ($placeholders) AND operator='京王電鉄' LIMIT 1", ids.toTypedArray()).use { it.moveToFirst() }
        val notes = mutableListOf<String>()
        if (keio) notes.add("京王電鉄：一部の便のみ収録・適用終了日未確認。未収録の時刻表・運賃は設定画面をご確認ください。")
        val keioBus = ids.any { it.startsWith("GTFS_KEIO_BUS:") }
        if (keioBus && hasTable("app_data")) {
            db.rawQuery("SELECT value FROM app_data WHERE key='keio-bus.note'", null).use { c ->
                if (c.moveToFirst()) notes.add(c.getString(0))
            }
        }
        return notes.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    // 時刻表の有効期間(例: 2026/10/1〜2026/12/31)
    fun validityText(): String? {
        db.rawQuery("SELECT MIN(start_date), MAX(end_date) FROM feeds", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) {
                return "${formatYmd(c.getInt(0))}〜${formatYmd(c.getInt(1))}"
            }
        }
        return null
    }

    private fun formatYmd(v: Int): String = "${v / 10000}/${v / 100 % 100}/${v % 100}"

    // その日に運行しているサービスID(曜日 + 例外日の追加・除外)
    private fun activeServices(date: LocalDate): Set<String> {
        val ymd = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        val col = DAY_COLUMNS[date.dayOfWeek.value - 1]
        val services = HashSet<String>()
        db.rawQuery(
            "SELECT service_id FROM calendar WHERE $col = 1 AND start_date <= $ymd AND end_date >= $ymd",
            null
        ).use { c ->
            while (c.moveToNext()) services.add(c.getString(0))
        }
        db.rawQuery(
            "SELECT service_id, exception_type FROM calendar_dates WHERE date = $ymd",
            null
        ).use { c ->
            while (c.moveToNext()) {
                when (c.getInt(1)) {
                    1 -> services.add(c.getString(0))
                    2 -> services.remove(c.getString(0))
                }
            }
        }
        return services
    }

    // 種別の列(trips.train_type)があるDBなら、その列名。古いDBでは NULL を返す
    private val trainTypeColumn: String by lazy {
        var has = false
        try {
            db.rawQuery("PRAGMA table_info(trips)", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == "train_type") has = true
                }
            }
        } catch (e: Exception) {
            // 何もしない
        }
        if (has) "t.train_type" else "NULL"
    }

    // 駅の種類の列(stations.kind)があるDB(バージョン5以降)か
    private val hasStationKind: Boolean by lazy {
        var has = false
        try {
            db.rawQuery("PRAGMA table_info(stations)", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == "kind") has = true
                }
            }
        } catch (e: Exception) {
            // 何もしない
        }
        has
    }

    // その駅と同じグループ(同じ名前でまとまっている駅)の station_id の一覧
    private fun groupStationIds(stationId: String): List<String> {
        val ids = ArrayList<String>()
        try {
            db.rawQuery(
                "SELECT station_id FROM stations WHERE grp = " +
                        "(SELECT COALESCE(grp, station_id) FROM stations WHERE station_id = ?)",
                arrayOf(stationId)
            ).use { c ->
                while (c.moveToNext()) ids.add(c.getString(0))
            }
        } catch (e: Exception) {
            // 何もしない
        }
        if (ids.isEmpty()) ids.add(stationId)
        return ids
    }

    // 駅の出発を、現在時刻以降で時刻順に最大 limit 件返す(同じ名前の駅の分もまとめる)
    fun departures(
        stationId: String,
        now: LocalDateTime,
        limit: Int = 25,
        daysAhead: Int = 7
    ): List<Departure> {
        val ids = groupStationIds(stationId)
        val result = ArrayList<Departure>()
        val today = now.toLocalDate()
        // 昨日分は「24時を過ぎた深夜便」を拾うために見る
        for (offset in -1..daysAhead) {
            val day = today.plusDays(offset.toLong())
            val services = activeServices(day)
            if (services.isNotEmpty()) {
                val midnight = day.atStartOfDay()
                val minSec = maxOf(0L, Duration.between(midnight, now).seconds)
                result.addAll(queryDay(ids, midnight, minSec, services, limit))
                result.sortBy { it.time }
            }
            // 必要な件数が集まり、次の日の0時より前に収まっていれば打ち切る
            if (offset >= 0 && result.size >= limit &&
                result[limit - 1].time < day.plusDays(1).atStartOfDay()
            ) {
                break
            }
        }
        return result.take(limit)
    }

    private fun queryDay(
        stationIds: List<String>,
        midnight: LocalDateTime,
        minSec: Long,
        services: Set<String>,
        limit: Int
    ): List<Departure> {
        val stationPlaceholders = stationIds.joinToString(",") { "?" }
        val placeholders = services.joinToString(",") { "?" }
        val sql = """
            SELECT st.dep_sec, r.operator, r.name, r.color, r.route_type,
                   COALESCE(st.headsign, t.headsign), s.platform,
                   st.trip, st.seq, $trainTypeColumn
            FROM stop_times st
            JOIN trips t ON t.trip_no = st.trip
            JOIN routes r ON r.route_id = t.route_id
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE st.station_id IN ($stationPlaceholders) AND st.can_board = 1 AND st.dep_sec >= $minSec
              AND t.service_id IN ($placeholders)
            ORDER BY st.dep_sec
            LIMIT $limit
        """.trimIndent()
        val args = stationIds.toTypedArray() + services.toTypedArray()

        val list = ArrayList<Departure>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val isBus = c.getInt(4) == 3
                val platform: String? = c.getString(6)
                list.add(
                    Departure(
                        operatorLabel = shorten(c.getString(1) ?: "", 6),
                        lineName = shorten(c.getString(2) ?: "", 8),
                        lineColor = 0xFF000000L or c.getInt(3).toLong(),
                        headsign = tidyHeadsign(c.getString(5) ?: ""),
                        detail = platformText(platform, isBus),
                        time = midnight.plusSeconds(c.getLong(0)),
                        isBus = isBus,
                        tripNo = c.getLong(7),
                        seq = c.getInt(8),
                        trainType = c.getString(9) ?: ""
                    )
                )
            }
        }
        return list
    }
    // 便の全停留所(経由地)と時刻。乗る停留所までは出発時刻、それ以降は到着時刻
    fun tripStops(d: Departure): List<TripStop> {
        val rows = ArrayList<RawStop>()
        db.rawQuery(
            """
            SELECT st.seq, s.name, s.platform, st.arr_sec, st.dep_sec
            FROM stop_times st
            JOIN stops s ON s.stop_id = st.stop_id
            WHERE st.trip = ${d.tripNo}
            ORDER BY st.seq
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    RawStop(c.getInt(0), c.getString(1) ?: "", c.getString(2), c.getInt(3), c.getInt(4))
                )
            }
        }
        val boardDep = rows.firstOrNull { it.seq == d.seq }?.dep ?: return emptyList()
        val midnight = d.time.minusSeconds(boardDep.toLong())
        return rows.map { r ->
            val sec = if (r.seq <= d.seq) r.dep else r.arr
            TripStop(
                seq = r.seq,
                name = r.name,
                platform = platformText(r.platform, d.isBus),
                time = midnight.plusSeconds(sec.toLong())
            )
        }
    }

    private class RawStop(
        val seq: Int,
        val name: String,
        val platform: String?,
        val arr: Int,
        val dep: Int
    )
    // 長い名前を「…」で切る
    private fun shorten(s: String, max: Int): String =
        if (s.length > max) s.take(max - 1) + "…" else s

    // 「市街地循環(市街地循環)」のように同じ語が重なっているときは1つにする
    private fun tidyHeadsign(h: String): String {
        val open = h.indexOf('(')
        if (open > 0 && h.endsWith(")")) {
            val base = h.substring(0, open)
            val inner = h.substring(open + 1, h.length - 1)
            if (base == inner) return base
        }
        return h
    }

    // のりば番号(空と「0」は表示しない)
    private fun platformText(platform: String?, isBus: Boolean): String {
        if (platform.isNullOrBlank() || platform == "0") return ""
        return if (isBus) "${platform}番のりば" else "${platform}番線"
    }
}
