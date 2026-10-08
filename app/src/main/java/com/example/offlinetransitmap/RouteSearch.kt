package com.example.offlinetransitmap

import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

// 駅1件。group は「同じ名前でまとまっている駅」のグループ
data class StationEntry(
    val id: String,
    val name: String,
    val kana: String,
    val lat: Double,
    val lon: Double,
    val group: String
)

// 経路の1区間(バス・電車1本、または徒歩)
data class RouteLeg(
    val isWalk: Boolean,
    val fromName: String,
    val toName: String,
    val depTime: LocalDateTime? = null,
    val arrTime: LocalDateTime? = null,
    val walkMinutes: Int = 0,
    val walkMeters: Int = 0,
    val operatorLabel: String = "",
    val lineName: String = "",
    val lineColor: Long = 0L,
    val headsign: String = "",
    val platform: String = "",
    val fare: Int? = null,
    val path: List<Pair<Double, Double>> = emptyList(),
    val trainType: String = ""
)

enum class SortMode { FASTEST, FEWEST_TRANSFERS, CHEAPEST }

// 1つの経路。fare が null のときは、運賃データが無い区間を含む(knownFare は分かる分の合計)
data class Itinerary(
    val legs: List<RouteLeg>,
    val departure: LocalDateTime,
    val arrival: LocalDateTime,
    val transfers: Int,
    val fare: Int?,
    val knownFare: Int,
    val routeKey: String
)

private const val INF = 1_000_000_000
private const val KIND_START = 1
private const val KIND_TRIP = 2
private const val KIND_WALK = 3
private const val MAX_TRIPS = 4                 // 乗る便は最大4本(乗換3回)
private const val BUF_AFTER_BUS = 180           // 降りてから次に乗るまでの余裕(秒)
private const val BUF_AFTER_WALK = 60           // 歩いたあとの余裕(秒)
private const val MAX_TRANSFER_WAIT = 90 * 60   // 乗換の待ちは90分まで
private const val WALK_MAX_M = 300              // 歩いて乗り換えられる距離(m)
private const val SAME_STATION_WALK_M = 150     // 同じ名前の駅で、これ未満の移動は「徒歩」として出さない
private const val HORIZON_SEC = 6 * 3600        // いまから6時間先までの便を読む
private const val EXTRA_MAX_MIN = 120L          // 最速より2時間以上遅い経路は出さない
private val RUN_OFFSETS_MIN = intArrayOf(0, 5, 10, 15, 20, 30, 40, 50, 60, 75, 90)

fun calcDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    val dy = (lat2 - lat1) * 110540.0
    val dx = (lon2 - lon1) * cos(Math.toRadians(lat1)) * 111320.0
    return hypot(dx, dy).toInt()
}

fun walkDurationMinutes(meters: Int): Int =
    (meters * 0.9375 / 60.0).roundToInt().coerceAtLeast(1)

private fun walkSec(meters: Int): Int = (meters * 0.9375).toInt()

private fun toHiragana(s: String): String {
    val sb = StringBuilder()
    for (ch in s) {
        sb.append(if (ch in '\u30A1'..'\u30F6') (ch.code - 0x60).toChar() else ch)
    }
    return sb.toString()
}

private fun shortenLabel(s: String, max: Int): String =
    if (s.length > max) s.take(max - 1) + "…" else s

private fun tidyHeadsignText(h: String): String {
    val open = h.indexOf('(')
    if (open > 0 && h.endsWith(")")) {
        val base = h.substring(0, open)
        if (base == h.substring(open + 1, h.length - 1)) return base
    }
    return h
}

class RouteSearcher(private val db: SQLiteDatabase) {

    private class Network(
        val stations: List<StationEntry>,
        val index: HashMap<String, Int>,
        val members: HashMap<String, IntArray>,
        val nbIdx: Array<IntArray>,
        val nbMeters: Array<IntArray>
    )

    // 探索に使う時刻データ(便ごとに停車行が連続して並ぶ。時刻は「今日の0時からの秒」)
    private class SearchData(
        val tripFirst: IntArray,
        val tripNo: LongArray,
        val rowStation: IntArray,
        val rowSeq: IntArray,
        val rowStop: Array<String>,
        val rowArr: IntArray,
        val rowDep: IntArray,
        val rowBoard: BooleanArray,
        val rowAlight: BooleanArray
    )

