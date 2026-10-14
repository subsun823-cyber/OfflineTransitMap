package com.example.offlinetransitmap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 実際の鉄道線路（OpenStreetMap / PMTiles の railway ジオメトリ）に完全に沿った
 * 列車経路のポリライン生成と距離計算を行うルーター。
 */
class RailRouter(private val db: SQLiteDatabase) {

    companion object {
        private const val TAG = "RailRouter"
        private const val DB_NAME = "railways.db"
        private const val RAILWAYS_DB_VERSION = 1
        private const val GRID_CELL_DEG = 0.01 // 約1.1km

        @Volatile
        private var instance: RailRouter? = null

        fun getInstance(context: Context): RailRouter? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                try {
                    val dbDir = context.getExternalFilesDir("railways") ?: context.filesDir
                    val file = File(dbDir, DB_NAME)
                    val prefs = context.getSharedPreferences("rail_router_prefs", Context.MODE_PRIVATE)
                    val installedVersion = prefs.getInt("railways_db_installed_version", -1)

                    val assetStream = try {
                        context.assets.open("bootstrap/$DB_NAME")
                    } catch (_: Exception) {
                        null
                    }

                    if (assetStream != null) {
                        val assetLength = try {
                            context.assets.openFd("bootstrap/$DB_NAME").length
                        } catch (_: Exception) {
                            -1L
                        }

                        val needsCopy = !file.exists() ||
                                installedVersion != RAILWAYS_DB_VERSION ||
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
                                prefs.edit().putInt("railways_db_installed_version", RAILWAYS_DB_VERSION).apply()
                            }
                        } else {
                            assetStream.close()
                        }
                    }

                    if (!file.exists()) return null

