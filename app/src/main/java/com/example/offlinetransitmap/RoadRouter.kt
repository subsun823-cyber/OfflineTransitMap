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
    BICYCLE,
    TRANSIT
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
    TravelMode.WALK, TravelMode.TRANSIT -> walkDurationMinutes(meters)
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

    // 最寄りの道路ノードを検索 (周囲グリッド探索、全DB走査、孤立小路を回避するロバストスナップ)
    fun findNearestNode(lat: Double, lon: Double, mode: TravelMode = TravelMode.WALK): Pair<Long, Int>? {
        val y = (lat / GRID_CELL_DEG).toInt()
        val x = (lon / GRID_CELL_DEG).toInt()
        val modeColumn = if (mode == TravelMode.BICYCLE) "is_bike" else "is_walk"

        fun searchInRadius(r: Int): Pair<Long, Int>? {
            val gridKeys = ArrayList<Int>((2 * r + 1) * (2 * r + 1))
            for (dy in -r..r) {
                for (dx in -r..r) {
                    gridKeys.add((((y + dy) and 0xFFFF) shl 16) or ((x + dx) and 0xFFFF))
                }
            }

            data class Candidate(val globalId: Long, val localId: Int, val dist: Int, val lat: Double, val lon: Double, val dbIdx: Int)
            val candidates = ArrayList<Candidate>()

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
                            val maxSnap = if (r <= 2) MAX_SNAP_DISTANCE_M else 4000
                            if (dist <= maxSnap) {
                                val globalId = makeGlobalNodeId(dbIdx, nid)
                                candidates.add(Candidate(globalId, nid, dist, nLat, nLon, dbIdx))
                            }
                        }
                    }
                }
            }

            if (candidates.isEmpty()) return null
            candidates.sortBy { it.dist }

            // 候補ノードの到達性・道路種別チェック
            // TRANSIT(バス)モード時は大通り・幹線道路(class 1, 2)を最優先でスナップし、裏道や歩道への誤スナップを完全防止
            val topCandidates = candidates.take(if (mode == TravelMode.TRANSIT) 25 else 15)
            var bestTransitNode: Candidate? = null
            var bestTransitScore = Double.MAX_VALUE

            for (cand in topCandidates) {
                val db = databases.getOrNull(cand.dbIdx) ?: continue
                if (!db.isOpen) continue
                val localAdj = HashMap<Int, ArrayList<Int>>()
                var minRc = 999
                val candY = (cand.lat / GRID_CELL_DEG).toInt()
                val candX = (cand.lon / GRID_CELL_DEG).toInt()
                val candGridKeys = ArrayList<Int>(9)
                for (cdy in -1..1) {
                    for (cdx in -1..1) {
                        candGridKeys.add((((candY + cdy) and 0xFFFF) shl 16) or ((candX + cdx) and 0xFFFF))
                    }
                }
                val chunkKeys = candGridKeys.joinToString(",")
                val edgeCursor = try {
                    db.rawQuery(
                        "SELECT u, v, road_class FROM road_edges WHERE $modeColumn = 1 AND grid IN ($chunkKeys)",
                        null
                    )
                } catch (e: Exception) {
                    null
                }
                edgeCursor?.use {
                    val uCol = it.getColumnIndexOrThrow("u")
                    val vCol = it.getColumnIndexOrThrow("v")
                    val rcCol = it.getColumnIndexOrThrow("road_class")
                    while (it.moveToNext()) {
                        val u = it.getInt(uCol)
                        val v = it.getInt(vCol)
                        val rc = it.getInt(rcCol)
                        localAdj.getOrPut(u) { ArrayList() }.add(v)
                        localAdj.getOrPut(v) { ArrayList() }.add(u)
                        if (u == cand.localId || v == cand.localId) {
                            if (rc < minRc) minRc = rc
                        }
                    }
                }

                val visited = HashSet<Int>()
                val queue = ArrayDeque<Int>()
                queue.add(cand.localId)
                visited.add(cand.localId)
                while (queue.isNotEmpty() && visited.size < 15) {
                    val curr = queue.removeFirst()
                    for (nxt in localAdj[curr] ?: emptyList()) {
                        if (visited.add(nxt)) {
                            queue.add(nxt)
                        }
                    }
                }

                if (visited.size >= 15) {
                    if (mode == TravelMode.TRANSIT) {
                        val rcWeight = when (minRc) {
                            1 -> 0.5   // 主要幹線 (最優先)
                            2 -> 0.65  // 主要道 (バス通り)
                            4 -> 1.0   // 生活道路
                            5 -> 4.0   // 歩行者道
                            6 -> 6.0   // 細街路・裏道
                            else -> 3.0
                        }
                        val score = cand.dist * rcWeight
                        if (score < bestTransitScore) {
                            bestTransitScore = score
                            bestTransitNode = cand
                        }
                    } else {
                        return Pair(cand.globalId, cand.dist)
                    }
                }
            }

            if (mode == TravelMode.TRANSIT && bestTransitNode != null) {
                return Pair(bestTransitNode.globalId, bestTransitNode.dist)
            }

            // 十分な連結ノードが見つからない場合は最寄り候補にフォールバック
            return Pair(candidates.first().globalId, candidates.first().dist)
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

        return try {
            val startSnap = findNearestNode(originLat, originLon, mode) ?: return directFallback
            val goalSnap = findNearestNode(destLat, destLon, mode) ?: return directFallback

            val startNodeId = startSnap.first
            val goalNodeId = goalSnap.first

            // 探索領域のノードとエッジを読み込み(近距離ではマージンを適正化しグリッド境界分断を防止)
            val margin = when {
                directMeters <= 1200 -> 0.012 // バス停間など近距離(約1.3km四方で境界分断を完全防止)
                directMeters <= 5000 -> 0.018 // 約2km四方
                directMeters <= 20000 -> 0.035
                else -> 0.05
            }
            val minLat = minOf(originLat, destLat) - margin
            val maxLat = maxOf(originLat, destLat) + margin
            val minLon = minOf(originLon, destLon) - margin
            val maxLon = maxOf(originLon, destLon) + margin

            val yMin = (minLat / GRID_CELL_DEG).toInt()
            val yMax = (maxLat / GRID_CELL_DEG).toInt()
            val xMin = (minLon / GRID_CELL_DEG).toInt()
            val xMax = (maxLon / GRID_CELL_DEG).toInt()

            val gridKeySet = HashSet<Int>()
            for (y in yMin..yMax) {
                for (x in xMin..xMax) {
                    gridKeySet.add(((y and 0xFFFF) shl 16) or (x and 0xFFFF))
                }
            }

            // スタート地点・ゴール地点の近傍グリッドを包含 (タイル境界での切断を完全に防ぐため常にr=1)
            fun addNeighborhood(lat: Double, lon: Double) {
                val cy = (lat / GRID_CELL_DEG).toInt()
                val cx = (lon / GRID_CELL_DEG).toInt()
                val r = 1
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        gridKeySet.add((((cy + dy) and 0xFFFF) shl 16) or (((cx + dx) and 0xFFFF)))
                    }
                }
            }
            addNeighborhood(originLat, originLon)
            addNeighborhood(destLat, destLon)

            val gridKeys = gridKeySet.toList()

            // 最大セル数を5000（約70km四方）まで大幅拡大
            if (gridKeys.isEmpty() || gridKeys.size > 5000) return directFallback

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

            // startNodeId と goalNodeId がバウンディングボックス端にあっても確実にロードするフォールバック
            fun ensureNodeLoaded(globalId: Long) {
                if (nodesMap.containsKey(globalId)) return
                val dbIdx = (globalId shr 32).toInt()
                val localId = (globalId and 0xFFFFFFFFL).toInt()
                val db = databases.getOrNull(dbIdx) ?: return
                if (!db.isOpen) return
                val cursor = try {
                    db.rawQuery("SELECT lat, lon FROM road_nodes WHERE id = $localId", null)
                } catch (_: Exception) { null } ?: return
                cursor.use {
                    if (it.moveToNext()) {
                        val nLat = it.getDouble(0)
                        val nLon = it.getDouble(1)
                        nodesMap[globalId] = GraphNode(globalId, nLat, nLon, dbIdx)
                    }
                }
            }
            ensureNodeLoaded(startNodeId)
            ensureNodeLoaded(goalNodeId)

            val startNode = nodesMap[startNodeId] ?: return directFallback
            val goalNode = nodesMap[goalNodeId] ?: return directFallback

            if (startNodeId == goalNodeId) {
                val p = listOf(Pair(originLat, originLon), Pair(startNode.lat, startNode.lon), Pair(destLat, destLon))
                return RoadRouteResult(
                    path = p,
                    distanceMeters = directMeters,
                    durationMinutes = directDuration,
                    isRoadBased = true
                )
            }

            // 領域内のエッジを隣接リストに展開 (全DB) - 高速な空間グリッドインデックス(idx_road_edges_grid)のみを使用
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

            // 既存エッジのペアを収集（重複エッジの追加を厳格に防止）
            val existingPairs = HashSet<Pair<Long, Long>>()
            for ((u, edges) in adj) {
                for (edge in edges) {
                    existingPairs.add(Pair(minOf(u, edge.toNode), maxOf(u, edge.toNode)))
                }
            }

            // 高速ノード間ステッチング（FINE_GRID法: 0.001度 約100mセルで高速探索）
            // 都県境・タイル境界・行き止まり・未分割交差点の隙間（最大35m以内）を高速縫合（数ミリ秒以内）
            val fineDeg = 0.001 // 約100m
            val fineGrid = HashMap<Long, ArrayList<GraphNode>>()
            for (node in nodesMap.values) {
                val fy = (node.lat / fineDeg).toLong()
                val fx = (node.lon / fineDeg).toLong()
                val fKey = (fy shl 32) or (fx and 0xFFFFFFFFL)
                fineGrid.getOrPut(fKey) { ArrayList() }.add(node)
            }

            // デッドエンドノード(接続数<=1)および境界・少数接続ノード(接続数<=2)を対象にステッチング
            val stitchCandidates = ArrayList<Long>()
            for (nid in nodesMap.keys) {
                val edgeCount = adj[nid]?.size ?: 0
                if (edgeCount <= 2) {
                    stitchCandidates.add(nid)
                }
            }

            for (u in stitchCandidates) {
                val uNode = nodesMap[u] ?: continue
                val uEdgeCount = adj[u]?.size ?: 0
                val maxGap = if (uEdgeCount <= 1) 35 else 22
                val fy = (uNode.lat / fineDeg).toLong()
                val fx = (uNode.lon / fineDeg).toLong()

                var bestV: Long? = null
                var bestD = Int.MAX_VALUE

                for (dy in -1L..1L) {
                    for (dx in -1L..1L) {
                        val fKey = ((fy + dy) shl 32) or ((fx + dx) and 0xFFFFFFFFL)
                        val neighbors = fineGrid[fKey] ?: continue
                        for (vNode in neighbors) {
                            val v = vNode.id
                            if (v == u) continue
                            val pairKey = Pair(minOf(u, v), maxOf(u, v))
                            if (existingPairs.contains(pairKey)) continue

                            val d = calcDistanceMeters(uNode.lat, uNode.lon, vNode.lat, vNode.lon)
                            if (d <= maxGap && d < bestD) {
                                bestD = d
                                bestV = v
                            }
                        }
                    }
                }

                if (bestV != null) {
                    val d = maxOf(1, bestD)
                    val pairKey = Pair(minOf(u, bestV), maxOf(u, bestV))
                    existingPairs.add(pairKey)
                    adj.getOrPut(u) { ArrayList() }.add(GraphEdge(bestV, d, isWalk = true, isBike = true, roadClass = 4))
                    adj.getOrPut(bestV) { ArrayList() }.add(GraphEdge(u, d, isWalk = true, isBike = true, roadClass = 4))
                }
            }

            // A* 探索 (大通り優先・走りやすさ・右左折ペナルティを考慮した高品質経路探索)
            val dist = HashMap<Long, Double>()
            val prev = HashMap<Long, Pair<Long, GraphEdge>>()
            val pq = PriorityQueue<SearchState>()

            dist[startNodeId] = 0.0
            val initialH = calcDistanceMeters(startNode.lat, startNode.lon, goalNode.lat, goalNode.lon) * 0.70
            pq.add(SearchState(startNodeId, 0.0, initialH))

            var found = false
            var bestNodeId = startNodeId
            var bestDistToGoal = calcDistanceMeters(startNode.lat, startNode.lon, goalNode.lat, goalNode.lon)

            var searchSteps = 0
            val maxSearchSteps = 100_000 // 巨大グラフにおける異常ブロッキング・ハングアップ・ANR完全防止ガード

            while (pq.isNotEmpty() && searchSteps < maxSearchSteps) {
                searchSteps++
                val curr = pq.poll() ?: break
                val u = curr.nodeId

                val uNode = nodesMap[u] ?: continue
                val distToGoal = calcDistanceMeters(uNode.lat, uNode.lon, goalNode.lat, goalNode.lon)
                if (distToGoal < bestDistToGoal) {
                    bestDistToGoal = distToGoal
                    bestNodeId = u
                }

                if (u == goalNodeId) {
                    found = true
                    break
                }

                if (curr.cost > (dist[u] ?: Double.MAX_VALUE)) {
                    continue
                }

                val edges = adj[u] ?: continue
                for (edge in edges) {
                    val v = edge.toNode
                    val vNode = nodesMap[v] ?: continue

                    // 道路種別によるコスト係数（走りやすさ・大通りの優先度）
                    val classFactor = when (mode) {
                        TravelMode.BICYCLE -> when (edge.roadClass) {
                            1 -> 0.80   // 大通り・主要幹線 (幅広・舗装状態良好・直進性高: 20%優遇)
                            2 -> 0.85   // 主要中規模道 (15%優遇)
                            3 -> 0.82   // 専用自転車道 (18%優遇: 幹線道路からの不要な離脱を防止)
                            4 -> 1.00   // 一般生活道路 (基準)
                            5 -> 1.20   // 歩行者道・広場 (徐行・押し歩き)
                            6 -> 1.45   // 細街路・裏道・路地 (見通し悪く低速・回避)
                            7 -> 1.70   // 未舗装路 (走行困難)
                            8 -> 8.00   // 階段
                            else -> 1.00
                        }
                        TravelMode.TRANSIT -> when (edge.roadClass) {
                            1 -> 0.50   // 大通り・主要幹線 (路線バス最優先)
                            2 -> 0.65   // 主要道 (主要バス通り)
                            3 -> 1.50   // 専用自転車道
                            4 -> 1.00   // 一般生活道路
                            5 -> 10.00  // 歩行者道 (バス通行不可・極大ペナルティ)
                            6 -> 5.00   // 細街路・裏道 (大型バス通行困難・極大ペナルティ)
                            7 -> 10.00  // 未舗装路
                            8 -> 50.00  // 階段
                            else -> 1.00
                        }
                        TravelMode.WALK -> when (edge.roadClass) {
                            1 -> 0.85   // 大通り (幅広歩道・街灯・わかりやすい: 15%優遇)
                            2 -> 0.88   // 主要道
                            3 -> 0.85   // 自転車・歩行者専用道
                            4 -> 1.00   // 生活道路 (基準)
                            5 -> 0.80   // 歩行者専用道・緑道・公園道 (快適安全: 20%優遇)
                            6 -> 1.30   // 細い路地・裏道 (迷いやすく見通しが悪い)
                            7 -> 1.40   // 未舗装路
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

            // ゴール到達または最接近ノードへのベストエフォート道なりフォールバック（画面全体の一直線化を根絶）
            val targetNodeId = if (found) goalNodeId else bestNodeId

            // スタートから1歩も進めずゴールにも届かなかった場合のみ directFallback
            if (!found && targetNodeId == startNodeId) {
                return directFallback
            }

            // 経路の復元 (通過ノード列を復元、閉路防止・上限ガードつき)
            val pathNodes = ArrayList<Long>()
            val visitedInPath = HashSet<Long>()
            var curr = targetNodeId
            pathNodes.add(curr)
            visitedInPath.add(curr)
            while (curr != startNodeId && pathNodes.size < 50_000) {
                val p = prev[curr] ?: break
                curr = p.first
                if (!visitedInPath.add(curr)) {
                    // 閉路を検出した場合は安全に打ち切り
                    break
                }
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

            // TRANSIT(バス)モード時に直線距離の2.2倍を超える異常迂回(袋小路ループや山林迷い込み)をガード
            if (mode == TravelMode.TRANSIT && directMeters > 100 && totalRoadDist > directMeters * 2.2) {
                return directFallback
            }

            val totalDuration = travelDurationMinutes(totalRoadDist, mode)

            RoadRouteResult(
                path = cleanedPath,
                distanceMeters = totalRoadDist,
                durationMinutes = totalDuration,
                isRoadBased = true
            )
        } catch (e: Throwable) {
            android.util.Log.e("RoadRouter", "Road route search failed gracefully", e)
            directFallback
        }
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
        penalty += if (mode == TravelMode.BICYCLE) 65.0 else 40.0
    }

    // 2連続ターン累積ペナルティ: 直前でも曲がっていた場合 (コの字型迂回の根絶)
    if (isConsecutiveTurn) {
        penalty += if (mode == TravelMode.BICYCLE) 75.0 else 45.0
    }

    // 大通りからの離脱ペナルティ: 大通り走行中はできるだけ直進維持 (自転車道への不要な逸脱も防止)
    if (prevRoadClass <= 2 && currRoadClass >= 3) {
        penalty += if (mode == TravelMode.BICYCLE) 50.0 else 30.0
        // 特に細街路・裏道への逸脱はさらに追加加算
        if (currRoadClass >= 6) {
            penalty += if (mode == TravelMode.BICYCLE) 35.0 else 20.0
        }
    }

    // 細街路・生活道路での右左折ペナルティ
    if (currRoadClass >= 6 || prevRoadClass >= 6) {
        penalty += if (mode == TravelMode.BICYCLE) 25.0 else 15.0
    }

    return penalty
}

