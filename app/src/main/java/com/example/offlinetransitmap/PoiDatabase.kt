package com.example.offlinetransitmap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

class PoiDatabase private constructor(val db: SQLiteDatabase) {

    companion object {
        private const val DB_NAME = "pois.db"
        @Volatile
        private var instance: PoiDatabase? = null

        fun open(context: Context): PoiDatabase? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                try {
                    val dbDir = context.getExternalFilesDir("pois") ?: context.filesDir
                    val file = File(dbDir, DB_NAME)

                    // assets に pois.db があるか確認
                    var assetStream = try {
                        context.assets.open("bootstrap/$DB_NAME")
                    } catch (e: Exception) {
                        null
                    }

                    if (assetStream != null) {
                        val assetLength = try {
                            context.assets.openFd("bootstrap/$DB_NAME").length
                        } catch (e: Exception) {
                            -1L
                        }

                        // ファイルが存在しない、またはサイズが一致しない場合はコピー
                        if (!file.exists() || (assetLength > 0 && file.length() != assetLength)) {
                            file.parentFile?.mkdirs()
                            val tempFile = File(dbDir, "$DB_NAME.tmp")
                            assetStream.use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (file.exists()) file.delete()
                            tempFile.renameTo(file)
                        } else {
                            assetStream.close()
                        }
                    }

                    if (!file.exists()) return null

                    val sqliteDb = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val poiDb = PoiDatabase(sqliteDb)
                    instance = poiDb
                    return poiDb
                } catch (e: Exception) {
                    return null
                }
            }
        }
    }

    /**
     * 施設検索（コンビニ、店舗、病院、学校などの全施設を高速検索）
     */
    fun searchPois(
        query: String,
        categoryFilter: PlaceCategory? = null,
        limit: Int = 40
    ): List<PlaceItem> {
        val q = query.trim()
        if (q.isEmpty() && categoryFilter == null) return emptyList()

        val results = ArrayList<PlaceItem>()
        val seen = HashSet<String>()

        // ブランド・業態エイリアスの正規化
        val normalizedQuery = when (q.lowercase()) {
            "ファミマ", "ふぁみま" -> "ファミリーマート"
            "マック", "マクド", "まっく" -> "マクドナルド"
            "スタバ", "すたば" -> "スターバックス"
            "セブン", "せぶん" -> "セブン-イレブン"
            else -> q
        }

        // キーワードによる業種一括検索
        val kindFilter = when (normalizedQuery) {
            "コンビニ", "こんびに" -> "convenience"
            "スーパー", "すーぱー" -> "supermarket"
            "薬局", "ドラッグストア", "どらっぐすとあ" -> "pharmacy"
            "病院", "クリニック" -> "hospital"
            "カフェ", "喫茶店" -> "cafe"
            "郵便局", "ゆうびんきょく" -> "post_office"
            "交番", "警察" -> "police"
            "学校", "小学校", "中学校", "高校", "大学" -> "school"
            else -> null
        }

        if (kindFilter != null) {
            val (sql, args) = when (kindFilter) {
                "hospital" -> Pair(
                    "SELECT id, name, lat, lon, kind, category, detail FROM pois WHERE kind IN ('hospital', 'clinic', 'doctors') LIMIT ?",
                    arrayOf(limit.toString())
                )
                "school" -> Pair(
                    "SELECT id, name, lat, lon, kind, category, detail FROM pois WHERE kind IN ('school', 'university', 'college') LIMIT ?",
                    arrayOf(limit.toString())
                )
                else -> Pair(
                    "SELECT id, name, lat, lon, kind, category, detail FROM pois WHERE kind = ? LIMIT ?",
                    arrayOf(kindFilter, limit.toString())
                )
            }
            try {
                db.rawQuery(sql, args).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        if (seen.add(id)) {
                            val name = c.getString(1)
                            val lat = c.getDouble(2)
                            val lon = c.getDouble(3)
                            val catStr = c.getString(5)
                            val detail = c.getString(6)
                            val cat = runCatching { PlaceCategory.valueOf(catStr) }.getOrDefault(PlaceCategory.FACILITY)
                            if (categoryFilter == null || cat == categoryFilter) {
                                results.add(PlaceItem(id, name, "", lat, lon, cat, detail))
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            return results
        }

        // カテゴリ単体指定でクエリが空の場合
        if (normalizedQuery.isEmpty() && categoryFilter != null) {
            try {
                db.rawQuery(
                    "SELECT id, name, lat, lon, kind, category, detail FROM pois WHERE category = ? LIMIT ?",
                    arrayOf(categoryFilter.name, limit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        if (seen.add(id)) {
                            val name = c.getString(1)
                            val lat = c.getDouble(2)
                            val lon = c.getDouble(3)
                            val detail = c.getString(6)
                            results.add(PlaceItem(id, name, "", lat, lon, categoryFilter, detail))
                        }
                    }
                }
            } catch (e: Exception) {}
            return results
        }

        // 名称による検索 (前方一致を優先し、残りを部分一致で取得)
        try {
            // 1. 前方一致
            db.rawQuery(
                "SELECT id, name, lat, lon, kind, category, detail FROM pois WHERE name LIKE ? LIMIT ?",
                arrayOf("$normalizedQuery%", limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (seen.add(id)) {
                        val name = c.getString(1)
                        val lat = c.getDouble(2)
                        val lon = c.getDouble(3)
                        val catStr = c.getString(5)
                        val detail = c.getString(6)
                        val cat = runCatching { PlaceCategory.valueOf(catStr) }.getOrDefault(PlaceCategory.FACILITY)
                        if (categoryFilter == null || cat == categoryFilter) {
                            results.add(PlaceItem(id, name, "", lat, lon, cat, detail))
                        }
                    }
                }
            }

            // 2. 部分一致 (残りの枠)
            val remaining = limit - results.size
            if (remaining > 0) {
                db.rawQuery(
                    "SELECT id, name, lat, lon, kind, category, detail FROM pois WHERE name LIKE ? AND name NOT LIKE ? LIMIT ?",
                    arrayOf("%$normalizedQuery%", "$normalizedQuery%", remaining.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        if (seen.add(id)) {
                            val name = c.getString(1)
                            val lat = c.getDouble(2)
                            val lon = c.getDouble(3)
                            val catStr = c.getString(5)
                            val detail = c.getString(6)
                            val cat = runCatching { PlaceCategory.valueOf(catStr) }.getOrDefault(PlaceCategory.FACILITY)
                            if (categoryFilter == null || cat == categoryFilter) {
                                results.add(PlaceItem(id, name, "", lat, lon, cat, detail))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        return results
    }
}
