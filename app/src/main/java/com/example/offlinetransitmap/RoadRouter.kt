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

class RoadRouter private constructor(private val db: SQLiteDatabase) {

    companion object {
        private const val DB_NAME = "roads.db"
        private const val GRID_CELL_DEG = 0.01 // 約1.1km
        private const val MAX_SNAP_DISTANCE_M = 1200 // 道路ノードまでの最大許容スナップ距離

        @Volatile
        private var instance: RoadRouter? = null

        fun open(context: Context): RoadRouter? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                try {
                    val dbDir = context.getExternalFilesDir("roads") ?: context.filesDir
                    val file = File(dbDir, DB_NAME)

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
                    val router = RoadRouter(sqliteDb)
                    instance = router
                    return router
                } catch (e: Exception) {
                    return null
                }
            }
        }

        private fun gridCellKey(lat: Double, lon: Double): Int {
            val y = (lat / GRID_CELL_DEG).toInt()
            val x = (lon / GRID_CELL_DEG).toInt()
            return ((y and 0xFFFF) shl 16) or (x and 0xFFFF)
        }
    }

    private data class GraphNode(
        val id: Int,
        val lat: Double,
        val lon: Double
    )

    private data class GraphEdge(
        val toNode: Int,
        val distMeters: Int,
        val isWalk: Boolean,
        val isBike: Boolean,
        val polyline: String,
        val isForward: Boolean // 順方向か逆方向か
    )

    private data class SearchState(
        val nodeId: Int,
        val cost: Double,
        val estimatedTotal: Double
    ) : Comparable<SearchState> {
        override fun compareTo(other: SearchState): Int =
            this.estimatedTotal.compareTo(other.estimatedTotal)
    }

    // 最寄りの道路ノードを検索
    fun findNearestNode(lat: Double, lon: Double): Pair<Int, Int>? {
        val y = (lat / GRID_CELL_DEG).toInt()
        val x = (lon / GRID_CELL_DEG).toInt()
        val gridKeys = ArrayList<Int>(9)
        for (dy in -1..1) {
            for (dx in -1..1) {
                gridKeys.add((((y + dy) and 0xFFFF) shl 16) or ((x + dx) and 0xFFFF))
            }
        }

        val inClause = gridKeys.joinToString(",")
        val cursor = db.rawQuery(
            "SELECT id, lat, lon FROM road_nodes WHERE grid IN ($inClause)",
            null
        )

        var bestId = -1
        var bestDist = Int.MAX_VALUE

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
                    bestId = nid
                }
            }
        }

        return if (bestId != -1 && bestDist <= MAX_SNAP_DISTANCE_M) {
            Pair(bestId, bestDist)
        } else {
            null
        }
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

        if (startNodeId == goalNodeId) {
            return directFallback
        }

        // 探索領域のノードとエッジを読み込み
        val minLat = minOf(originLat, destLat) - 0.015
        val maxLat = maxOf(originLat, destLat) + 0.015
        val minLon = minOf(originLon, destLon) - 0.015
        val maxLon = maxOf(originLon, destLon) + 0.015

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

        if (gridKeys.isEmpty()) return directFallback

        val inClause = gridKeys.joinToString(",")

        // 領域内のノードをマップに展開
        val nodesMap = HashMap<Int, GraphNode>()
        val nodeCursor = db.rawQuery(
            "SELECT id, lat, lon FROM road_nodes WHERE grid IN ($inClause)",
            null
        )
        nodeCursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val latCol = it.getColumnIndexOrThrow("lat")
            val lonCol = it.getColumnIndexOrThrow("lon")
            while (it.moveToNext()) {
                val nid = it.getInt(idCol)
                nodesMap[nid] = GraphNode(nid, it.getDouble(latCol), it.getDouble(lonCol))
            }
        }

        if (!nodesMap.containsKey(startNodeId) || !nodesMap.containsKey(goalNodeId)) {
            return directFallback
        }

        val goalNode = nodesMap[goalNodeId] ?: return directFallback

        // 領域内のエッジを隣接リストに展開
        val modeColumn = if (mode == TravelMode.BICYCLE) "is_bike" else "is_walk"
        val edgeCursor = db.rawQuery(
            "SELECT u, v, dist_m, is_walk, is_bike, polyline FROM road_edges WHERE $modeColumn = 1 AND grid IN ($inClause)",
            null
        )

        val adj = HashMap<Int, ArrayList<GraphEdge>>()
        edgeCursor.use {
            val uCol = it.getColumnIndexOrThrow("u")
            val vCol = it.getColumnIndexOrThrow("v")
            val distCol = it.getColumnIndexOrThrow("dist_m")
            val walkCol = it.getColumnIndexOrThrow("is_walk")
            val bikeCol = it.getColumnIndexOrThrow("is_bike")
            val polyCol = it.getColumnIndexOrThrow("polyline")

            while (it.moveToNext()) {
                val u = it.getInt(uCol)
                val v = it.getInt(vCol)
                val d = it.getInt(distCol)
                val isWalk = it.getInt(walkCol) == 1
                val isBike = it.getInt(bikeCol) == 1
                val poly = it.getString(polyCol)

                // 両方向通行可能として隣接リストに追加
                adj.getOrPut(u) { ArrayList() }.add(GraphEdge(v, d, isWalk, isBike, poly, isForward = true))
                adj.getOrPut(v) { ArrayList() }.add(GraphEdge(u, d, isWalk, isBike, poly, isForward = false))
            }
        }

        // A* 探索
        val dist = HashMap<Int, Double>()
        val prev = HashMap<Int, Pair<Int, GraphEdge>>()
        val pq = PriorityQueue<SearchState>()

        dist[startNodeId] = 0.0
        val initialH = calcDistanceMeters(nodesMap[startNodeId]!!.lat, nodesMap[startNodeId]!!.lon, goalNode.lat, goalNode.lon).toDouble()
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
            for (edge in edges) {
                val v = edge.toNode
                val vNode = nodesMap[v] ?: continue
                val newCost = curr.cost + edge.distMeters

                if (newCost < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = newCost
                    prev[v] = Pair(u, edge)
                    val h = calcDistanceMeters(vNode.lat, vNode.lon, goalNode.lat, goalNode.lon).toDouble()
                    pq.add(SearchState(v, newCost, newCost + h))
                }
            }
        }

        if (!found) {
            return directFallback
        }

        // 経路の復元
        val reconstructedEdges = ArrayList<GraphEdge>()
        var curr = goalNodeId
        while (curr != startNodeId) {
            val p = prev[curr] ?: break
            reconstructedEdges.add(p.second)
            curr = p.first
        }
        reconstructedEdges.reverse()

        // 完全なポリラインの構築
        val fullPath = ArrayList<Pair<Double, Double>>()
        fullPath.add(Pair(originLat, originLon))

        for (edge in reconstructedEdges) {
            val pts = parsePolyline(edge.polyline)
            if (pts.isNotEmpty()) {
                if (edge.isForward) {
                    fullPath.addAll(pts)
                } else {
                    fullPath.addAll(pts.reversed())
                }
            } else {
                val node = nodesMap[edge.toNode]
                if (node != null) {
                    fullPath.add(Pair(node.lat, node.lon))
                }
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

    private fun parsePolyline(polyStr: String): List<Pair<Double, Double>> {
        if (polyStr.isBlank()) return emptyList()
        val parts = polyStr.split(";")
        val list = ArrayList<Pair<Double, Double>>(parts.size)
        for (part in parts) {
            val comma = part.indexOf(',')
            if (comma > 0) {
                val lat = part.substring(0, comma).toDoubleOrNull()
                val lon = part.substring(comma + 1).toDoubleOrNull()
                if (lat != null && lon != null) {
                    list.add(Pair(lat, lon))
                }
            }
        }
        return list
    }
}