    private class Labels(n: Int) {
        val arr = Array(MAX_TRIPS + 1) { IntArray(n) { INF } }
        val kind = Array(MAX_TRIPS + 1) { IntArray(n) }
        val labRound = Array(MAX_TRIPS + 1) { IntArray(n) }
        val walkFrom = Array(MAX_TRIPS + 1) { IntArray(n) { -1 } }
        val tArr = Array(MAX_TRIPS + 1) { IntArray(n) { INF } }
        val tTrip = Array(MAX_TRIPS + 1) { IntArray(n) { -1 } }
        val tBoard = Array(MAX_TRIPS + 1) { IntArray(n) { -1 } }
        val tAlight = Array(MAX_TRIPS + 1) { IntArray(n) { -1 } }
    }

    // 乗り物の区間: a=便の番号, b=乗る行, c=降りる行 / 徒歩: a=出発駅, b=到着駅, c=到着時刻(秒)
    private class RawLeg(val isWalk: Boolean, val a: Int, val b: Int, val c: Int)

    private class LegInfo(
        val routeId: String,
        val operatorLabel: String,
        val lineName: String,
        val lineColor: Long,
        val headsign: String,
        val platform: String,
        val trainType: String
    )

    private val net: Network by lazy { loadNetwork() }

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

    // DB が経路検索に対応した形式(バージョン3以上)か
    fun isSupported(): Boolean {
        return try {
            db.rawQuery("SELECT value FROM meta WHERE key = 'schema_version'", null).use { c ->
                c.moveToFirst() && (c.getString(0)?.toIntOrNull() ?: 0) >= 3
            }
        } catch (e: Exception) {
            false
        }
    }

    // 駅データの読み込み(少し時間がかかるので、先に呼んでおく)
    fun warmUp(): Int = net.stations.size

