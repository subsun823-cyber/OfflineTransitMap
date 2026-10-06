package com.example.offlinetransitmap

import java.io.File
import java.io.FilterInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.hypot

internal data class BusFeed(val id: String, val prefix: String, val label: String, val url: String)
internal data class BusFeedInfo(val start: Int, val end: Int, val note: String)
internal fun interface GtfsSink { fun row(table: String, values: List<Any?>) }

internal object BusGtfsImporter {
    fun day(raw: String): Int {
        LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)
        return raw.toInt()
    }
    fun seconds(raw: String): Int {
        val parts = raw.split(':').map { it.toInt() }
        require(parts.size == 3 && parts[0] in 0..47 && parts[1] in 0..59 && parts[2] in 0..59) { "時刻が不正です" }
        return parts[0]*3600+parts[1]*60+parts[2]
    }
    private data class Stop(val name: String, val lat: Double, val lon: Double, val zone: String?, val group: String)
    private data class Trip(val number: Long, val route: String, val headsign: String)

    fun convert(file: File, feed: BusFeed, sink: GtfsSink, check: () -> Unit = {}): BusFeedInfo {
        fun put(table: String, vararg values: Any?) = sink.row(table, values.toList())
        fun id(value: String): String { require(value.isNotBlank()) { "GTFSのIDが空です" };return feed.prefix + value }
        var bytes = 0L
        ZipFile(file).use { zip ->
            require(zip.size() in 1..100) { "GTFS ZIPの内容が不正です" }
            val names = zip.entries().asSequence().map { it.name }.toList()
            require(names.toSet().size == names.size) { "ZIP内のファイルが重複しています" }
            // Unsupported timetable mechanisms must not silently disappear.
            for (unsupported in listOf("frequencies.txt")) require(zip.getEntry(unsupported) == null) { "$unsupported は未対応です" }
            fun read(name: String, optional: Boolean = false, consume: (Map<String,String>) -> Unit) {
                val entry = zip.getEntry("$name.txt")
                if (entry == null && optional) return
                requireNotNull(entry) { "$name.txt がありません" }
                zip.getInputStream(entry).use { raw ->
                    val bounded = object : FilterInputStream(raw) {
                        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                        override fun read(buffer: ByteArray, off: Int, len: Int): Int = `in`.read(buffer,off,len).also { if (it > 0) count(it) }
                        fun count(n: Int) { bytes += n; require(bytes <= 768L*1024*1024) { "GTFSの展開サイズが上限を超えました" }; check() }
                    }
                    GtfsCsv(java.io.InputStreamReader(bounded,Charsets.UTF_8.newDecoder())).records(consume)
                }
            }
            val agencies = mutableMapOf<String,String>()
            read("agency") { r ->
                require(r["agency_timezone"] == "Asia/Tokyo") { "日本以外の時刻設定は未対応です" }
                agencies[r["agency_id"].orEmpty()] = r.getValue("agency_name")
            }
            require(agencies.isNotEmpty())
            val correctAgency = agencies.values.any { if (feed.id == "ntbus") it.contains("西東京バス") else it.contains("京王") && it.contains("バス") }
            require(correctAgency) { "選択した事業者のGTFSではありません" }
            val info = mutableListOf<Map<String,String>>()
            read("feed_info") { info.add(it) }
            require(info.size == 1) { "フィード情報が不正です" }
            val start = day(info.single().getValue("feed_start_date")); val end = day(info.single().getValue("feed_end_date"))
            require(start <= end)
            put("meta","schema_version","5"); put("feeds",feed.prefix,start,end)
            val kana = mutableMapOf<String,String>()
            read("translations",true) { r ->
                if (r["table_name"] == "stops" && r["field_name"] == "stop_name" && r["language"] == "ja-Hrkt") {
                    kana[r["record_id"].orEmpty().ifEmpty { r["field_value"].orEmpty() }] = r["translation"].orEmpty()
                }
            }
            val stopRows = mutableMapOf<String,Map<String,String>>()
            read("stops") { r -> require(stopRows.put(r.getValue("stop_id"),r) == null) { "停留所IDが重複しています" }; require(stopRows.size<=100_000) }
            val stops = mutableMapOf<String,Stop>()
            val groups = mutableMapOf<String,MutableList<Stop>>()
            for ((raw,r) in stopRows) {
                if (r["location_type"].orEmpty() == "1") continue
                require(r["location_type"].orEmpty() in listOf("","0")) { "この停留所形式は未対応です" }
                val parentId = r["parent_station"].orEmpty()
                val parent = if (parentId.isNotEmpty()) requireNotNull(stopRows[parentId]) else null
                val name = r.getValue("stop_name");require(name.isNotBlank()) { "停留所名が空です" }
                val lat = (r["stop_lat"].orEmpty().ifEmpty { parent?.get("stop_lat").orEmpty() }).toDouble()
                val lon = (r["stop_lon"].orEmpty().ifEmpty { parent?.get("stop_lon").orEmpty() }).toDouble()
                require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
                val nearby = groups[name].orEmpty().firstOrNull { hypot((lat-it.lat)*110540,(lon-it.lon)*111320*cos(Math.toRadians(lat)))<=700 }
                val group = nearby?.group ?: id(raw)
                val zone = r["zone_id"]?.takeIf { it.isNotEmpty() }
                val stop = Stop(name,lat,lon,zone,group); stops[raw]=stop; groups.getOrPut(name) { mutableListOf() }.add(stop)
                put("stations",id(raw),name,lat,lon,kana[raw] ?: kana[name].orEmpty(),group,"bus")
                put("stops",id(raw),id(raw),name,r["platform_code"].orEmpty(),zone?.let(::id))
            }
            val routes = mutableSetOf<String>()
            read("routes") { r ->
                require(r["route_type"] == "3") { "バス以外の路線が含まれています" }
                val raw=r.getValue("route_id"); require(routes.add(raw))
                val agencyId=r["agency_id"].orEmpty()
                val agency = agencies[agencyId] ?: if (agencyId.isEmpty() && agencies.size==1) agencies.values.single() else error("事業者が不明です")
                val color = r["route_color"].orEmpty().ifEmpty { "34659B" }
                require(color.matches(Regex("[0-9a-fA-F]{6}")))
                put("routes",id(raw),if(feed.id=="keio-bus") "京王バス" else agency,r["route_short_name"].orEmpty().ifEmpty { r["route_long_name"].orEmpty() },r["route_long_name"].orEmpty(),color.toInt(16),3)
            }
            val services = mutableSetOf<String>()
            read("calendar",true) { r ->
                val service=r.getValue("service_id"); require(services.add(service))
                val a=maxOf(start,day(r.getValue("start_date")));val b=minOf(end,day(r.getValue("end_date")))
                val flags=listOf("monday","tuesday","wednesday","thursday","friday","saturday","sunday").map { r.getValue(it).toInt().also { f -> require(f in 0..1) } }
                sink.row("calendar",listOf(id(service),a,b)+flags)
            }
            val exceptions=mutableSetOf<Pair<String,Int>>()
            read("calendar_dates",true) { r ->
                val service=r.getValue("service_id"); services.add(service)
                val date=day(r.getValue("date")); val exception=r.getValue("exception_type").toInt();require(exception in 1..2)
                require(exceptions.add(service to date)) { "運行例外が重複しています" }
                if (date in start..end) put("calendar_dates",id(service),date,exception)
            }
            val trips=mutableMapOf<String,Trip>()
            read("trips") { r ->
                val raw=r.getValue("trip_id");val route=r.getValue("route_id");require(route in routes && r.getValue("service_id") in services)
                val trip=Trip(trips.size.toLong()+1,route,r["trip_headsign"].orEmpty());require(trips.put(raw,trip)==null)
                require(trips.size<=500_000)
                put("trips",trip.number,id(raw),id(route),id(r.getValue("service_id")),trip.headsign,"")
            }
            val routeZones = mutableMapOf<String,MutableSet<String>>()
            val tripStops=mutableMapOf<Long,Int>()
            var count=0
            read("stop_times") { r ->
                val trip=trips.getValue(r.getValue("trip_id"));val stop=stops.getValue(r.getValue("stop_id"))
                val arr=seconds(r.getValue("arrival_time"));val dep=seconds(r.getValue("departure_time"));require(arr<=dep)
                val board=r["pickup_type"].orEmpty(); val alight=r["drop_off_type"].orEmpty()
                require(board in listOf("","0","1") && alight in listOf("","0","1")) { "予約制の乗降は未対応です" }
                val seq=r.getValue("stop_sequence").toInt();require(seq>=0)
                put("stop_times",trip.number,seq,id(r.getValue("stop_id")),id(r.getValue("stop_id")),arr,dep,r["stop_headsign"]?.takeIf { it.isNotEmpty() },if (board=="1") 0 else 1,if (alight=="1") 0 else 1)
                tripStops[trip.number]=(tripStops[trip.number] ?: 0)+1
                stop.zone?.let { routeZones.getOrPut(trip.route) { mutableSetOf() }.add(it) }
                require(++count<=5_000_000)
            }
            require(trips.isNotEmpty() && stops.isNotEmpty() && tripStops.size==trips.size && tripStops.values.all { it>=2 }) { "便の停車時刻が不足しています" }
            val fares=mutableMapOf<String,Int>()
            read("fare_attributes",true) { r ->
                require(r["currency_type"]=="JPY" && r["transfers"] == "0") { "運賃形式が未対応です" }
                val price=r.getValue("price").toBigDecimal().intValueExact();require(price>=0)
                require(fares.put(r.getValue("fare_id"),price)==null)
            }
            val rules=mutableListOf<Map<String,String>>()
            read("fare_rules",true) { rules.add(it);require(rules.size<=500_000) }
            val excluded=rules.filter { !it["contains_id"].isNullOrEmpty() }.map { it["route_id"].orEmpty() }.toSet()
            val prices=mutableMapOf<Triple<String,String,String>,Int>()
            for (r in rules) {
                val route=r["route_id"].orEmpty();require(route in routes) { "路線未指定の運賃は未対応です" }
                if (route in excluded) continue
                val price=fares.getValue(r.getValue("fare_id"))
                val origins=r["origin_id"]?.takeIf { it.isNotEmpty() }?.let { setOf(it) } ?: routeZones[route].orEmpty()
                val destinations=r["destination_id"]?.takeIf { it.isNotEmpty() }?.let { setOf(it) } ?: routeZones[route].orEmpty()
                for (a in origins) for (b in destinations) {
                    val key=Triple(route,a,b);val previous=prices[key]
                    prices[key]=if (previous==null || previous==price) price else -1
                    require(prices.size<=1_000_000) { "運賃件数が上限を超えました" }
                }
            }
            for ((key,price) in prices) if (price>=0) put("fares",id(key.first),id(key.second),id(key.third),price)
            val note="${feed.label}の有効期間: $start〜$end。経由条件・金額不明の運賃は表示しません。"
            put("app_data", "${feed.id}.note", note)
            return BusFeedInfo(start,end,note)
        }
    }
}
