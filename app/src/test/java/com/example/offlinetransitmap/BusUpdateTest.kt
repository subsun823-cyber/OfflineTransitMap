package com.example.offlinetransitmap

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BusUpdateTest {
    @Test fun csvHandlesQuotedCommasEscapedQuotesNewlinesAndBom() {
        val records=mutableListOf<Map<String,String>>()
        GtfsCsv(StringReader("\uFEFFid,name\r\n1,\"a,b\"\r\n2,\"a\"\"b\n続き\"\r\n")).records { records.add(it) }
        assertEquals("a,b",records[0]["name"]);assertEquals("a\"b\n続き",records[1]["name"])
    }
    @Test fun csvRejectsBrokenQuotesAndDuplicateColumns() {
        for(raw in listOf("id,name\n1,\"broken", "id,id\n1,2", "id,name\n1,\"x\"junk", "id,name\n1,2,3")) {
            assertThrows(IllegalArgumentException::class.java) { GtfsCsv(StringReader(raw)).records {} }
        }
    }
    @Test fun futureExpiredOlderAndCurrentSchedulesAreSeparated() {
        assertEquals(UpdateDecision.APPLY,busUpdateDecision(20261001,20261231,20261231,20261006))
        assertEquals(UpdateDecision.WAIT,busUpdateDecision(20270101,20270331,20261231,20261006))
        assertEquals(UpdateDecision.EXPIRED,busUpdateDecision(20260101,20260930,20260930,20261006))
        assertEquals(UpdateDecision.OLDER,busUpdateDecision(20261001,20261031,20261231,20261006))
        assertEquals(UpdateDecision.APPLY,busUpdateDecision(20261001,20261231,20261231,20261231))
    }
    @Test fun httpsEndpointsAndServiceDayTimesAreChecked() {
        assertTrue(BusUpdateDownload.validUrl("https://example.org/feed.zip?version=latest"))
        for(url in listOf("http://example.org/feed.zip","https://user:pass@example.org/x","file:///tmp/feed.zip","https://example.org/x#fragment")) assertFalse(BusUpdateDownload.validUrl(url))
        assertEquals(87180,BusGtfsImporter.seconds("24:13:00"));assertEquals(780,BusGtfsImporter.seconds("00:13:00"))
        assertThrows(IllegalArgumentException::class.java) { BusGtfsImporter.seconds("24:60:00") }
        assertThrows(Exception::class.java) { BusGtfsImporter.day("20260230") }
    }
    private fun fixture(edit: MutableMap<String,String>.() -> Unit = {}): File {
        val files=linkedMapOf(
            "agency" to "agency_id,agency_name,agency_timezone\na,西東京バス,Asia/Tokyo\n",
            "feed_info" to "feed_start_date,feed_end_date\n20261001,20261231\n",
            "stops" to "stop_id,stop_name,stop_lat,stop_lon,zone_id,platform_code\na,駅前,35.6,139.3,A,1\nb,終点,35.61,139.31,B,2\n",
            "routes" to "route_id,agency_id,route_short_name,route_type\nr,a,八01,3\n",
            "calendar" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\ns,1,1,1,1,1,0,0,20260101,20271231\n",
            "calendar_dates" to "service_id,date,exception_type\ns,20261012,2\nextra,20261012,1\ns,20270101,1\n",
            "trips" to "route_id,service_id,trip_id,trip_headsign\nr,s,t,終点\nr,extra,t2,終点\n",
            "stop_times" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\nt,23:50:00,23:50:00,a,1,0,1\nt,24:13:00,24:13:00,b,2,1,0\nt2,23:50:00,23:50:00,a,1,0,1\nt2,24:13:00,24:13:00,b,2,1,0\n",
            "fare_attributes" to "fare_id,price,currency_type,transfers\nf,200.0,JPY,0\n",
            "fare_rules" to "fare_id,route_id,origin_id,destination_id,contains_id\nf,r,,,\n"
        )
        files.edit()
        val zip=File.createTempFile("gtfs-test-",".zip")
        ZipOutputStream(zip.outputStream()).use { out -> files.forEach { (name,text) -> out.putNextEntry(ZipEntry("$name.txt"));out.write(text.toByteArray());out.closeEntry() } }
        return zip
    }
    @Test fun importsCalendarsNightTimesBoardingAndUniformFares() {
        val zip=fixture();val rows=mutableMapOf<String,MutableList<List<Any?>>>()
        try {
            val info=BusGtfsImporter.convert(zip,BusFeed("ntbus","NT_","西東京バス",""),GtfsSink { t,v -> rows.getOrPut(t) { mutableListOf() }.add(v) })
            assertEquals(20261231,info.end)
            assertEquals(listOf("NT_s",20261001,20261231,1,1,1,1,1,0,0),rows.getValue("calendar").single())
            assertEquals(2,rows.getValue("calendar_dates").size)
            assertEquals(87180,rows.getValue("stop_times")[1][4]);assertEquals(0,rows.getValue("stop_times")[1][7])
            assertEquals(4,rows.getValue("fares").size)
            assertEquals("西東京バス",rows.getValue("routes").single()[1])
        } finally { zip.delete() }
    }
    @Test fun wrongAgencyUnsupportedServiceAndUnknownReferencesAbort() {
        val cases=listOf<MutableMap<String,String>.() -> Unit>(
            { this["agency"]=getValue("agency").replace("西東京バス","他社バス") },
            { this["frequencies"]="trip_id,start_time,end_time,headway_secs\nt,06:00:00,07:00:00,300\n" },
            { this["stop_times"]=getValue("stop_times").replace(",a,1,",",missing,1,") },
            { this["stop_times"]=getValue("stop_times").replace(",1,0,1",",1,2,1") }
        )
        for(edit in cases) {
            val zip=fixture(edit)
            try { assertThrows(Exception::class.java) { BusGtfsImporter.convert(zip,BusFeed("ntbus","NT_","西東京バス",""),GtfsSink { _,_ -> }) } }
            finally { zip.delete() }
        }
    }
    @Test fun pathDependentFaresAreNotQuotedAsOrdinaryFares() {
        val zip=fixture { this["fare_rules"]=getValue("fare_rules").replace("f,r,,,","f,r,A,B,via") }
        try {
            var fares=0
            BusGtfsImporter.convert(zip,BusFeed("ntbus","NT_","西東京バス",""),GtfsSink { t,_ -> if(t=="fares") fares++ })
            assertEquals(0,fares)
        } finally { zip.delete() }
    }
    @Test fun cancellationPropagatesBeforePublishingAnything() {
        val zip=fixture()
        try { assertThrows(InterruptedException::class.java) {
            BusGtfsImporter.convert(zip,BusFeed("ntbus","NT_","西東京バス",""),GtfsSink { _,_ -> }) { throw InterruptedException() }
        } } finally { zip.delete() }
    }
    @Test fun suppliedKeioGtfsUsesTheProductionStreamingImporter() {
        val path=System.getenv("OFFLINE_TRANSIT_GTFS_FIXTURE")
        org.junit.Assume.assumeTrue("Set OFFLINE_TRANSIT_GTFS_FIXTURE for the supplied-feed integration check",path!=null)
        val output=System.getenv("OFFLINE_TRANSIT_GTFS_ROWS")?.let { File(it).bufferedWriter() }
        val counts=mutableMapOf<String,Int>()
        try {
            val info=BusGtfsImporter.convert(File(requireNotNull(path)),BusFeed("keio-bus","GTFS_KEIO_BUS:","京王バス",""),GtfsSink { table,values ->
                counts[table]=(counts[table] ?: 0)+1
                output?.appendLine(table+"\t"+values.joinToString("\t") { value -> when(value) {
                    null -> "n"
                    is Double -> "d$value"
                    is Number -> "i$value"
                    else -> "s"+java.util.Base64.getEncoder().encodeToString(value.toString().toByteArray())
                } })
            })
            assertEquals(20261001,info.start);assertEquals(20261231,info.end)
            assertEquals(2927,counts["stations"]);assertEquals(254,counts["routes"])
            assertEquals(29550,counts["trips"]);assertEquals(447000,counts["stop_times"])
            assertEquals(106189,counts["fares"])
        } finally { output?.close() }
    }

    @Test fun conditionalDownloadAndRedirectProtection() {
        val target=File.createTempFile("download-test-",".zip")
        val requests=mutableMapOf<String,String>()
        fun connection(url: java.net.URL,status: Int,body: ByteArray=byteArrayOf(1,2,3),location: String?=null) = object : java.net.HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy()=false
            override fun getResponseCode()=status
            override fun getInputStream()=body.inputStream()
            override fun getContentLengthLong()=body.size.toLong()
            override fun getHeaderField(name: String): String? = when(name) { "Location" -> location; "ETag" -> "v2"; else -> null }
            override fun setRequestProperty(key: String,value: String) { requests[key]=value }
        }
        try {
            val downloaded=BusUpdateDownload.fetch("https://example.org/feed.zip",target,"old","",{connection(it,200)}) {}
            assertEquals("old",requests["If-None-Match"]);assertEquals("v2",downloaded?.etag)
            assertArrayEquals(byteArrayOf(1,2,3),target.readBytes())
            assertEquals(BusUpdateDownload.hash(target),downloaded?.hash)
            assertNull(BusUpdateDownload.fetch("https://example.org/feed.zip",target,"v2","",{connection(it,304)}) {})
            assertThrows(IllegalArgumentException::class.java) {
                BusUpdateDownload.fetch("https://example.org/feed.zip",target,"","",{connection(it,302,location="http://example.org/bad")}) {}
            }
            assertThrows(IllegalStateException::class.java) {
                BusUpdateDownload.fetch("https://example.org/feed.zip",target,"","",{connection(it,500)}) {}
            }
        } finally { target.delete() }
    }

}
