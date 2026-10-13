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
    val trainType: String = "",
    val fareNote: String? = null
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
private const val HORIZON_SEC = (2.5 * 3600).toInt() // 直近2.5時間以内の便を読む(メモリ・検索負荷を大幅軽量化)
private const val EXTRA_MAX_MIN = 120L          // 最速より2時間以上遅い経路は出さない
private val RUN_OFFSETS_MIN = intArrayOf(0, 15, 35)
private const val GRID_CELL_DEG = 0.01          // 空間インデックス用グリッドサイズ(約1.1km)

private fun gridCellKey(lat: Double, lon: Double): Long {
    val y = (lat / GRID_CELL_DEG).toInt()
    val x = (lon / GRID_CELL_DEG).toInt()
    return (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)
}

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

    companion object {
        // 候補から、指定の順で上位3件を選ぶ(同じ路線の組み合わせは1件にまとめる)
        // 公共交通機関を含むルートを最優先とし、徒歩ルートで上書きされないようにする
        fun rank(all: List<Itinerary>, mode: SortMode): List<Itinerary> {
            if (all.isEmpty()) return all

            // 公共交通機関（電車・バス）に乗車するレグを1つ以上含む旅程を最優先で選抜
            val transitOnly = all.filter { itin -> itin.legs.any { !it.isWalk } }
            val pool = if (transitOnly.isNotEmpty()) transitOnly else all

            var earliest = pool[0].arrival
            for (i in pool) if (i.arrival.isBefore(earliest)) earliest = i.arrival
            val limit = earliest.plusMinutes(EXTRA_MAX_MIN)
            val ok = pool.filter { !it.arrival.isAfter(limit) }
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
    }

    private class Network(
        val stations: List<StationEntry>,
        val index: HashMap<String, Int>,
        val members: HashMap<String, IntArray>,
        val nbIdx: Array<IntArray>,
        val nbMeters: Array<IntArray>,
        val grid: HashMap<Long, IntArray>
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
        val rowAlight: BooleanArray,
        val stationTrips: Array<IntArray>
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

    // DB が経路検索に対応した形式(GTFSベース)か
    fun isSupported(): Boolean {
        return try {
            val schemaVer = runCatching {
                db.rawQuery("SELECT value FROM meta WHERE key = 'schema_version'", null).use { c ->
                    if (c.moveToFirst()) c.getString(0)?.toIntOrNull() else null
                }
            }.getOrNull()
            if (schemaVer != null && schemaVer >= 1) return true

            // meta テーブルが無い場合でも、中核テーブル群が存在すれば対応とみなす
            val required = setOf("stations", "trips", "stop_times", "routes")
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                val tables = HashSet<String>()
                while (c.moveToNext()) tables.add(c.getString(0).lowercase())
                required.all { tables.contains(it) }
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

        // 空間グリッドインデックスの構築(約1.1km四方のバケット)
        val gridBuckets = HashMap<Long, ArrayList<Int>>()
        for (i in 0 until n) {
            val key = gridCellKey(list[i].lat, list[i].lon)
            gridBuckets.getOrPut(key) { ArrayList() }.add(i)
        }
        val grid = HashMap<Long, IntArray>(gridBuckets.size * 2)
        for ((k, v) in gridBuckets) grid[k] = v.toIntArray()

        // 徒歩で乗り換えられる駅(300m以内、または同じグループの駅)
        // 300m 以内は同じセルおよび隣接セル(計9セル)に必ず収まるため、周囲9セルのみを比較して O(N) に高速化
        val nbIdx = Array(n) { IntArray(0) }
        val nbMeters = Array(n) { IntArray(0) }
        for (i in 0 until n) {
            val a = list[i]
            val cy = (a.lat / GRID_CELL_DEG).toInt()
            val cx = (a.lon / GRID_CELL_DEG).toInt()
            val cosLat = cos(Math.toRadians(a.lat))
            val js = ArrayList<Int>()
            val ms = ArrayList<Int>()
            val seen = HashSet<Int>()

            for (dyCell in -1..1) {
                for (dxCell in -1..1) {
                    val k = ((cy + dyCell).toLong() shl 32) or ((cx + dxCell).toLong() and 0xFFFFFFFFL)
                    val candidates = grid[k] ?: continue
                    for (j in candidates) {
                        if (i == j || !seen.add(j)) continue
                        val b = list[j]
                        val dy = (b.lat - a.lat) * 110540.0
                        if (dy > WALK_MAX_M || dy < -WALK_MAX_M) continue
                        val dx = (b.lon - a.lon) * cosLat * 111320.0
                        val m = hypot(dx, dy)
                        if (m <= WALK_MAX_M) {
                            js.add(j)
                            ms.add(m.toInt())
                        }
                    }
                }
            }

            // 同一グループの駅(同じ駅内の移動)
            val sameGroup = members[a.group]
            if (sameGroup != null) {
                for (j in sameGroup) {
                    if (i == j || !seen.add(j)) continue
                    val b = list[j]
                    val dy = (b.lat - a.lat) * 110540.0
                    val dx = (b.lon - a.lon) * cosLat * 111320.0
                    val m = hypot(dx, dy)
                    js.add(j)
                    ms.add(m.toInt())
                }
            }

            nbIdx[i] = js.toIntArray()
            nbMeters[i] = ms.toIntArray()
        }
        return Network(list, index, members, nbIdx, nbMeters, grid)
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

    // 緯度経度に最も近い駅(空間グリッドを活用して高速化)
    fun nearestStation(lat: Double, lon: Double): StationEntry? {
        val cy = (lat / GRID_CELL_DEG).toInt()
        val cx = (lon / GRID_CELL_DEG).toInt()
        val cosLat = cos(Math.toRadians(lat))

        var best: StationEntry? = null
        var bestD = Double.MAX_VALUE

        // 半径 r のセル境界を探索
        fun checkRadius(r: Int) {
            for (dyCell in -r..r) {
                for (dxCell in -r..r) {
                    if (r > 0 && Math.abs(dyCell) < r && Math.abs(dxCell) < r) continue
                    val k = ((cy + dyCell).toLong() shl 32) or ((cx + dxCell).toLong() and 0xFFFFFFFFL)
                    val candidates = net.grid[k] ?: continue
                    for (idx in candidates) {
                        val s = net.stations[idx]
                        val dy = (s.lat - lat) * 110540.0
                        val dx = (s.lon - lon) * cosLat * 111320.0
                        val d = dx * dx + dy * dy
                        if (d < bestD) {
                            bestD = d
                            best = s
                        }
                    }
                }
            }
        }

        // 1. まず中心セルおよび隣接セル(計9セル、約1.1km四方)を探索
        checkRadius(0)
        checkRadius(1)

        // 2. 見つからない、または境界付近の可能性がある場合は外側(最大半径5セル)を探索
        val cellMeters = GRID_CELL_DEG * 110540.0
        if (best == null || bestD > cellMeters * cellMeters) {
            for (r in 2..5) {
                checkRadius(r)
                if (best != null && bestD <= (r * cellMeters) * (r * cellMeters)) break
            }
        }

        // 3. 極端な遠方のフォールバック(全駅走査)
        if (best == null) {
            for (s in net.stations) {
                val dy = (s.lat - lat) * 110540.0
                val dx = (s.lon - lon) * cosLat * 111320.0
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d
                    best = s
                }
            }
        }
        return best
    }

    // 緯度経度に近い周辺駅候補を距離順に取得(同一駅グループは最も近い1駅に集約)
    fun nearestStations(lat: Double, lon: Double, limit: Int = 3, maxDistanceMeters: Double = 4000.0): List<StationEntry> {
        val cy = (lat / GRID_CELL_DEG).toInt()
        val cx = (lon / GRID_CELL_DEG).toInt()
        val cosLat = cos(Math.toRadians(lat))
        val maxDistSq = maxDistanceMeters * maxDistanceMeters

        val candidatesWithDist = ArrayList<Pair<StationEntry, Double>>()
        val seenIndices = HashSet<Int>()

        val cellRadius = (maxDistanceMeters / (GRID_CELL_DEG * 110540.0)).toInt() + 1
        for (dyCell in -cellRadius..cellRadius) {
            for (dxCell in -cellRadius..cellRadius) {
                val k = ((cy + dyCell).toLong() shl 32) or ((cx + dxCell).toLong() and 0xFFFFFFFFL)
                val candidates = net.grid[k] ?: continue
                for (idx in candidates) {
                    if (!seenIndices.add(idx)) continue
                    val s = net.stations[idx]
                    val dy = (s.lat - lat) * 110540.0
                    val dx = (s.lon - lon) * cosLat * 111320.0
                    val d = dx * dx + dy * dy
                    if (d <= maxDistSq) {
                        candidatesWithDist.add(Pair(s, d))
                    }
                }
            }
        }

        if (candidatesWithDist.isEmpty()) {
            val single = nearestStation(lat, lon)
            return if (single != null) listOf(single) else emptyList()
        }

        candidatesWithDist.sortBy { it.second }

        val result = ArrayList<StationEntry>()
        val seenGroups = HashSet<String>()
        for ((st, _) in candidatesWithDist) {
            if (seenGroups.add(st.group)) {
                result.add(st)
                if (result.size >= limit) break
            }
        }
        return result
    }

    private val activeServicesCache = HashMap<LocalDate, Set<String>>()

    private fun activeServices(date: LocalDate): Set<String> {
        activeServicesCache[date]?.let { return it }
        val ymd = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        val col = arrayOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")[date.dayOfWeek.value - 1]
        val services = HashSet<String>()
        runCatching {
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
        }

        // 日付範囲外（端末内Timetable.dbの日付が過去・未来の場合など）の自動フォールバック救済
        if (services.isEmpty()) {
            runCatching {
                // 1. 同一曜日($col = 1)の直近・最新サービスを採用
                db.rawQuery(
                    "SELECT service_id FROM calendar WHERE $col = 1 ORDER BY end_date DESC LIMIT 50",
                    null
                ).use { c ->
                    while (c.moveToNext()) services.add(c.getString(0))
                }
                // 2. それでも空なら calendar テーブルの全サービス
                if (services.isEmpty()) {
                    db.rawQuery("SELECT service_id FROM calendar LIMIT 50", null).use { c ->
                        while (c.moveToNext()) services.add(c.getString(0))
                    }
                }
                // 3. calendar テーブル自体が無い場合は trips テーブルから直接 service_id を取得
                if (services.isEmpty()) {
                    db.rawQuery("SELECT DISTINCT service_id FROM trips LIMIT 50", null).use { c ->
                        while (c.moveToNext()) services.add(c.getString(0))
                    }
                }
            }
        }

        activeServicesCache[date] = services
        return services
    }

    private data class CachedSearchEntry(
        val cachedTime: LocalDateTime,
        val startSec: Int,
        val endSec: Int,
        val data: SearchData
    )
    private var cachedSearchData: CachedSearchEntry? = null
    private val legInfoCache = HashMap<Long, LegInfo?>()
    private val fareCache = HashMap<String, Int?>()

    // いまから HORIZON_SEC 先までの停車時刻を読み込む(通常時間帯は今日のみ高速スキャン)
    private fun loadData(now: LocalDateTime): SearchData {
        val today = now.toLocalDate()
        val lo = now.toLocalTime().toSecondOfDay()
        val hi = lo + HORIZON_SEC

        val cached = cachedSearchData
        if (cached != null) {
            val (cachedTime, cStart, cEnd, data) = cached
            val diffSec = java.time.Duration.between(cachedTime, now).seconds
            // 20分以内の連続検索で、時刻範囲がカバーされている場合はキャッシュを再利用（0.001秒で即応）
            if (diffSec in 0..1200 && cachedTime.toLocalDate() == today && lo >= cStart && hi <= cEnd + 600) {
                return data
            }
        }
        val tripFirst = ArrayList<Int>()
        val tripNo = ArrayList<Long>()
        val rs = ArrayList<Int>()
        val rseq = ArrayList<Int>()
        val rstop = ArrayList<String>()
        val rarr = ArrayList<Int>()
        val rdep = ArrayList<Int>()
        val rb = ArrayList<Boolean>()
        val ra = ArrayList<Boolean>()

        // 通常の時間帯(4時〜22時)は当日の運行データ(off = 0)のみをスキャンし、不要な過去・未来日スキャンを完全排除
        val offs = when {
            lo < 4 * 3600 -> intArrayOf(-1, 0)
            hi >= 86400 -> intArrayOf(0, 1)
            else -> intArrayOf(0)
        }

        for (off in offs) {
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
                    val arrSec = c.getInt(4)
                    val depSec = c.getInt(5)
                    rs.add(si)
                    rseq.add(c.getInt(1))
                    rstop.add(c.getString(3))
                    rarr.add(arrSec + off * 86400)
                    rdep.add(maxOf(depSec, arrSec) + off * 86400)
                    rb.add(c.getInt(6) == 1)
                    ra.add(c.getInt(7) == 1)
                }
            }
        }
        tripFirst.add(rs.size)

        val tripCount = tripNo.size
        val stationTripLists = Array(net.stations.size) { ArrayList<Int>() }
        for (t in 0 until tripCount) {
            var prevSt = -1
            for (r in tripFirst[t] until tripFirst[t + 1]) {
                val st = rs[r]
                if (st != prevSt) {
                    stationTripLists[st].add(t)
                    prevSt = st
                }
            }
        }
        val stationTrips = Array(net.stations.size) { stationTripLists[it].toIntArray() }

        val data = SearchData(
            tripFirst.toIntArray(), tripNo.toLongArray(), rs.toIntArray(), rseq.toIntArray(),
            rstop.toTypedArray(), rarr.toIntArray(), rdep.toIntArray(),
            rb.toBooleanArray(), ra.toBooleanArray(), stationTrips
        )
        cachedSearchData = CachedSearchEntry(now, lo, hi, data)
        return data
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

        val marked = BooleanArray(n)
        for (o in origins) {
            L.arr[0][o] = startSec
            L.kind[0][o] = KIND_START
            marked[o] = true
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
                    marked[j] = true
                }
            }
        }

        val found = ArrayList<List<RawLeg>>()
        var bestDest = INF
        for (d in dests) bestDest = minOf(bestDest, L.arr[0][d])
        var destBest = bestDest

        val tripsToScan = BooleanArray(tripCount)
        val nextMarked = BooleanArray(n)

        for (k in 1..MAX_TRIPS) {
            val prev = L.arr[k - 1]
            val cur = L.arr[k]
            System.arraycopy(prev, 0, cur, 0, n)
            System.arraycopy(L.kind[k - 1], 0, L.kind[k], 0, n)
            System.arraycopy(L.labRound[k - 1], 0, L.labRound[k], 0, n)
            System.arraycopy(L.walkFrom[k - 1], 0, L.walkFrom[k], 0, n)

            // 到着時刻が更新された(marked)駅を通る便のみを走査対象にする(無関係な便の走査を90%以上削減)
            tripsToScan.fill(false)
            var hasTrips = false
            for (s in 0 until n) {
                if (!marked[s]) continue
                val ts = data.stationTrips[s]
                for (x in ts.indices) {
                    val t = ts[x]
                    if (!tripsToScan[t]) {
                        tripsToScan[t] = true
                        hasTrips = true
                    }
                }
            }
            if (!hasTrips) break

            nextMarked.fill(false)

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
                if (!tripsToScan[t]) continue
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
                            nextMarked[s] = true
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
                if (!nextMarked[s]) continue
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
                        nextMarked[j] = true
                        if (isDest[j]) destBest = a
                    }
                }
            }

            System.arraycopy(nextMarked, 0, marked, 0, n)

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
        val key = (tripNo shl 16) or (seq.toLong() and 0xFFFFL)
        if (legInfoCache.containsKey(key)) return legInfoCache[key]

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
        legInfoCache[key] = info
        return info
    }

    // 運賃(路線 × 乗る乗り場 × 降りる乗り場)。データが無いとき(JRなど)は null
    private fun fareOf(routeId: String, fromStop: String, toStop: String): Int? {
        val key = "$routeId|$fromStop|$toStop"
        if (fareCache.containsKey(key)) return fareCache[key]

        var price: Int? = null
        db.rawQuery(
            "SELECT f.price FROM fares f WHERE f.route_id = ? " +
                    "AND f.from_zone = (SELECT zone FROM stops WHERE stop_id = ?) " +
                    "AND f.to_zone = (SELECT zone FROM stops WHERE stop_id = ?)",
            arrayOf(routeId, fromStop, toStop)
        ).use { c ->
            if (c.moveToFirst()) price = c.getInt(0)
        }
        fareCache[key] = price
        return price
    }

    private fun buildItinerary(data: SearchData, raw: List<RawLeg>, base: LocalDateTime): Itinerary? {
        val legs = ArrayList<RouteLeg>()
        val keyParts = ArrayList<String>()
        var rideCount = 0
        var firstRideDep: LocalDateTime? = null
        var lastRideArr: LocalDateTime? = null
        var firstWalkMin = 0

        // 乗車レグごとの運賃情報
        data class LegFareMeta(
            val legIndex: Int,
            val isJr: Boolean,
            val jrKm: Int,
            val isYamanote: Boolean,
            val individualFare: Int?
        )
        val rideFareMetas = ArrayList<LegFareMeta>()

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
                val fromSt = data.rowStation[leg.b]
                val toSt = data.rowStation[leg.c]
                val legStations = (leg.b..leg.c).map { r -> net.stations[data.rowStation[r]] }

                var fare = fareOf(info.routeId, data.rowStop[leg.b], data.rowStop[leg.c])
                val isJr = JrFareCalculator.isJrRoute(info.routeId, info.operatorLabel)
                var jrKm = 0
                var isYm = false
                if (fare == null && isJr) {
                    jrKm = JrFareCalculator.estimateOperatingKm(legStations)
                    isYm = JrFareCalculator.isAllYamanote(legStations)
                    fare = JrFareCalculator.calculateFare(jrKm, isYm)
                }

                val dep = base.plusSeconds(data.rowDep[leg.b].toLong())
                val arr = base.plusSeconds(data.rowArr[leg.c].toLong())
                if (firstRideDep == null) firstRideDep = dep
                lastRideArr = arr
                rideCount++

                val legIdx = legs.size
                rideFareMetas.add(LegFareMeta(legIdx, isJr, jrKm, isYm, fare))

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

        // 旅程全体の運賃計算（JR連続乗車時の通算運賃適用）
        var totalFare = 0
        var fareUnknown = false
        val currentJrGroup = ArrayList<LegFareMeta>()

        fun flushJrGroup() {
            if (currentJrGroup.isEmpty()) return
            if (currentJrGroup.size == 1) {
                val single = currentJrGroup[0]
                if (single.individualFare != null) {
                    totalFare += single.individualFare
                } else {
                    fareUnknown = true
                }
            } else {
                // 連続する複数のJR乗車区間 -> 通算営業キロで通算運賃を計算
                val totalKm = currentJrGroup.sumOf { it.jrKm }
                val allYm = currentJrGroup.all { it.isYamanote }
                val throughFare = JrFareCalculator.calculateFare(totalKm, allYm)
                totalFare += throughFare
                for (item in currentJrGroup) {
                    val original = legs[item.legIndex]
                    legs[item.legIndex] = original.copy(fareNote = "JR通算")
                }
            }
            currentJrGroup.clear()
        }

        var prevRideLegIdx: Int? = null
        for (meta in rideFareMetas) {
            val isConsecutive = prevRideLegIdx != null && (meta.legIndex == prevRideLegIdx + 1)
            if (meta.isJr) {
                if (currentJrGroup.isNotEmpty() && !isConsecutive) {
                    flushJrGroup()
                }
                currentJrGroup.add(meta)
            } else {
                flushJrGroup()
                if (meta.individualFare != null) {
                    totalFare += meta.individualFare
                } else {
                    fareUnknown = true
                }
            }
            prevRideLegIdx = meta.legIndex
        }
        flushJrGroup()

        return Itinerary(
            legs = legs,
            departure = dep0.minusMinutes(firstWalkMin.toLong()),
            arrival = arrival,
            transfers = rideCount - 1,
            fare = if (fareUnknown) null else totalFare,
            knownFare = totalFare,
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
    fun rank(all: List<Itinerary>, mode: SortMode): List<Itinerary> = RouteSearcher.rank(all, mode)

    // 任意の座標（現在地など）から任意の座標（長押し地点など）への経路を探す。
    // 出発地〜乗車駅、降車駅〜目的地への道なり区間を自動で付加する。
    fun findRoutesBetweenCoordinates(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        now: LocalDateTime,
        destName: String = "目的地",
        travelMode: TravelMode = TravelMode.WALK,
        roadRouter: RoadRouter? = null
    ): List<Itinerary> {
        val crowDistM = calcDistanceMeters(originLat, originLon, destLat, destLon)

        // 出発地〜目的地のダイレクトフォールバック
        fun directFallback(mode: TravelMode = travelMode): Itinerary {
            val isBike = mode == TravelMode.BICYCLE
            val modeLabel = if (isBike) "自転車" else "徒歩"
            val roadResult = runCatching {
                roadRouter?.route(originLat, originLon, destLat, destLon, mode)
            }.getOrNull()
            val directMeters = roadResult?.distanceMeters ?: crowDistM
            val directDurationMin = roadResult?.durationMinutes ?: travelDurationMinutes(directMeters, mode)
            val directPath = roadResult?.path ?: listOf(Pair(originLat, originLon), Pair(destLat, destLon))
            val directLeg = RouteLeg(
                isWalk = true,
                fromName = "現在地",
                toName = destName,
                walkMinutes = directDurationMin,
                walkMeters = directMeters,
                lineName = modeLabel,
                trainType = modeLabel,
                path = directPath
            )
            return Itinerary(
                legs = listOf(directLeg),
                departure = now,
                arrival = now.plusMinutes(directDurationMin.toLong()),
                transfers = 0,
                fare = 0,
                knownFare = 0,
                routeKey = if (isBike) "bike_direct" else "walk_direct"
            )
        }

        // 徒歩または自転車モードが明示的に選択されている場合は、公共交通探索を行わずダイレクト道なりルートを即座に計算・返却（超高速応答）
        if (travelMode == TravelMode.WALK || travelMode == TravelMode.BICYCLE) {
            return listOf(directFallback(travelMode))
        }

        // --- travelMode == TravelMode.TRANSIT (公共交通機関：電車・バス) ---
        return try {
            val originCandidates = nearestStations(originLat, originLon, limit = 3)
            val destCandidates = nearestStations(destLat, destLon, limit = 3)

            if (originCandidates.isEmpty() || destCandidates.isEmpty()) {
                return listOf(directFallback(TravelMode.WALK))
            }

            // 目的地名称が駅名の場合、その駅を目的地候補の先頭に持ってくる
            val resolvedDestCandidates = ArrayList<StationEntry>()
            val nameMatch = destCandidates.firstOrNull { destName.contains(it.name) || it.name.contains(destName) }
            if (nameMatch != null) {
                resolvedDestCandidates.add(nameMatch)
                resolvedDestCandidates.addAll(destCandidates.filter { it.group != nameMatch.group })
            } else {
                resolvedDestCandidates.addAll(destCandidates)
            }

            class StationAccess(val walkMeters: Int, val walkMin: Int, val path: List<Pair<Double, Double>>)
            val originAccessMap = HashMap<String, StationAccess>()
            val destAccessMap = HashMap<String, StationAccess>()

            fun getOriginAccess(st: StationEntry): StationAccess {
                return originAccessMap.getOrPut(st.id) {
                    val crow = calcDistanceMeters(originLat, originLon, st.lat, st.lon)
                    val road = if (crow in 41..4000) {
                        runCatching { roadRouter?.route(originLat, originLon, st.lat, st.lon, TravelMode.WALK) }.getOrNull()
                    } else null
                    val meters = road?.distanceMeters ?: crow
                    val min = if (meters > 40) (road?.durationMinutes ?: walkDurationMinutes(meters)) else 0
                    val path = road?.path ?: listOf(Pair(originLat, originLon), Pair(st.lat, st.lon))
                    StationAccess(meters, min, path)
                }
            }

            fun getDestAccess(st: StationEntry): StationAccess {
                return destAccessMap.getOrPut(st.id) {
                    val crow = calcDistanceMeters(st.lat, st.lon, destLat, destLon)
                    val road = if (crow in 41..4000) {
                        runCatching { roadRouter?.route(st.lat, st.lon, destLat, destLon, TravelMode.WALK) }.getOrNull()
                    } else null
                    val meters = road?.distanceMeters ?: crow
                    val min = if (meters > 40) (road?.durationMinutes ?: walkDurationMinutes(meters)) else 0
                    val path = road?.path ?: listOf(Pair(st.lat, st.lon), Pair(destLat, destLon))
                    StationAccess(meters, min, path)
                }
            }

            val results = ArrayList<Itinerary>()
            val seenKeys = HashSet<String>()

            // 駅ペアの候補を優先度順に探索 (最寄り同士 -> 第2候補同士)
            val searchPairs = ArrayList<Pair<StationEntry, StationEntry>>()
            for (oSt in originCandidates) {
                for (dSt in resolvedDestCandidates) {
                    if (oSt.group != dSt.group) {
                        searchPairs.add(Pair(oSt, dSt))
                    }
                }
            }

            var pairsSearched = 0
            for ((origSt, destSt) in searchPairs) {
                // 最寄り駅ペアで経路が見つかったら即座に完了し、無駄な多重探索を完全防止
                if (results.isNotEmpty()) break
                if (pairsSearched >= 2) break
                pairsSearched++

                val origAcc = getOriginAccess(origSt)
                val destAcc = getDestAccess(destSt)
                val transitSearchTime = now.plusMinutes(origAcc.walkMin.toLong())

                val transitRoutes = runCatching {
                    findRoutes(origSt.id, destSt.id, transitSearchTime)
                }.getOrElse { emptyList() }

                for (itin in transitRoutes) {
                    val newLegs = ArrayList<RouteLeg>()

                    // 1. 出発地から最初の乗車駅への徒歩レグ（40m以上離れている場合）
                    val firstLeg = itin.legs.firstOrNull()
                    if (origAcc.walkMeters > 40 && firstLeg != null) {
                        newLegs.add(
                            RouteLeg(
                                isWalk = true,
                                fromName = "現在地",
                                toName = firstLeg.fromName,
                                walkMinutes = origAcc.walkMin,
                                walkMeters = origAcc.walkMeters,
                                path = origAcc.path
                            )
                        )
                    }

                    // 2. 公共交通のレグ群
                    newLegs.addAll(itin.legs)

                    // 3. 最後の降車駅から目的地への徒歩レグ（40m以上離れている場合）
                    val lastLeg = itin.legs.lastOrNull()
                    if (destAcc.walkMeters > 40 && lastLeg != null) {
                        newLegs.add(
                            RouteLeg(
                                isWalk = true,
                                fromName = lastLeg.toName,
                                toName = destName,
                                walkMinutes = destAcc.walkMin,
                                walkMeters = destAcc.walkMeters,
                                path = destAcc.path
                            )
                        )
                    }

                    val dep = if (origAcc.walkMin > 0) itin.departure.minusMinutes(origAcc.walkMin.toLong()) else itin.departure
                    val arr = if (destAcc.walkMin > 0) itin.arrival.plusMinutes(destAcc.walkMin.toLong()) else itin.arrival

                    val combined = itin.copy(
                        legs = newLegs,
                        departure = dep,
                        arrival = arr
                    )
                    if (seenKeys.add(combined.routeKey)) {
                        results.add(combined)
                    }
                }
            }

            // 公共交通機関ルートが1件も見つからなかった場合のみダイレクト徒歩をフォールバックとして返す
            if (results.isEmpty()) {
                results.add(directFallback(TravelMode.WALK))
            }

            results
        } catch (e: Throwable) {
            android.util.Log.e("RouteSearch", "findRoutesBetweenCoordinates failed gracefully", e)
            listOf(directFallback(TravelMode.WALK))
        }
    }
}