    private fun loadNetwork(): Network {
        val list = ArrayList<StationEntry>()
        db.rawQuery(
            "SELECT station_id, name, COALESCE(kana, ''), lat, lon, COALESCE(grp, station_id) " +
                    "FROM stations ORDER BY station_id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    StationEntry(
                        c.getString(0), c.getString(1), c.getString(2),
                        c.getDouble(3), c.getDouble(4), c.getString(5)
                    )
                )
            }
        }
        val n = list.size
        val index = HashMap<String, Int>(n * 2)
        for (i in 0 until n) index[list[i].id] = i

        val memberLists = HashMap<String, ArrayList<Int>>()
        for (i in 0 until n) memberLists.getOrPut(list[i].group) { ArrayList() }.add(i)
        val members = HashMap<String, IntArray>()
        for ((g, l) in memberLists) members[g] = l.toIntArray()

        // 徒歩で乗り換えられる駅(300m以内、または同じグループの駅)
        val nbIdx = Array(n) { IntArray(0) }
        val nbMeters = Array(n) { IntArray(0) }
        for (i in 0 until n) {
            val a = list[i]
            val cosLat = cos(Math.toRadians(a.lat))
            val js = ArrayList<Int>()
            val ms = ArrayList<Int>()
            for (j in 0 until n) {
                if (i == j) continue
                val b = list[j]
                val dy = (b.lat - a.lat) * 110540.0
                val far = dy > WALK_MAX_M || dy < -WALK_MAX_M
                if (far && a.group != b.group) continue
                val dx = (b.lon - a.lon) * cosLat * 111320.0
                val m = hypot(dx, dy)
                if (m <= WALK_MAX_M || a.group == b.group) {
                    js.add(j)
                    ms.add(m.toInt())
                }
            }
            nbIdx[i] = js.toIntArray()
            nbMeters[i] = ms.toIntArray()
        }
        return Network(list, index, members, nbIdx, nbMeters)
    }

    private fun distanceMeters(a: StationEntry, b: StationEntry): Int =
        calcDistanceMeters(a.lat, a.lon, b.lat, b.lon)

    // 時刻表の有効期間(開始日, 終了日)
    fun validityRange(): Pair<LocalDate, LocalDate>? {
        db.rawQuery("SELECT MIN(start_date), MAX(end_date) FROM feeds", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) {
                return Pair(ymdToDate(c.getInt(0)), ymdToDate(c.getInt(1)))
            }
        }
        return null
    }

    private fun ymdToDate(v: Int): LocalDate = LocalDate.of(v / 10000, v / 100 % 100, v % 100)

    // 駅名・読みで駅を探す(ひらがな・カタカナ・漢字で入力できる。同じ名前の駅は1件にまとめる)
    fun searchStations(query: String, limit: Int = 8): List<StationEntry> {
        val q = toHiragana(query.trim())
        if (q.isEmpty()) return emptyList()
        val starts = ArrayList<StationEntry>()
        val contains = ArrayList<StationEntry>()
        val seen = HashSet<String>()
        for (s in net.stations) {
            if (seen.contains(s.group)) continue
            val name = toHiragana(s.name)
            if (name.startsWith(q) || s.kana.startsWith(q)) {
                starts.add(s)
                seen.add(s.group)
            } else if (name.contains(q) || s.kana.contains(q)) {
                contains.add(s)
                seen.add(s.group)
            }
            if (starts.size >= limit) break
        }
        return (starts + contains).take(limit)
    }

    // 緯度経度に最も近い駅
    fun nearestStation(lat: Double, lon: Double): StationEntry? {
        var best: StationEntry? = null
        var bestD = Double.MAX_VALUE
        val cosLat = cos(Math.toRadians(lat))
        for (s in net.stations) {
            val dy = (s.lat - lat) * 110540.0
            val dx = (s.lon - lon) * cosLat * 111320.0
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = s
            }
        }
        return best
    }

    private fun activeServices(date: LocalDate): Set<String> {
        val ymd = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        val col = arrayOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")[date.dayOfWeek.value - 1]
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

    // いまから HORIZON_SEC 先までの停車時刻を読み込む(昨日・今日・明日の運行日を対象)
    private fun loadData(now: LocalDateTime): SearchData {
        val today = now.toLocalDate()
        val lo = now.toLocalTime().toSecondOfDay()
        val hi = lo + HORIZON_SEC
        val tripFirst = ArrayList<Int>()
        val tripNo = ArrayList<Long>()
        val rs = ArrayList<Int>()
        val rseq = ArrayList<Int>()
        val rstop = ArrayList<String>()
        val rarr = ArrayList<Int>()
        val rdep = ArrayList<Int>()
        val rb = ArrayList<Boolean>()
        val ra = ArrayList<Boolean>()
        for (off in -1..1) {
            val a = maxOf(lo - off * 86400, 0)
            val b = hi - off * 86400
            if (b < 0) continue
            val services = activeServices(today.plusDays(off.toLong()))
            if (services.isEmpty()) continue
            val placeholders = services.joinToString(",") { "?" }
            val sql = "SELECT st.trip, st.seq, st.station_id, st.stop_id, st.arr_sec, st.dep_sec, " +
                    "st.can_board, st.can_alight FROM stop_times st " +
                    "JOIN trips t ON t.trip_no = st.trip " +
                    "WHERE st.dep_sec BETWEEN $a AND $b AND t.service_id IN ($placeholders) " +
                    "ORDER BY st.trip, st.seq"
            var last = -1L
            db.rawQuery(sql, services.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val trip = c.getLong(0)
                    if (trip != last) {
                        tripFirst.add(rs.size)
                        tripNo.add(trip)
                        last = trip
                    }
                    val si = net.index[c.getString(2)] ?: continue
                    rs.add(si)
                    rseq.add(c.getInt(1))
                    rstop.add(c.getString(3))
                    rarr.add(c.getInt(4) + off * 86400)
                    rdep.add(c.getInt(5) + off * 86400)
                    rb.add(c.getInt(6) == 1)
                    ra.add(c.getInt(7) == 1)
                }
            }
        }
        tripFirst.add(rs.size)
        return SearchData(
            tripFirst.toIntArray(), tripNo.toLongArray(), rs.toIntArray(), rseq.toIntArray(),
            rstop.toTypedArray(), rarr.toIntArray(), rdep.toIntArray(),
            rb.toBooleanArray(), ra.toBooleanArray()
        )
    }

    // 出発時刻 startSec(今日の0時からの秒)で探し、乗る便の本数ごとの最速経路を返す
    // origins / dests は、出発地・目的地のグループに属する駅(の番号)すべて
    private fun search(
        data: SearchData,
        origins: IntArray,
        dests: IntArray,
        startSec: Int
    ): List<List<RawLeg>> {
        val n = net.stations.size
        val tripCount = data.tripNo.size
        val L = Labels(n)
        val isDest = BooleanArray(n)
        for (d in dests) isDest[d] = true

        for (o in origins) {
            L.arr[0][o] = startSec
            L.kind[0][o] = KIND_START
        }
        for (o in origins) {
            val nb = net.nbIdx[o]
            for (x in nb.indices) {
                val j = nb[x]
                val a = startSec + walkSec(net.nbMeters[o][x])
                if (a < L.arr[0][j]) {
                    L.arr[0][j] = a
                    L.kind[0][j] = KIND_WALK
                    L.walkFrom[0][j] = o
                }
            }
        }

        val found = ArrayList<List<RawLeg>>()
        var bestDest = INF
        for (d in dests) bestDest = minOf(bestDest, L.arr[0][d])
        var destBest = bestDest

        for (k in 1..MAX_TRIPS) {
            val prev = L.arr[k - 1]
            val cur = L.arr[k]
            System.arraycopy(prev, 0, cur, 0, n)
            System.arraycopy(L.kind[k - 1], 0, L.kind[k], 0, n)
            System.arraycopy(L.labRound[k - 1], 0, L.labRound[k], 0, n)
            System.arraycopy(L.walkFrom[k - 1], 0, L.walkFrom[k], 0, n)

            // その駅から次の便に乗れる最も早い時刻
            val prevKind = L.kind[k - 1]
            val ready = IntArray(n) { s ->
                if (prev[s] >= INF) {
                    INF
                } else {
                    prev[s] + when (prevKind[s]) {
                        KIND_START -> 0
                        KIND_WALK -> BUF_AFTER_WALK
                        else -> BUF_AFTER_BUS
                    }
                }
            }

            for (t in 0 until tripCount) {
                var board = -1
                for (r in data.tripFirst[t] until data.tripFirst[t + 1]) {
                    val s = data.rowStation[r]
                    if (board >= 0 && data.rowAlight[r]) {
                        val a = data.rowArr[r]
                        if (a < cur[s] && a < destBest) {
                            cur[s] = a
                            L.kind[k][s] = KIND_TRIP
                            L.labRound[k][s] = k
                            L.tArr[k][s] = a
                            L.tTrip[k][s] = t
                            L.tBoard[k][s] = board
                            L.tAlight[k][s] = r
                            if (isDest[s]) destBest = a
                        }
                    }
                    if (board < 0 && data.rowBoard[r] && ready[s] <= data.rowDep[r] &&
                        (k == 1 || data.rowDep[r] - ready[s] <= MAX_TRANSFER_WAIT)
                    ) {
                        board = r
                    }
                }
            }

            // 降りた駅から、近くの駅(同じ名前の駅を含む)へ歩く
            for (s in 0 until n) {
                val base = L.tArr[k][s]
                if (base >= INF) continue
                val nb = net.nbIdx[s]
                val nm = net.nbMeters[s]
                for (x in nb.indices) {
                    val j = nb[x]
                    val a = base + walkSec(nm[x])
                    if (a < cur[j] && a < destBest) {
                        cur[j] = a
                        L.kind[k][j] = KIND_WALK
                        L.labRound[k][j] = k
                        L.walkFrom[k][j] = s
                        if (isDest[j]) destBest = a
                    }
                }
            }

            var bd = INF
            var bdNode = -1
            for (d in dests) {
                if (cur[d] < bd) {
                    bd = cur[d]
                    bdNode = d
                }
            }
            if (bdNode >= 0 && bd < bestDest) {
                bestDest = bd
                found.add(reconstruct(data, L, k, bdNode))
            }
        }
        return found
    }

    // 目的地から逆にたどって、区間の並びを作る
    private fun reconstruct(data: SearchData, L: Labels, k: Int, dest: Int): List<RawLeg> {
        val legs = ArrayList<RawLeg>()
        var s = dest
        var kk = k
        while (true) {
            val r0 = L.labRound[kk][s]
            val kd = L.kind[kk][s]
            if (kd == KIND_TRIP) {
                legs.add(RawLeg(false, L.tTrip[r0][s], L.tBoard[r0][s], L.tAlight[r0][s]))
                s = data.rowStation[L.tBoard[r0][s]]
                kk = r0 - 1
            } else if (kd == KIND_WALK) {
                val f = L.walkFrom[r0][s]
                legs.add(RawLeg(true, f, s, L.arr[r0][s]))
                if (r0 == 0) break
                legs.add(RawLeg(false, L.tTrip[r0][f], L.tBoard[r0][f], L.tAlight[r0][f]))
                s = data.rowStation[L.tBoard[r0][f]]
                kk = r0 - 1
            } else {
                break
            }
        }
        legs.reverse()
        return legs
    }

    private fun signature(data: SearchData, raw: List<RawLeg>): String {
        val sb = StringBuilder()
        for (leg in raw) {
            if (leg.isWalk) {
                sb.append('w').append(leg.a).append('-').append(leg.b).append(';')
            } else {
                sb.append('b').append(data.tripNo[leg.a]).append(':').append(data.rowDep[leg.b]).append(';')
            }
        }
        return sb.toString()
    }

    private fun legInfo(tripNo: Long, seq: Int): LegInfo? {
        var info: LegInfo? = null
        db.rawQuery(
            "SELECT t.route_id, r.operator, r.name, r.color, COALESCE(st.headsign, t.headsign), s.platform, $trainTypeColumn " +                    "FROM stop_times st JOIN trips t ON t.trip_no = st.trip " +
                    "JOIN routes r ON r.route_id = t.route_id JOIN stops s ON s.stop_id = st.stop_id " +
                    "WHERE st.trip = $tripNo AND st.seq = $seq",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                val platform: String? = c.getString(5)
                info = LegInfo(
                    routeId = c.getString(0),
                    operatorLabel = shortenLabel(c.getString(1) ?: "", 6),
                    lineName = shortenLabel(c.getString(2) ?: "", 8),
                    lineColor = 0xFF000000L or c.getInt(3).toLong(),
                    headsign = tidyHeadsignText(c.getString(4) ?: ""),
                    platform = if (platform.isNullOrBlank() || platform == "0") "" else "${platform}番のりば",
                    trainType = c.getString(6) ?: ""
                )
            }
        }
        return info
    }

    // 運賃(路線 × 乗る乗り場 × 降りる乗り場)。データが無いとき(JRなど)は null
    private fun fareOf(routeId: String, fromStop: String, toStop: String): Int? {
        var price: Int? = null
        db.rawQuery(
            "SELECT f.price FROM fares f WHERE f.route_id = ? " +
                    "AND f.from_zone = (SELECT zone FROM stops WHERE stop_id = ?) " +
                    "AND f.to_zone = (SELECT zone FROM stops WHERE stop_id = ?)",
            arrayOf(routeId, fromStop, toStop)
        ).use { c ->
            if (c.moveToFirst()) price = c.getInt(0)
        }
        return price
    }

    private fun buildItinerary(data: SearchData, raw: List<RawLeg>, base: LocalDateTime): Itinerary? {
        val legs = ArrayList<RouteLeg>()
        val keyParts = ArrayList<String>()
        var known = 0
        var fareUnknown = false
        var rideCount = 0
        var firstRideDep: LocalDateTime? = null
        var lastRideArr: LocalDateTime? = null
        var firstWalkMin = 0
        for ((i, leg) in raw.withIndex()) {
            if (leg.isWalk) {
                val from = net.stations[leg.a]
                val to = net.stations[leg.b]
                val m = distanceMeters(from, to)
                // 同じ駅の中(同じ名前の駅)での短い移動は、徒歩の区間として出さない
                if (from.group == to.group && m < SAME_STATION_WALK_M) continue
                val min = (walkSec(m) / 60.0).roundToInt().coerceAtLeast(1)
                if (i == 0) firstWalkMin = min
                legs.add(
                    RouteLeg(
                        isWalk = true, fromName = from.name, toName = to.name,
                        walkMinutes = min, walkMeters = m,
                        path = listOf(Pair(from.lat, from.lon), Pair(to.lat, to.lon))
                    )
                )
                keyParts.add("w")
            } else {
                val info = legInfo(data.tripNo[leg.a], data.rowSeq[leg.b]) ?: return null
                val fare = fareOf(info.routeId, data.rowStop[leg.b], data.rowStop[leg.c])
                if (fare == null) fareUnknown = true else known += fare
                val dep = base.plusSeconds(data.rowDep[leg.b].toLong())
                val arr = base.plusSeconds(data.rowArr[leg.c].toLong())
                if (firstRideDep == null) firstRideDep = dep
                lastRideArr = arr
                rideCount++
                val fromSt = data.rowStation[leg.b]
                val toSt = data.rowStation[leg.c]
                legs.add(
                    RouteLeg(
                        isWalk = false,
                        fromName = net.stations[fromSt].name,
                        toName = net.stations[toSt].name,
                        depTime = dep,
                        arrTime = arr,
                        operatorLabel = info.operatorLabel,
                        lineName = info.lineName,
                        lineColor = info.lineColor,
                        headsign = info.headsign,
                        platform = info.platform,
                        trainType = info.trainType,
                        fare = fare,
                        path = (leg.b..leg.c).map { r ->
                            val st = net.stations[data.rowStation[r]]
                            Pair(st.lat, st.lon)
                        }
                    )
                )
                keyParts.add("${info.routeId}>$fromSt>$toSt")
            }
        }
        val dep0 = firstRideDep ?: return null
        val lastArr = lastRideArr ?: return null
        val tail = raw.last()
        val arrival = if (tail.isWalk) base.plusSeconds(tail.c.toLong()) else lastArr
        return Itinerary(
            legs = legs,
            departure = dep0.minusMinutes(firstWalkMin.toLong()),
            arrival = arrival,
            transfers = rideCount - 1,
            fare = if (fareUnknown) null else known,
            knownFare = known,
            routeKey = keyParts.joinToString("|")
        )
    }

    // 出発地→目的地の経路の候補を集める(並べ替えは rank で行う)。時間のかかる処理なので裏のスレッドで呼ぶこと
    fun findRoutes(originId: String, destId: String, now: LocalDateTime): List<Itinerary> {
        val o = net.index[originId] ?: return emptyList()
        val d = net.index[destId] ?: return emptyList()
        val originGroup = net.stations[o].group
        val destGroup = net.stations[d].group
        if (originGroup == destGroup) return emptyList()
        val origins = net.members[originGroup] ?: intArrayOf(o)
        val dests = net.members[destGroup] ?: intArrayOf(d)

        val data = loadData(now)
        val base = now.toLocalDate().atStartOfDay()
        val startBase = now.toLocalTime().toSecondOfDay()
        val seen = HashSet<String>()
        val result = ArrayList<Itinerary>()
        for (offMin in RUN_OFFSETS_MIN) {
            for (raw in search(data, origins, dests, startBase + offMin * 60)) {
                if (!seen.add(signature(data, raw))) continue
                buildItinerary(data, raw, base)?.let { result.add(it) }
            }
        }
        return result
    }

    // 候補から、指定の順で上位3件を選ぶ(同じ路線の組み合わせは1件にまとめる)
    fun rank(all: List<Itinerary>, mode: SortMode): List<Itinerary> {
        if (all.isEmpty()) return all
        var earliest = all[0].arrival
        for (i in all) if (i.arrival.isBefore(earliest)) earliest = i.arrival
        val limit = earliest.plusMinutes(EXTRA_MAX_MIN)
        val ok = all.filter { !it.arrival.isAfter(limit) }
        val comparator: Comparator<Itinerary> = when (mode) {
            SortMode.FASTEST -> compareBy<Itinerary>({ it.arrival }, { it.transfers }, { it.fare ?: Int.MAX_VALUE })
            SortMode.FEWEST_TRANSFERS -> compareBy<Itinerary>({ it.transfers }, { it.arrival }, { it.fare ?: Int.MAX_VALUE })
            SortMode.CHEAPEST -> compareBy<Itinerary>({ it.fare ?: Int.MAX_VALUE }, { it.arrival }, { it.transfers })
        }
        return ok.groupBy { it.routeKey }.values
            .map { group -> group.minWithOrNull(comparator)!! }
            .sortedWith(comparator)
            .take(3)
    }

    // 任意の座標（現在地など）から任意の座標（長押し地点など）への経路を探す。
    // 出発地〜乗車駅、降車駅〜目的地への徒歩区間を自動で付加する。
    fun findRoutesBetweenCoordinates(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        now: LocalDateTime,
        destName: String = "目的地"
    ): List<Itinerary> {
        val directMeters = calcDistanceMeters(originLat, originLon, destLat, destLon)
        val directWalkMin = walkDurationMinutes(directMeters)

        fun directWalkItinerary(): Itinerary {
            val dep = now
            val arr = now.plusMinutes(directWalkMin.toLong())
            val walkLeg = RouteLeg(
                isWalk = true,
                fromName = "現在地",
                toName = destName,
                walkMinutes = directWalkMin,
                walkMeters = directMeters,
                path = listOf(Pair(originLat, originLon), Pair(destLat, destLon))
            )
            return Itinerary(
                legs = listOf(walkLeg),
                departure = dep,
                arrival = arr,
                transfers = 0,
                fare = 0,
                knownFare = 0,
                routeKey = "walk_direct"
            )
        }

        val originSt = nearestStation(originLat, originLon)
        val destSt = nearestStation(destLat, destLon)

        if (originSt == null || destSt == null) {
            return listOf(directWalkItinerary())
        }

        val originWalkMeters = calcDistanceMeters(originLat, originLon, originSt.lat, originSt.lon)
        val originWalkMin = if (originWalkMeters > 40) walkDurationMinutes(originWalkMeters) else 0

        val destWalkMeters = calcDistanceMeters(destSt.lat, destSt.lon, destLat, destLon)
        val destWalkMin = if (destWalkMeters > 40) walkDurationMinutes(destWalkMeters) else 0

        val transitSearchTime = now.plusMinutes(originWalkMin.toLong())

        val transitRoutes = if (originSt.group != destSt.group) {
            findRoutes(originSt.id, destSt.id, transitSearchTime)
        } else {
            emptyList()
        }

        val results = ArrayList<Itinerary>()

        for (itin in transitRoutes) {
            val newLegs = ArrayList<RouteLeg>()

            // 1. 出発地から最初の乗車駅への徒歩レグ（40m以上離れている場合）
            val firstLeg = itin.legs.firstOrNull()
            if (originWalkMeters > 40 && firstLeg != null) {
                val boardStationName = firstLeg.fromName
                val boardCoord = firstLeg.path.firstOrNull() ?: Pair(originSt.lat, originSt.lon)
                newLegs.add(
                    RouteLeg(
                        isWalk = true,
                        fromName = "現在地",
                        toName = boardStationName,
                        walkMinutes = originWalkMin,
                        walkMeters = originWalkMeters,
                        path = listOf(Pair(originLat, originLon), boardCoord)
                    )
                )
            }

            // 2. 公共交通のレグ群
            newLegs.addAll(itin.legs)

            // 3. 最後の降車駅から目的地への徒歩レグ（40m以上離れている場合）
            val lastLeg = itin.legs.lastOrNull()
            if (destWalkMeters > 40 && lastLeg != null) {
                val alightStationName = lastLeg.toName
                val alightCoord = lastLeg.path.lastOrNull() ?: Pair(destSt.lat, destSt.lon)
                newLegs.add(
                    RouteLeg(
                        isWalk = true,
                        fromName = alightStationName,
                        toName = destName,
                        walkMinutes = destWalkMin,
                        walkMeters = destWalkMeters,
                        path = listOf(alightCoord, Pair(destLat, destLon))
                    )
                )
            }

            val dep = if (originWalkMin > 0) itin.departure.minusMinutes(originWalkMin.toLong()) else itin.departure
            val arr = if (destWalkMin > 0) itin.arrival.plusMinutes(destWalkMin.toLong()) else itin.arrival

            results.add(
                itin.copy(
                    legs = newLegs,
                    departure = dep,
                    arrival = arr
                )
            )
        }

        // 徒歩で移動可能な距離（2.5km以内）、または公共交通の経路が見つからなかった場合は徒歩ルートも追加
        if (results.isEmpty() || directMeters <= 2500) {
            results.add(directWalkItinerary())
        }

        return results
    }
}