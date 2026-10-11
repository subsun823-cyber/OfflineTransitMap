package com.example.offlinetransitmap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class CrossingMemo(
    val isFavorite: Boolean = false,
    val visited: Boolean = false,
    val visitedDate: String = "",
    val recommendedLens: String = "",
    val bestLightingTime: String = "",
    val notes: String = ""
)

data class LevelCrossingItem(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val detail: String,
    val railwayLine: String = "その他・専用線",
    val distanceMeters: Int? = null,
    val sunPosition: SunPosition? = null,
    val memo: CrossingMemo? = null
)

/**
 * 路線別踏切統計データ
 */
data class RailwayLineCrossingStats(
    val lineName: String,
    val totalCount: Int,
    val visitedCount: Int,
    val crossings: List<LevelCrossingItem>
) {
    val progressRatio: Float get() = if (totalCount > 0) visitedCount.toFloat() / totalCount else 0f
    val progressPercentText: String get() = "%.1f%%".format(progressRatio * 100f)
}

/**
 * 踏切全体の制覇統計
 */
data class OverallConquestStats(
    val totalCount: Int,
    val visitedCount: Int,
    val lineCount: Int,
    val lineStats: List<RailwayLineCrossingStats>
) {
    val progressRatio: Float get() = if (totalCount > 0) visitedCount.toFloat() / totalCount else 0f
    val progressPercentText: String get() = "%.1f%%".format(progressRatio * 100f)
}

object CrossingRepository {
    private const val PREF_NAME = "crossing_memos"

    // 路線判定キャッシュ
    private data class StationInfo(val id: String, val name: String, val lat: Double, val lon: Double)
    private var cachedStations: List<StationInfo>? = null
    private var cachedStationRoutes: Map<String, List<Pair<String, String>>>? = null