                    val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val router = RailRouter(db)
                    instance = router
                    return router
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to open railways database", e)
                    return null
                }
            }
        }

        private fun coordPairKey(p1: Pair<Double, Double>, p2: Pair<Double, Double>): Long {
            val y1 = (p1.first * 10000.0).toLong()
            val x1 = (p1.second * 10000.0).toLong()
            val y2 = (p2.first * 10000.0).toLong()
            val x2 = (p2.second * 10000.0).toLong()
            var h = 17L
            h = h * 31L + y1
            h = h * 31L + x1
            h = h * 31L + y2
            h = h * 31L + x2
            return h
        }
    }

    private data class RailNode(val id: Long, val lat: Double, val lon: Double)
    private data class RailEdge(val toNode: Long, val distMeters: Int)

    private data class SearchState(
        val nodeId: Long,
        val cost: Double,
        val estimatedTotal: Double
    ) : Comparable<SearchState> {
        override fun compareTo(other: SearchState): Int =
            this.estimatedTotal.compareTo(other.estimatedTotal)
    }

    // 駅間線路セグメントの LRU キャッシュ (上限500件)
    private val railSegmentCache = Collections.synchronizedMap(
        object : LinkedHashMap<Long, List<Pair<Double, Double>>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Pair<Double, Double>>>?): Boolean = size > 500
        }
    )

    fun close() {
        try {
            if (db.isOpen) db.close()
        } catch (_: Exception) {}
    }

    // 最寄りの線路ノードを検索 (最大500m以内)
    fun findNearestNode(lat: Double, lon: Double): Pair<Long, Int>? {
        val y = (lat / GRID_CELL_DEG).toInt()
        val x = (lon / GRID_CELL_DEG).toInt()

        val gridKeys = ArrayList<Int>(9)
        for (dy in -1..1) {
            for (dx in -1..1) {
                gridKeys.add((((y + dy) and 0xFFFF) shl 16) or ((x + dx) and 0xFFFF))
            }
        }

        val inClause = gridKeys.joinToString(",")
        val cursor = try {
            db.rawQuery("SELECT id, lat, lon FROM rail_nodes WHERE grid IN ($inClause)", null)
        } catch (_: Exception) {
            null
        } ?: return null

        var bestNodeId = -1L
        var bestDist = Int.MAX_VALUE

        cursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val latCol = it.getColumnIndexOrThrow("lat")
            val lonCol = it.getColumnIndexOrThrow("lon")
            while (it.moveToNext()) {
                val nid = it.getLong(idCol)
                val nLat = it.getDouble(latCol)
                val nLon = it.getDouble(lonCol)
                val d = calcDistanceMeters(lat, lon, nLat, nLon)
                if (d < bestDist && d <= 600) {
                    bestDist = d
                    bestNodeId = nid
                }
            }
        }

        return if (bestNodeId != -1L) Pair(bestNodeId, bestDist) else null
    }

    // 2駅間の線路経路を A* で探索し、実線路ポリラインを生成
    fun route(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double
    ): List<Pair<Double, Double>>? {
        val p1 = Pair(originLat, originLon)
        val p2 = Pair(destLat, destLon)
        val key = coordPairKey(p1, p2)
        val cached = railSegmentCache[key]
        if (cached != null) return cached

        val directMeters = calcDistanceMeters(originLat, originLon, destLat, destLon)
        if (directMeters < 30) {
            val trivial = listOf(p1, p2)
            railSegmentCache[key] = trivial
            return trivial
        }

        val startSnap = findNearestNode(originLat, originLon) ?: return null
        val goalSnap = findNearestNode(destLat, destLon) ?: return null

        val startNodeId = startSnap.first
        val goalNodeId = goalSnap.first

        if (startNodeId == goalNodeId) {
            val result = listOf(p1, p2)
            railSegmentCache[key] = result
            return result
        }

        // バウンディングボックス内の線路ノード・エッジを展開
        val margin = when {
            directMeters <= 5000 -> 0.025
            directMeters <= 20000 -> 0.04
            else -> 0.06
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

        val gridKeys = gridKeySet.toList()
        if (gridKeys.isEmpty()) return null

        val nodesMap = HashMap<Long, RailNode>()
        for (chunk in gridKeys.chunked(500)) {
            val inClause = chunk.joinToString(",")
            val nodeCursor = try {
                db.rawQuery("SELECT id, lat, lon FROM rail_nodes WHERE grid IN ($inClause)", null)
            } catch (_: Exception) {
                null
            } ?: continue

            nodeCursor.use {
                val idCol = it.getColumnIndexOrThrow("id")
                val latCol = it.getColumnIndexOrThrow("lat")
                val lonCol = it.getColumnIndexOrThrow("lon")
                while (it.moveToNext()) {
                    val nid = it.getLong(idCol)
                    nodesMap[nid] = RailNode(nid, it.getDouble(latCol), it.getDouble(lonCol))
                }
            }
        }

        fun ensureNodeLoaded(nid: Long) {
            if (nodesMap.containsKey(nid)) return
            val cursor = try {
                db.rawQuery("SELECT lat, lon FROM rail_nodes WHERE id = $nid", null)
            } catch (_: Exception) { null } ?: return
            cursor.use {
                if (it.moveToNext()) {
                    nodesMap[nid] = RailNode(nid, it.getDouble(0), it.getDouble(1))
                }
            }
        }
        ensureNodeLoaded(startNodeId)
        ensureNodeLoaded(goalNodeId)

        val startNode = nodesMap[startNodeId] ?: return null
        val goalNode = nodesMap[goalNodeId] ?: return null

        val adj = HashMap<Long, ArrayList<RailEdge>>()
        for (chunk in gridKeys.chunked(500)) {
            val inClause = chunk.joinToString(",")
            val edgeCursor = try {
                db.rawQuery("SELECT u, v, dist_m FROM rail_edges WHERE grid IN ($inClause)", null)
            } catch (_: Exception) {
                null
            } ?: continue

            edgeCursor.use {
                val uCol = it.getColumnIndexOrThrow("u")
                val vCol = it.getColumnIndexOrThrow("v")
                val distCol = it.getColumnIndexOrThrow("dist_m")
                while (it.moveToNext()) {
                    val u = it.getLong(uCol)
                    val v = it.getLong(vCol)
                    val d = it.getInt(distCol)
                    if (nodesMap.containsKey(u) && nodesMap.containsKey(v)) {
                        adj.getOrPut(u) { ArrayList() }.add(RailEdge(v, d))
                        adj.getOrPut(v) { ArrayList() }.add(RailEdge(u, d))
                    }
                }
            }
        }

        // A* 探索
        val dist = HashMap<Long, Double>()
        val prev = HashMap<Long, Pair<Long, Int>>()
        val pq = PriorityQueue<SearchState>()

        dist[startNodeId] = 0.0
        val initialH = calcDistanceMeters(startNode.lat, startNode.lon, goalNode.lat, goalNode.lon).toDouble()
        pq.add(SearchState(startNodeId, 0.0, initialH))

        var found = false
        var searchSteps = 0
        val maxSteps = 30000

        while (pq.isNotEmpty() && searchSteps < maxSteps) {
            searchSteps++
            val curr = pq.poll() ?: break
            val u = curr.nodeId

            if (u == goalNodeId) {
                found = true
                break
            }

            if (curr.cost > (dist[u] ?: Double.MAX_VALUE)) continue

            val edges = adj[u] ?: continue
            for (edge in edges) {
                val v = edge.toNode
                val vNode = nodesMap[v] ?: continue
                val nc = curr.cost + edge.distMeters

                if (nc < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = nc
                    prev[v] = Pair(u, edge.distMeters)
                    val h = calcDistanceMeters(vNode.lat, vNode.lon, goalNode.lat, goalNode.lon).toDouble()
                    pq.add(SearchState(v, nc, nc + h))
                }
            }
        }

        if (!found) return null

        // 経路復元
        val pathNodes = ArrayList<Long>()
        val visitedInPath = HashSet<Long>()
        var curr = goalNodeId
        pathNodes.add(curr)
        visitedInPath.add(curr)
        while (curr != startNodeId && pathNodes.size < 5000) {
            val p = prev[curr] ?: break
            curr = p.first
            if (!visitedInPath.add(curr)) break
            pathNodes.add(curr)
        }
        pathNodes.reverse()

        val fullPath = ArrayList<Pair<Double, Double>>()
        fullPath.add(p1)
        for (nid in pathNodes) {
            val node = nodesMap[nid]
            if (node != null) {
                fullPath.add(Pair(node.lat, node.lon))
            }
        }
        fullPath.add(p2)

        // 重複点の除去
        val cleaned = ArrayList<Pair<Double, Double>>()
        for (pt in fullPath) {
            if (cleaned.isEmpty() ||
                calcDistanceMeters(cleaned.last().first, cleaned.last().second, pt.first, pt.second) >= 3
            ) {
                cleaned.add(pt)
            }
        }

        railSegmentCache[key] = cleaned
        return cleaned
    }

    /**
     * 鉄道区間の停車駅列（駅0, 駅1, 駅2...）を受け取り、
     * 駅間を実際の線路ジオメトリに沿って高精度に補間したポリラインを生成。
     * 線路データのない未収録区間がある場合は、その区間のみ Catmull-Rom スプライン補間に安全にフォールバック。
     */
    fun interpolateRailLegPath(rawStations: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (rawStations.size < 2) return rawStations
        val result = ArrayList<Pair<Double, Double>>()

        for (i in 0 until rawStations.size - 1) {
            val st1 = rawStations[i]
            val st2 = rawStations[i + 1]

            val railSegment = route(st1.first, st1.second, st2.first, st2.second)
            val segPoints = if (railSegment != null && railSegment.size >= 2) {
                railSegment
            } else {
                // 線路が見つからない区間のみスプライン補間にフォールバック
                RouteSearcher.smoothRailPath(listOf(st1, st2))
            }

            if (result.isEmpty()) {
                result.addAll(segPoints)
            } else {
                for (k in 1 until segPoints.size) {
                    result.add(segPoints[k])
                }
            }
        }
        return result
    }
}
