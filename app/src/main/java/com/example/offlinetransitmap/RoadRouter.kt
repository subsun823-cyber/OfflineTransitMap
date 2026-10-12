package com.example.offlinetransitmap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class TravelMode {
    WALK,
    BICYCLE
}

data class RoadRouteResult(
    val path: List<Pair<Double, Double>>,
    val distanceMeters: Int,
    val durationMinutes: Int,
    val isRoadBased: Boolean
)

fun bicycleDurationMinutes(meters: Int): Int =
    (meters / 250.0).roundToInt().coerceAtLeast(1)

fun travelDurationMinutes(meters: Int, mode: TravelMode): Int = when (mode) {
    TravelMode.WALK -> walkDurationMinutes(meters)
    TravelMode.BICYCLE -> bicycleDurationMinutes(meters)
}

class RoadRouter private constructor(private val databases: List<SQLiteDatabase>) {

    companion object {
        private const val TAG = "RoadRouter"
        private const val DB_NAME = "roads.db"
        private const val ROADS_DB_VERSION = 9 // DB更新用バージョンコード (タイル境界クリップ&ステッチング roads.db v9)
        private const val GRID_CELL_DEG = 0.01 // 約1.1km
        private const val MAX_SNAP_DISTANCE_M = 2500 // 道路ノードまでの最大許容スナップ距離

        @Volatile
        private var instance: RoadRouter? = null

        fun reload(context: Context): RoadRouter? {
            synchronized(this) {
                try {
                    instance?.close()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Error closing existing RoadRouter databases", e)
                }
                instance = null
                return open(context)
            }
        }

        fun open(context: Context): RoadRouter? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                try {
                    val dbDir = context.getExternalFilesDir("roads") ?: context.filesDir
                    val file = File(dbDir, DB_NAME)
                    val prefs = context.getSharedPreferences("road_router_prefs", Context.MODE_PRIVATE)
                    val installedVersion = prefs.getInt("roads_db_installed_version", -1)

                    val assetStream = try {
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

                        val needsCopy = !file.exists() ||
                                installedVersion != ROADS_DB_VERSION ||
                                (assetLength > 0 && file.length() != assetLength)

                        if (needsCopy) {
                            file.parentFile?.mkdirs()
                            val tempFile = File(dbDir, "$DB_NAME.tmp")
                            if (tempFile.exists()) tempFile.delete()
                            assetStream.use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (file.exists()) file.delete()
                            val renamed = tempFile.renameTo(file)
                            if (renamed) {
                                prefs.edit().putInt("roads_db_installed_version", ROADS_DB_VERSION).apply()
                            }
                        } else {
                            assetStream.close()
                        }
                    }

                    // 探索対象の全SQLite道路DBファイルを収集 (roads.db + roads_*.db)
                    val dbFiles = ArrayList<File>()
                    if (file.exists()) {
                        dbFiles.add(file)
                    }

                    val extraDbs = dbDir.listFiles { f ->
                        f.isFile && f.name.endsWith(".db") && f.name != DB_NAME && f.length() > 1024
                    }
                    if (extraDbs != null) {
                        for (f in extraDbs) {
                            dbFiles.add(f)
                        }
                    }

                    if (dbFiles.isEmpty()) return null

                    val openedDbs = ArrayList<SQLiteDatabase>()
                    for (f in dbFiles) {
                        try {
                            val db = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                            openedDbs.add(db)
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Failed to open road database: ${f.name}", e)
                        }
                    }

                    if (openedDbs.isEmpty()) return null

                    val router = RoadRouter(openedDbs)
                    instance = router
                    return router
                } catch (e: Exception) {
                    android.util.Log.e("RoadRouter", "Failed to open road router database", e)
                    return null
                }
            }
        }

        private fun gridCellKey(lat: Double, lon: Double): Int {
            val y = (lat / GRID_CELL_DEG).toInt()
            val x = (lon / GRID_CELL_DEG).toInt()
            return ((y and 0xFFFF) shl 16) or (x and 0xFFFF)
        }

        fun makeGlobalNodeId(dbIdx: Int, localId: Int): Long {
            return (dbIdx.toLong() shl 32) or (localId.toLong() and 0xFFFFFFFFL)
        }
    }

    fun close() {
        for (db in databases) {
            try {
                if (db.isOpen) db.close()
            } catch (_: Exception) {}
        }
    }

    private data class GraphNode(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val dbIdx: Int
    )

    private data class GraphEdge(
        val toNode: Long,
        val distMeters: Int,
        val isWalk: Boolean,
        val isBike: Boolean,
        val roadClass: Int
    )

    private data class SearchState(
        val nodeId: Long,
        val cost: Double,
        val estimatedTotal: Double
    ) : Comparable<SearchState> {
        override fun compareTo(other: SearchState): Int =
            this.estimatedTotal.compareTo(other.estimatedTotal)
    }

    // 最寄りの道路ノードを検索 (周囲グリッド探索、全DB走査)
    fun findNearestNode(lat: Double, lon: Double): Pair<Long, Int>? {
        val y = (lat / GRID_CELL_DEG).toInt()
        val x = (lon / GRID_CELL_DEG).toInt()

        fun searchInRadius(r: Int): Pair<Long, Int>? {
            val gridKeys = ArrayList<Int>((2 * r + 1) * (2 * r + 1))
            for (dy in -r..r) {
                for (dx in -r..r) {
                    gridKeys.add((((y + dy) and 0xFFFF) shl 16) or ((x + dx) and 0xFFFF))
                }
            }
            var bestGlobalId: Long = -1L
            var bestDist = Int.MAX_VALUE

            for ((dbIdx, db) in databases.withIndex()) {
                if (!db.isOpen) continue
                for (chunk in gridKeys.chunked(500)) {
                    val inClause = chunk.joinToString(",")
                    val cursor = try {
                        db.rawQuery(
                            "SELECT id, lat, lon FROM road_nodes WHERE grid IN ($inClause)",
                            null
                        )
                    } catch (e: Exception) {
                        null
                    } ?: continue

                    cursor.use {
                        val idCol = it.getColumnIndexOrThrow("id")
                        val latCol = it.getColumnIndexOrThrow("lat")
                        val lonCol = it.getColumnIndexOrThrow("lon")
                        while (it.moveToNext()) {
                            val nid = it.getInt(idCol)
                            val nLat = it.getDouble(latCol)
                            val nLon = it.getDouble(lonCol)
                            val dist = calcDistanceMeters(lat, lon, nLat, nLon)
                            if (dist < bestDist) {
                                bestDist = dist
                                bestGlobalId = makeGlobalNodeId(dbIdx, nid)
                            }
                        }
                    }
                }
            }
            return if (bestGlobalId != -1L) Pair(bestGlobalId, bestDist) else null
        }

        // まず半径2セル (約1.5km四方) で探索
        val near = searchInRadius(2)
        if (near != null && near.second <= 2500) return near

        // 見つからない場合は半径4セル (約3km四方) で探索
        val expanded = searchInRadius(4)
        return if (expanded != null && expanded.second <= 4000) expanded else null
    }

    // 2点間の道路ナビ経路を探索 (徒歩 / 自転車)
    fun route(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        mode: TravelMode = TravelMode.WALK
    ): RoadRouteResult {
        val directMeters = calcDistanceMeters(originLat, originLon, destLat, destLon)
        val directDuration = travelDurationMinutes(directMeters, mode)
        val directFallback = RoadRouteResult(
            path = listOf(Pair(originLat, originLon), Pair(destLat, destLon)),
            distanceMeters = directMeters,
            durationMinutes = directDuration,
            isRoadBased = false
        )

        // 距離が極めて近い場合（30m未満）はそのまま直線
        if (directMeters < 30) {
            return directFallback
        }

        val startSnap = findNearestNode(originLat, originLon) ?: return directFallback
        val goalSnap = findNearestNode(destLat, destLon) ?: return directFallback

        val startNodeId = startSnap.first
        val goalNodeId = goalSnap.first

        // 探索領域のノードとエッジを読み込み (マージン0.05: 約5.5kmで迂回路も確実にカバー)
        val minLat = minOf(originLat, destLat) - 0.05
        val maxLat = maxOf(originLat, destLat) + 0.05
        val minLon = minOf(originLon, destLon) - 0.05
        val maxLon = maxOf(originLon, destLon) + 0.05

        val yMin = (minLat / GRID_CELL_DEG).toInt()
        val yMax = (maxLat / GRID_CELL_DEG).toInt()
        val xMin = (minLon / GRID_CELL_DEG).toInt()
        val xMax = (maxLon / GRID_CELL_DEG).toInt()

        val gridKeys = ArrayList<Int>()
        for (y in yMin..yMax) {
            for (x in xMin..xMax) {
                gridKeys.add(((y and 0xFFFF) shl 16) or (x and 0xFFFF))
            }
        }

        if (gridKeys.isEmpty() || gridKeys.size > 2500) return directFallback

        // 領域内のノードをマップに展開 (全DB)
        val nodesMap = HashMap<Long, GraphNode>()
        for ((dbIdx, db) in databases.withIndex()) {
            if (!db.isOpen) continue
            for (chunk in gridKeys.chunked(500)) {
                val inClause = chunk.joinToString(",")
                val nodeCursor = try {
                    db.rawQuery(
                        "SELECT id, lat, lon FROM road_nodes WHERE grid IN ($inClause)",
                        null
                    )
                } catch (e: Exception) {
                    null
                } ?: continue

                nodeCursor.use {
                    val idCol = it.getColumnIndexOrThrow("id")
                    val latCol = it.getColumnIndexOrThrow("lat")
                    val lonCol = it.getColumnIndexOrThrow("lon")
                    while (it.moveToNext()) {
                        val nid = it.getInt(idCol)
                        val globalId = makeGlobalNodeId(dbIdx, nid)
                        nodesMap[globalId] = GraphNode(globalId, it.getDouble(latCol), it.getDouble(lonCol), dbIdx)
                    }
                }
            }
        }

        if (startNodeId == goalNodeId) {
            val node = nodesMap[startNodeId]
            if (node != null) {
                val p = listOf(Pair(originLat, originLon), Pair(node.lat, node.lon), Pair(destLat, destLon))
                return RoadRouteResult(
                    path = p,
                    distanceMeters = directMeters,
                    durationMinutes = directDuration,
                    isRoadBased = true
                )
            }
            return directFallback
        }

        if (!nodesMap.containsKey(startNodeId) || !nodesMap.containsKey(goalNodeId)) {
            return directFallback
        }

        val goalNode = nodesMap[goalNodeId] ?: return directFallback

        // 領域内のエッジを隣接リストに展開 (全DB)
        val modeColumn = if (mode == TravelMode.BICYCLE) "is_bike" else "is_walk"
        val adj = HashMap<Long, ArrayList<GraphEdge>>()
        for ((dbIdx, db) in databases.withIndex()) {
            if (!db.isOpen) continue
            for (chunk in gridKeys.chunked(500)) {
                val inClause = chunk.joinToString(",")
                val edgeCursor = try {
                    db.rawQuery(
                        "SELECT u, v, dist_m, is_walk, is_bike, road_class FROM road_edges WHERE $modeColumn = 1 AND grid IN ($inClause)",
                        null
                    )
                } catch (e: Exception) {
                    null
                } ?: continue

                edgeCursor.use {
                    val uCol = it.getColumnIndexOrThrow("u")
                    val vCol = it.getColumnIndexOrThrow("v")
                    val distCol = it.getColumnIndexOrThrow("dist_m")
                    val walkCol = it.getColumnIndexOrThrow("is_walk")
                    val bikeCol = it.getColumnIndexOrThrow("is_bike")
                    val rcCol = it.getColumnIndexOrThrow("road_class")

                    while (it.moveToNext()) {
                        val localU = it.getInt(uCol)
                        val localV = it.getInt(vCol)
                        val d = it.getInt(distCol)
                        val isWalk = it.getInt(walkCol) == 1
                        val isBike = it.getInt(bikeCol) == 1
                        val rc = it.getInt(rcCol)

                        val u = makeGlobalNodeId(dbIdx, localU)
                        val v = makeGlobalNodeId(dbIdx, localV)

                        if (nodesMap.containsKey(u) && nodesMap.containsKey(v)) {
                            adj.getOrPut(u) { ArrayList() }.add(GraphEdge(v, d, isWalk, isBike, rc))
                            adj.getOrPut(v) { ArrayList() }.add(GraphEdge(u, d, isWalk, isBike, rc))
                        }
                    }
                }
            }
        }

        // 都県境ステッチング（異なるDB間の近接ノードを連結してシームレスに探索可能にする）
        if (databases.size > 1) {
            val nodesByGrid = HashMap<Int, ArrayList<GraphNode>>()
            for (node in nodesMap.values) {
                val key = gridCellKey(node.lat, node.lon)
                nodesByGrid.getOrPut(key) { ArrayList() }.add(node)
            }

            for ((key, cellNodes) in nodesByGrid) {
                val cy = (key shr 16) and 0xFFFF
                val cx = key and 0xFFFF
                // 同一グリッドおよび隣接グリッドを探索
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val neighborKey = (((cy + dy) and 0xFFFF) shl 16) or ((cx + dx) and 0xFFFF)
                        val neighborNodes = nodesByGrid[neighborKey] ?: continue
                        for (n1 in cellNodes) {
                            for (n2 in neighborNodes) {
                                if (n1.dbIdx != n2.dbIdx && n1.id < n2.id) {
                                    val dist = calcDistanceMeters(n1.lat, n1.lon, n2.lat, n2.lon)
                                    // 25m 以内の近接ノードを境界接続エッジで結ぶ
                                    if (dist <= 25) {
                                        val d = maxOf(1, dist)
                                        adj.getOrPut(n1.id) { ArrayList() }.add(GraphEdge(n2.id, d, isWalk = true, isBike = true, roadClass = 4))
                                        adj.getOrPut(n2.id) { ArrayList() }.add(GraphEdge(n1.id, d, isWalk = true, isBike = true, roadClass = 4))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // A* 探索 (大通り優先・走りやすさ・右左折ペナルティを考慮した高品質経路探索)
        val dist = HashMap<Long, Double>()
        val prev = HashMap<Long, Pair<Long, GraphEdge>>()
        val pq = PriorityQueue<SearchState>()

        dist[startNodeId] = 0.0
        val initialH = calcDistanceMeters(nodesMap[startNodeId]!!.lat, nodesMap[startNodeId]!!.lon, goalNode.lat, goalNode.lon) * 0.70
        pq.add(SearchState(startNodeId, 0.0, initialH))

        var found = false

        while (pq.isNotEmpty()) {
            val curr = pq.poll() ?: break
            val u = curr.nodeId

            if (u == goalNodeId) {
                found = true
                break
            }

            if (curr.cost > (dist[u] ?: Double.MAX_VALUE)) {
                continue
            }

            val edges = adj[u] ?: continue
            val uNode = nodesMap[u] ?: continue
            for (edge in edges) {
                val v = edge.toNode
                val vNode = nodesMap[v] ?: continue

                // 道路種別によるコスト係数（走りやすさ・大通りの優先度）
                val classFactor = when (mode) {
                    TravelMode.BICYCLE -> when (edge.roadClass) {
                        1 -> 0.82   // 大通り・主要幹線 (幅広・舗装状態良好・直進性高: 18%優遇)
                        2 -> 0.88   // 主要中規模道 (12%優遇)
                        3 -> 0.85   // 専用自転車道 (15%優遇: 幹線道路からの不要な離脱を防止)
                        4 -> 1.00   // 一般生活道路 (基準)
                        5 -> 1.15   // 歩行者道・広場 (徐行・押し歩き)
                        6 -> 1.35   // 細街路・裏道・路地 (見通し悪く低速・回避)
                        7 -> 1.60   // 未舗装路 (走行困難)
                        8 -> 8.00   // 階段
                        else -> 1.00
                    }
                    TravelMode.WALK -> when (edge.roadClass) {
                        1 -> 0.88   // 大通り (幅広歩道・街灯・わかりやすい: 12%優遇)
                        2 -> 0.90   // 主要道
                        3 -> 0.85   // 自転車・歩行者専用道
                        4 -> 1.00   // 生活道路 (基準)
                        5 -> 0.80   // 歩行者専用道・緑道・公園道 (快適安全: 20%優遇)
                        6 -> 1.25   // 細い路地・裏道 (迷いやすく見通しが悪い)
                        7 -> 1.35   // 未舗装路
                        8 -> 1.50   // 階段 (疲れるため迂回路があれば回避)
                        else -> 1.00
                    }
                }

                var edgeCost = edge.distMeters * classFactor

                // 直進性・右左折ペナルティ（無駄な曲がり角・ジグザグ・頻繁な右左折の強力な抑制）
                if (prev.containsKey(u)) {
                    val fromNodeId = prev[u]!!.first
                    val prevEdge = prev[u]!!.second
                    val fromNode = nodesMap[fromNodeId]
                    if (fromNode != null) {
                        val b1 = calcBearing(fromNode.lat, fromNode.lon, uNode.lat, uNode.lon)
                        val b2 = calcBearing(uNode.lat, uNode.lon, vNode.lat, vNode.lon)
                        val diff = kotlin.math.abs(angleDiff(b1, b2))

                        var isConsecutive = false
                        if (diff > 35.0 && prev.containsKey(fromNodeId)) {
                            val fromFromNodeId = prev[fromNodeId]!!.first
                            val fromFromNode = nodesMap[fromFromNodeId]
                            if (fromFromNode != null) {
                                val b0 = calcBearing(fromFromNode.lat, fromFromNode.lon, fromNode.lat, fromNode.lon)
                                isConsecutive = kotlin.math.abs(angleDiff(b0, b1)) > 35.0
                            }
                        }

                        val turnPenalty = computeTurnPenalty(
                            angleDiffDeg = diff,
                            prevEdgeDistMeters = prevEdge.distMeters.toDouble(),
                            isConsecutiveTurn = isConsecutive,
                            prevRoadClass = prevEdge.roadClass,
                            currRoadClass = edge.roadClass,
                            mode = mode
                        )
                        edgeCost += turnPenalty
                    }
                }

                val newCost = curr.cost + edgeCost

                if (newCost < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = newCost
                    prev[v] = Pair(u, edge)
                    // A* のヒューリスティック関数 (大通りの最大割引係数 0.70 を乗算して許容性を保持)
                    val h = calcDistanceMeters(vNode.lat, vNode.lon, goalNode.lat, goalNode.lon) * 0.70
                    pq.add(SearchState(v, newCost, newCost + h))
                }
            }
        }

        if (!found) {
            return directFallback
        }

        // 経路の復元 (通過ノード列を復元)
        val pathNodes = ArrayList<Long>()
        var curr = goalNodeId
        pathNodes.add(curr)
        while (curr != startNodeId) {
            val p = prev[curr] ?: break
            curr = p.first
            pathNodes.add(curr)
        }
        pathNodes.reverse()

        // 道路座標列の構築
        val fullPath = ArrayList<Pair<Double, Double>>()
        fullPath.add(Pair(originLat, originLon))

        for (nid in pathNodes) {
            val node = nodesMap[nid]
            if (node != null) {
                fullPath.add(Pair(node.lat, node.lon))
            }
        }

        fullPath.add(Pair(destLat, destLon))

        // 重複する連続点の除去
        val cleanedPath = ArrayList<Pair<Double, Double>>()
        for (pt in fullPath) {
            if (cleanedPath.isEmpty() ||
                calcDistanceMeters(cleanedPath.last().first, cleanedPath.last().second, pt.first, pt.second) >= 2) {
                cleanedPath.add(pt)
            }
        }

        // 道なり総距離の計算
        var totalRoadDist = 0
        for (i in 0 until cleanedPath.size - 1) {
            totalRoadDist += calcDistanceMeters(
                cleanedPath[i].first, cleanedPath[i].second,
                cleanedPath[i + 1].first, cleanedPath[i + 1].second
            )
        }

        val totalDuration = travelDurationMinutes(totalRoadDist, mode)

        return RoadRouteResult(
            path = cleanedPath,
            distanceMeters = totalRoadDist,
            durationMinutes = totalDuration,
            isRoadBased = true
        )
    }
}

internal fun computeTurnPenalty(
    angleDiffDeg: Double,
    prevEdgeDistMeters: Double,
    isConsecutiveTurn: Boolean,
    prevRoadClass: Int,
    currRoadClass: Int,
    mode: TravelMode
): Double {
    if (angleDiffDeg <= 35.0) return 0.0

    var penalty = if (mode == TravelMode.BICYCLE) {
        when {
            angleDiffDeg >= 130.0 -> 120.0 + (angleDiffDeg - 130.0) * 0.8
            angleDiffDeg >= 85.0 -> 50.0 + (angleDiffDeg - 85.0) * 0.5
            angleDiffDeg >= 55.0 -> 32.0 + (angleDiffDeg - 55.0) * 0.6
            else -> 18.0 + (angleDiffDeg - 35.0) * 0.7
        }
    } else {
        when {
            angleDiffDeg >= 130.0 -> 70.0 + (angleDiffDeg - 130.0) * 0.5
            angleDiffDeg >= 85.0 -> 30.0 + (angleDiffDeg - 85.0) * 0.3
            angleDiffDeg >= 55.0 -> 20.0 + (angleDiffDeg - 55.0) * 0.33
            else -> 10.0 + (angleDiffDeg - 35.0) * 0.5
        }
    }

    // 頻繁な曲がり（短区間での連続ターン）ペナルティ: 70m未満ですぐまた曲がるのを強力に抑制
    if (prevEdgeDistMeters < 70.0) {
        penalty += if (mode == TravelMode.BICYCLE) 55.0 else 35.0
    }

    // 2連続ターン累積ペナルティ: 直前でも曲がっていた場合
    if (isConsecutiveTurn) {
        penalty += if (mode == TravelMode.BICYCLE) 60.0 else 40.0
    }

    // 大通りからの離脱ペナルティ: 大通り走行中はできるだけ直進維持 (自転車道への不要な逸脱も防止)
    if (prevRoadClass <= 2 && currRoadClass >= 3) {
        penalty += if (mode == TravelMode.BICYCLE) 30.0 else 18.0
    }

    // 細街路・生活道路での右左折ペナルティ
    if (currRoadClass >= 6 || prevRoadClass >= 6) {
        penalty += if (mode == TravelMode.BICYCLE) 25.0 else 15.0
    }

    return penalty
}

