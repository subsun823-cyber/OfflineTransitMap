package com.example.offlinetransitmap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import kotlin.math.roundToInt

data class CrossingMemo(
    val isFavorite: Boolean = false,
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
    val distanceMeters: Int? = null,
    val sunPosition: SunPosition? = null,
    val memo: CrossingMemo? = null
)

object CrossingRepository {
    private const val PREF_NAME = "crossing_memos"

    /**
     * pois.db から現在地周辺の踏切を取得（距離順）
     */
    fun getNearbyCrossings(
        db: SQLiteDatabase,
        userLat: Double,
        userLon: Double,
        context: Context,
        limit: Int = 40
    ): List<LevelCrossingItem> {
        val memos = getAllMemos(context)
        val list = mutableListOf<LevelCrossingItem>()

        // 粗いバウンディングボックス (約 ±0.2度 ≒ 約20km)
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
                    val name = if (rawName.isNotBlank()) rawName else "踏切"
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
                            distanceMeters = dist,
                            sunPosition = sun,
                            memo = memo
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // テーブル未準備等のフェイルセーフ
        }

        // 距離昇順にソート（お気に入りは優先）
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
        limit: Int = 30
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
                    val name = if (rawName.isNotBlank()) rawName else "踏切"
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
     * 地図上に踏切スポットをオーバーレイ表示するための GeoJSON を生成
     */
    fun getCrossingsGeoJson(db: SQLiteDatabase): String {
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
                    val name = if (rawName.isNotBlank()) rawName else "踏切"
                    val lat = c.getDouble(2)
                    val lon = c.getDouble(3)
                    sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{"id":"$id","name":"${name.replace("\"", "\\\"")}","kind":"level_crossing"}}""")
                }
            }
        } catch (e: Exception) {}
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 踏切メモの保存
     */
    fun saveMemo(context: Context, crossingId: String, memo: CrossingMemo) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("isFavorite", memo.isFavorite)
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