    private fun ensureStationCache(timetableDb: SQLiteDatabase?) {
        if (cachedStations != null || timetableDb == null) return
        try {
            val sList = mutableListOf<StationInfo>()
            timetableDb.rawQuery("SELECT station_id, name, lat, lon FROM stations WHERE kind = 'rail'", null).use { c ->
                while (c.moveToNext()) {
                    sList.add(StationInfo(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3)))
                }
            }
            cachedStations = sList

            val rMap = mutableMapOf<String, MutableList<Pair<String, String>>>()
            val sql = """
                SELECT DISTINCT stm.station_id, r.operator, r.name 
                FROM stop_times stm
                JOIN trips t ON t.trip_no = stm.trip
                JOIN routes r ON r.route_id = t.route_id
                WHERE r.route_type != 3
            """.trimIndent()
            timetableDb.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val sid = c.getString(0)
                    val op = c.getString(1)
                    val rname = c.getString(2)
                    rMap.getOrPut(sid) { mutableListOf() }.add(Pair(op, rname))
                }
            }
            cachedStationRoutes = rMap
        } catch (e: Exception) {}
    }

    /**
     * 各踏切の最寄り鉄道・所属路線を推定
     */
    fun resolveRailwayLine(cname: String, clat: Double, clon: Double, timetableDb: SQLiteDatabase?): String {
        ensureStationCache(timetableDb)
        val stations = cachedStations ?: return "その他・専用線"
        val stationRoutes = cachedStationRoutes ?: emptyMap()

        var nearestStation: StationInfo? = null
        var minDistance = Double.MAX_VALUE

        for (st in stations) {
            val dist = calcDistanceMeters(clat, clon, st.lat, st.lon).toDouble()
            if (dist < minDistance) {
                minDistance = dist
                nearestStation = st
            }
        }

        if (nearestStation != null && minDistance < 5000.0) {
            val routes = stationRoutes[nearestStation.id]
            if (!routes.isNullOrEmpty()) {
                val matched = routes.firstOrNull { (op, rname) ->
                    cname.contains(rname) || cname.contains(op)
                } ?: routes.first()
                return "${matched.first} ${matched.second}"
            } else {
                return "鉄道（${nearestStation.name}駅 周辺）"
            }
        }
        return "その他・専用線"
    }

    /**
     * 全踏切を取得
     */
    fun getAllCrossings(
        poiDb: SQLiteDatabase,
        timetableDb: SQLiteDatabase?,
        context: Context,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<LevelCrossingItem> {
        val memos = getAllMemos(context)
        val list = mutableListOf<LevelCrossingItem>()

        val sql = "SELECT id, name, lat, lon, detail FROM pois WHERE kind = 'level_crossing'"
        try {
            poiDb.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val rawName = c.getString(1)
                    val lat = c.getDouble(2)
                    val lon = c.getDouble(3)
                    val detail = c.getString(4) ?: ""
                    val name = if (!rawName.isNullOrBlank()) rawName else "（名称未設定の踏切）"
                    val line = resolveRailwayLine(name, lat, lon, timetableDb)
                    val dist = if (userLat != null && userLon != null) calcDistanceMeters(userLat, userLon, lat, lon) else null
                    val sun = SunPositionCalculator.calculate(lat, lon)
                    val memo = memos[id]
                    list.add(
                        LevelCrossingItem(
                            id = id,
                            name = name,
                            lat = lat,
                            lon = lon,
                            detail = detail,
                            railwayLine = line,
                            distanceMeters = dist,
                            sunPosition = sun,
                            memo = memo
                        )
                    )
                }
            }
        } catch (e: Exception) {}

        return list
    }

    /**
     * 路線別・全体の踏切制覇統計を取得
     */
    fun getConquestStats(
        poiDb: SQLiteDatabase,
        timetableDb: SQLiteDatabase?,
        context: Context
    ): OverallConquestStats {
        val all = getAllCrossings(poiDb, timetableDb, context)
        val grouped = all.groupBy { it.railwayLine }

        val lineStats = grouped.map { (lineName, crossings) ->
            val visited = crossings.count { it.memo?.visited == true }
            RailwayLineCrossingStats(
                lineName = lineName,
                totalCount = crossings.size,
                visitedCount = visited,
                crossings = crossings.sortedWith(
                    compareByDescending<LevelCrossingItem> { it.memo?.isFavorite == true }
                        .thenBy { it.name }
                )
            )
        }.sortedWith(
            compareByDescending<RailwayLineCrossingStats> { it.totalCount }
                .thenByDescending { it.visitedCount }
        )

        val totalCrossings = all.size
        val totalVisited = all.count { it.memo?.visited == true }

        return OverallConquestStats(
            totalCount = totalCrossings,
            visitedCount = totalVisited,
            lineCount = lineStats.size,
            lineStats = lineStats
        )
    }

    /**
     * pois.db から現在地周辺の踏切を取得（距離順）
     */
    fun getNearbyCrossings(
        db: SQLiteDatabase,
        userLat: Double,
        userLon: Double,
        context: Context,
        limit: Int = 40,
        timetableDb: SQLiteDatabase? = null
    ): List<LevelCrossingItem> {
        val memos = getAllMemos(context)
        val list = mutableListOf<LevelCrossingItem>()

        val latRange = 0.25
        val lonRange = 0.25

        val sql = """
            SELECT id, name, lat, lon, detail 
            FROM pois 
            WHERE kind = 'level_crossing' 
              AND lat BETWEEN ? AND ? 
              AND lon BETWEEN ? AND ?
        """.trimIndent()

        val args = arrayOf(
            (userLat - latRange).toString(),
            (userLat + latRange).toString(),
            (userLon - lonRange).toString(),
            (userLon + lonRange).toString()
        )

        try {
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val rawName = c.getString(1)
                    val lat = c.getDouble(2)
                    val lon = c.getDouble(3)
                    val detail = c.getString(4) ?: ""
                    val name = if (!rawName.isNullOrBlank()) rawName else "（名称未設定の踏切）"
                    val line = resolveRailwayLine(name, lat, lon, timetableDb)
                    val dist = calcDistanceMeters(userLat, userLon, lat, lon)
                    val sun = SunPositionCalculator.calculate(lat, lon)
                    val memo = memos[id]
                    list.add(
                        LevelCrossingItem(
                            id = id,
                            name = name,
                            lat = lat,
                            lon = lon,
                            detail = detail,
                            railwayLine = line,
                            distanceMeters = dist,
                            sunPosition = sun,
                            memo = memo
                        )
                    )
                }
            }
        } catch (e: Exception) {}

        return list.sortedWith(
            compareByDescending<LevelCrossingItem> { it.memo?.isFavorite == true }
                .thenBy { it.distanceMeters ?: Int.MAX_VALUE }
        ).take(limit)
    }

    /**
     * キーワードで踏切を検索
     */
    fun searchCrossings(
        db: SQLiteDatabase,
        query: String,
        userLat: Double?,
        userLon: Double?,
        context: Context,
        limit: Int = 30,
        timetableDb: SQLiteDatabase? = null
    ): List<LevelCrossingItem> {
        val q = query.trim()
        val memos = getAllMemos(context)
        val list = mutableListOf<LevelCrossingItem>()

        val sql = if (q.isEmpty()) {
            "SELECT id, name, lat, lon, detail FROM pois WHERE kind = 'level_crossing' LIMIT ?"
        } else {
            "SELECT id, name, lat, lon, detail FROM pois WHERE kind = 'level_crossing' AND (name LIKE ? OR detail LIKE ?) LIMIT ?"
        }
        val args = if (q.isEmpty()) arrayOf(limit.toString()) else arrayOf("%$q%", "%$q%", limit.toString())

        try {
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val rawName = c.getString(1)
                    val lat = c.getDouble(2)
                    val lon = c.getDouble(3)
                    val detail = c.getString(4) ?: ""
                    val name = if (!rawName.isNullOrBlank()) rawName else "（名称未設定の踏切）"
                    val line = resolveRailwayLine(name, lat, lon, timetableDb)
                    val dist = if (userLat != null && userLon != null) calcDistanceMeters(userLat, userLon, lat, lon) else null
                    val sun = SunPositionCalculator.calculate(lat, lon)
                    val memo = memos[id]
                    list.add(
                        LevelCrossingItem(
                            id = id,
                            name = name,
                            lat = lat,
                            lon = lon,
                            detail = detail,
                            railwayLine = line,
                            distanceMeters = dist,
                            sunPosition = sun,
                            memo = memo
                        )
                    )
                }
            }
        } catch (e: Exception) {}

        return if (userLat != null && userLon != null) {
            list.sortedWith(
                compareByDescending<LevelCrossingItem> { it.memo?.isFavorite == true }
                    .thenBy { it.distanceMeters ?: Int.MAX_VALUE }
            )
        } else {
            list
        }
    }

    /**
     * 地図上に踏切スポットをオーバーレイ表示するための GeoJSON を生成（訪問状態付き）
     */
    fun getCrossingsGeoJson(db: SQLiteDatabase, context: Context): String {
        val memos = getAllMemos(context)
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        try {
            db.rawQuery(
                "SELECT id, name, lat, lon FROM pois WHERE kind = 'level_crossing'",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    if (!first) sb.append(",")
                    first = false
                    val id = c.getString(0)
                    val rawName = c.getString(1)
                    val name = if (!rawName.isNullOrBlank()) rawName else "（名称未設定の踏切）"
                    val lat = c.getDouble(2)
                    val lon = c.getDouble(3)
                    val isVisited = memos[id]?.visited == true
                    sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{"id":"$id","name":"${name.replace("\"", "\\\"")}","kind":"level_crossing","visited":$isVisited}}""")
                }
            }
        } catch (e: Exception) {}
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 訪問状態のトグル切り替え（チェックイン）
     */
    fun toggleVisited(context: Context, crossingId: String): CrossingMemo {
        val current = loadMemo(context, crossingId) ?: CrossingMemo()
        val newVisited = !current.visited
        val todayStr = if (newVisited) LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) else ""
        val updated = current.copy(visited = newVisited, visitedDate = todayStr)
        saveMemo(context, crossingId, updated)
        return updated
    }

    /**
     * 踏切メモの保存
     */
    fun saveMemo(context: Context, crossingId: String, memo: CrossingMemo) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("isFavorite", memo.isFavorite)
            put("visited", memo.visited)
            put("visitedDate", memo.visitedDate)
            put("recommendedLens", memo.recommendedLens)
            put("bestLightingTime", memo.bestLightingTime)
            put("notes", memo.notes)
        }
        prefs.edit().putString(crossingId, json.toString()).apply()
    }

    /**
     * 踏切メモの読み込み
     */
    fun loadMemo(context: Context, crossingId: String): CrossingMemo? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(crossingId, null) ?: return null
        return try {
            val json = JSONObject(str)
            CrossingMemo(
                isFavorite = json.optBoolean("isFavorite", false),
                visited = json.optBoolean("visited", false),
                visitedDate = json.optString("visitedDate", ""),
                recommendedLens = json.optString("recommendedLens", ""),
                bestLightingTime = json.optString("bestLightingTime", ""),
                notes = json.optString("notes", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 全踏切メモのマップを取得
     */
    fun getAllMemos(context: Context): Map<String, CrossingMemo> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val result = mutableMapOf<String, CrossingMemo>()
        for ((key, value) in prefs.all) {
            if (value is String) {
                try {
                    val json = JSONObject(value)
                    result[key] = CrossingMemo(
                        isFavorite = json.optBoolean("isFavorite", false),
                        visited = json.optBoolean("visited", false),
                        visitedDate = json.optString("visitedDate", ""),
                        recommendedLens = json.optString("recommendedLens", ""),
                        bestLightingTime = json.optString("bestLightingTime", ""),
                        notes = json.optString("notes", "")
                    )
                } catch (e: Exception) {}
            }
        }
        return result
    }
}
