package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DestinationNavigationTest {

    @Test
    fun testCalcDistanceMeters() {
        // 新宿駅付近 (35.6896, 139.7006) から 代々木駅付近 (35.6830, 139.7020)
        // 直線距離は約740m
        val d = calcDistanceMeters(35.6896, 139.7006, 35.6830, 139.7020)
        assertTrue("新宿〜代々木間の距離は約700-800mであること: $d", d in 700..800)
    }

    @Test
    fun testWalkDurationMinutes() {
        // 80m -> 1分以上 (約1.25分 -> 四捨五入で1分)
        assertEquals(1, walkDurationMinutes(80))
        // 400m -> 約6分
        val m = walkDurationMinutes(400)
        assertTrue("400mの徒歩は約6分前後であること: $m", m in 5..7)
        // 0m -> 最低1分
        assertEquals(1, walkDurationMinutes(0))
    }

    @Test
    fun testDestinationGeoJson() {
        val emptyJson = destinationGeoJson(null)
        assertEquals(EMPTY_ROUTE_JSON, emptyJson)

        val point = Pair(35.6896, 139.7006)
        val geoJson = destinationGeoJson(point, "指定した地点")
        assertTrue(geoJson.contains("\"type\":\"FeatureCollection\""))
        assertTrue(geoJson.contains("\"type\":\"Point\""))
        assertTrue(geoJson.contains("139.7006"))
        assertTrue(geoJson.contains("35.6896"))
        assertTrue(geoJson.contains("指定した地点"))
    }

    @Test
    fun testDirectWalkItineraryCreation() {
        val now = LocalDateTime.of(2026, 10, 8, 12, 0)
        val p1 = Pair(35.6896, 139.7006)
        val p2 = Pair(35.6920, 139.7030)
        val dist = calcDistanceMeters(p1.first, p1.second, p2.first, p2.second)
        val walkMin = walkDurationMinutes(dist)

        val walkLeg = RouteLeg(
            isWalk = true,
            fromName = "現在地",
            toName = "目的地",
            walkMinutes = walkMin,
            walkMeters = dist,
            path = listOf(p1, p2)
        )
        val itin = Itinerary(
            legs = listOf(walkLeg),
            departure = now,
            arrival = now.plusMinutes(walkMin.toLong()),
            transfers = 0,
            fare = 0,
            knownFare = 0,
            routeKey = "walk_direct"
        )

        assertEquals(1, itin.legs.size)
        assertTrue(itin.legs[0].isWalk)
        assertEquals("現在地", itin.legs[0].fromName)
        assertEquals("目的地", itin.legs[0].toName)
        assertEquals(dist, itin.legs[0].walkMeters)
        assertEquals(walkMin, itin.legs[0].walkMinutes)
        assertEquals(now.plusMinutes(walkMin.toLong()), itin.arrival)
    }

    @Test
    fun testBicycleDurationMinutes() {
        // 250m -> 約1分
        assertEquals(1, bicycleDurationMinutes(250))
        // 1000m (1km) -> 約4分 (徒歩だと約13分)
        assertEquals(4, bicycleDurationMinutes(1000))
        // 3000m (3km) -> 約12分
        assertEquals(12, bicycleDurationMinutes(3000))
        // 0m -> 最低1分
        assertEquals(1, bicycleDurationMinutes(0))

        // travelDurationMinutes ヘルパー
        assertEquals(walkDurationMinutes(1000), travelDurationMinutes(1000, TravelMode.WALK))
        assertEquals(bicycleDurationMinutes(1000), travelDurationMinutes(1000, TravelMode.BICYCLE))
        assertTrue(travelDurationMinutes(1000, TravelMode.BICYCLE) < travelDurationMinutes(1000, TravelMode.WALK))
    }

    @Test
    fun testDirectBicycleItineraryCreation() {
        val now = LocalDateTime.of(2026, 10, 10, 12, 0)
        val p1 = Pair(35.6896, 139.7006)
        val p2 = Pair(35.7000, 139.7100)
        val dist = calcDistanceMeters(p1.first, p1.second, p2.first, p2.second)
        val bikeMin = bicycleDurationMinutes(dist)

        val bikeLeg = RouteLeg(
            isWalk = true,
            fromName = "現在地",
            toName = "目的地",
            walkMinutes = bikeMin,
            walkMeters = dist,
            lineName = "自転車",
            trainType = "自転車",
            path = listOf(p1, p2)
        )
        val itin = Itinerary(
            legs = listOf(bikeLeg),
            departure = now,
            arrival = now.plusMinutes(bikeMin.toLong()),
            transfers = 0,
            fare = 0,
            knownFare = 0,
            routeKey = "bike_direct"
        )

        assertEquals(1, itin.legs.size)
        assertTrue(itin.legs[0].isWalk)
        assertEquals("自転車", itin.legs[0].lineName)
        assertEquals("現在地", itin.legs[0].fromName)
        assertEquals("目的地", itin.legs[0].toName)
        assertEquals(dist, itin.legs[0].walkMeters)
        assertEquals(bikeMin, itin.legs[0].walkMinutes)
        assertEquals("bike_direct", itin.routeKey)
    }

    @Test
    fun testComputeInitialGuidanceBicycle() {
        val now = LocalDateTime.now().plusHours(1)
        val p1 = Pair(35.6896, 139.7006)
        val p2 = Pair(35.7000, 139.7100)
        val bikeLeg = RouteLeg(
            isWalk = true,
            fromName = "現在地",
            toName = "池袋駅",
            walkMinutes = 15,
            walkMeters = 3000,
            lineName = "自転車",
            trainType = "自転車",
            path = listOf(p1, p2)
        )
        val itin = Itinerary(
            legs = listOf(bikeLeg),
            departure = LocalDateTime.now(),
            arrival = now,
            transfers = 0,
            fare = 0,
            knownFare = 0,
            routeKey = "bike_direct"
        )

        val guidance = computeInitialGuidance(itin, TravelMode.BICYCLE, null)
        assertEquals(GuidanceKind.BICYCLE, guidance.kind)
        assertEquals("池袋駅へ", guidance.title)
        assertTrue(guidance.subtitle.contains("自転車で向かう"))
        assertTrue(guidance.subtitle.contains("15分"))
        assertEquals("目的地に到着", guidance.next)
    }

    @Test
    fun testEvaluateRoadTurnByTurn() {
        val now = LocalDateTime.now().plusMinutes(20)
        // 北進(35.6800 -> 35.6810)ののち東進(35.6810, 139.7000 -> 35.6810, 139.7010) = 右折
        val p1 = Pair(35.6800, 139.7000)
        val p2 = Pair(35.6810, 139.7000)
        val p3 = Pair(35.6810, 139.7010)
        val path = listOf(p1, p2, p3)

        val info = evaluateRoadTurnByTurn(
            path = path,
            curLat = 35.6800,
            curLon = 139.7000,
            destName = "八王子駅",
            isBike = false,
            scheduledArrival = now
        )

        assertEquals(ManeuverType.TURN_RIGHT, info.maneuver)
        assertTrue(info.title.contains("右折"))
        assertTrue(info.subtitle.contains("八王子駅"))
        assertTrue(info.totalRemainingMeters > 0)
        assertEquals(4, info.remainingPath.size)
    }

    @Test
    fun testEvaluateRoadTurnByTurnArrive() {
        val now = LocalDateTime.now().plusMinutes(5)
        val p1 = Pair(35.68000, 139.70000)
        val p2 = Pair(35.68010, 139.70010) // 15m以内
        val path = listOf(p1, p2)

        val info = evaluateRoadTurnByTurn(
            path = path,
            curLat = 35.68005,
            curLon = 139.70005,
            destName = "八王子駅",
            isBike = false,
            scheduledArrival = now
        )

        assertEquals(ManeuverType.ARRIVE, info.maneuver)
        assertTrue(info.title.contains("到着"))
    }

    @Test
    fun testRemainingRoadOverlay() {
        val path = listOf(Pair(35.6800, 139.7000), Pair(35.6810, 139.7010))
        val overlay = remainingRoadOverlay(path)
        assertTrue(overlay.geoJson.contains("\"type\":\"LineString\""))
        assertTrue(overlay.geoJson.contains("139.7"))
        assertTrue(overlay.geoJson.contains("35.68"))
        assertEquals(2, overlay.points.size)
    }

    @Test
    fun testComputeTurnPenaltyStraight() {
        // 直進 (角度差 15度) はペナルティ 0
        val p = computeTurnPenalty(
            angleDiffDeg = 15.0,
            prevEdgeDistMeters = 100.0,
            isConsecutiveTurn = false,
            prevRoadClass = 1,
            currRoadClass = 1,
            mode = TravelMode.BICYCLE
        )
        assertEquals(0.0, p, 0.001)
    }

    @Test
    fun testComputeTurnPenaltySingleTurn() {
        // 90度直角ターン (単発、長区間後)
        val p = computeTurnPenalty(
            angleDiffDeg = 90.0,
            prevEdgeDistMeters = 150.0,
            isConsecutiveTurn = false,
            prevRoadClass = 4,
            currRoadClass = 4,
            mode = TravelMode.BICYCLE
        )
        // 50 + (90 - 85) * 0.5 = 52.5m
        assertEquals(52.5, p, 0.001)
    }

    @Test
    fun testComputeTurnPenaltyFrequentAndConsecutiveTurns() {
        // 頻繁な曲がり (直前区間40mで短区間、かつ2連続ターン、細街路)
        val p = computeTurnPenalty(
            angleDiffDeg = 90.0,
            prevEdgeDistMeters = 40.0,
            isConsecutiveTurn = true,
            prevRoadClass = 6,
            currRoadClass = 6,
            mode = TravelMode.BICYCLE
        )
        // 基本 52.5 + 短区間 55.0 + 連続 60.0 + 細街路 25.0 = 192.5m
        assertTrue("頻繁な連続ターンには強力な累積ペナルティが課されること: $p", p >= 180.0)
    }

    @Test
    fun testComputeTurnPenaltyMajorRoadKeep() {
        // 大通り (roadClass 1) から細街路 (roadClass 4) への離脱ターン
        val pLeave = computeTurnPenalty(
            angleDiffDeg = 90.0,
            prevEdgeDistMeters = 150.0,
            isConsecutiveTurn = false,
            prevRoadClass = 1,
            currRoadClass = 4,
            mode = TravelMode.BICYCLE
        )
        // 大通り (roadClass 1) から自転車道 (roadClass 3) への離脱ターン (迂回防止)
        val pLeaveCycleway = computeTurnPenalty(
            angleDiffDeg = 90.0,
            prevEdgeDistMeters = 150.0,
            isConsecutiveTurn = false,
            prevRoadClass = 1,
            currRoadClass = 3,
            mode = TravelMode.BICYCLE
        )
        // 大通り同士 (roadClass 1 -> 1) のターン
        val pKeep = computeTurnPenalty(
            angleDiffDeg = 90.0,
            prevEdgeDistMeters = 150.0,
            isConsecutiveTurn = false,
            prevRoadClass = 1,
            currRoadClass = 1,
            mode = TravelMode.BICYCLE
        )
        assertTrue("大通りから生活道路への離脱には追加ペナルティがあること", pLeave > pKeep)
        assertTrue("大通りから自転車道への不要な離脱にも追加ペナルティがあること", pLeaveCycleway > pKeep)
    }

    @Test
    fun testDistanceToSegmentMeters() {
        // 線分: (35.6800, 139.7000) から (35.6820, 139.7000) (南北に約220m走る直線)
        val aLat = 35.6800
        val aLon = 139.7000
        val bLat = 35.6820
        val bLon = 139.7000

        // 1. 線分上の点 (35.6810, 139.7000) -> 距離 0m
        val dOn = distanceToSegmentMeters(35.6810, 139.7000, aLat, aLon, bLat, bLon)
        assertEquals(0.0, dOn, 1.0)

        // 2. 線分から東に約50m離れた点
        // 経度差 0.00055度は約50m
        val dEast = distanceToSegmentMeters(35.6810, 139.70055, aLat, aLon, bLat, bLon)
        assertTrue("東西に約50m離れていること: $dEast", dEast in 45.0..55.0)

        // 3. 端点Aより南に外れた点 (35.6790, 139.7000) -> 端点Aまでの距離 (約110m)
        val dSouth = distanceToSegmentMeters(35.6790, 139.7000, aLat, aLon, bLat, bLon)
        assertTrue("端点外の距離が正しく求まること: $dSouth", dSouth in 100.0..120.0)
    }

    @Test
    fun testMinDistanceToPathMeters() {
        // パス: (35.6800, 139.7000) -> (35.6810, 139.7000) -> (35.6810, 139.7020)
        val path = listOf(
            Pair(35.6800, 139.7000),
            Pair(35.6810, 139.7000),
            Pair(35.6810, 139.7020)
        )

        // パス上の点
        val dPath = minDistanceToPathMeters(35.6805, 139.7000, path)
        assertEquals(0.0, dPath, 1.0)

        // パスから60m離れた点
        val dFar = minDistanceToPathMeters(35.6805, 139.7007, path)
        assertTrue("パスから約60m離れていること: $dFar", dFar in 55.0..68.0)
    }

    @Test
    fun testEvaluateRoadTurnByTurnOffCourseDetection() {
        val now = LocalDateTime.now().plusMinutes(10)
        // ルート: (35.6800, 139.7000) から (35.6850, 139.7000) へ約550m
        val path = listOf(
            Pair(35.6800, 139.7000),
            Pair(35.6820, 139.7000),
            Pair(35.6850, 139.7000)
        )

        // 1. ルート近傍 (ルートから5m) -> isOffCourse は false
        val infoOnRoute = evaluateRoadTurnByTurn(
            path = path,
            curLat = 35.6810,
            curLon = 139.70005,
            destName = "テスト目的地",
            isBike = false,
            scheduledArrival = now
        )
        org.junit.Assert.assertFalse("ルート近傍ではオフコースでないこと", infoOnRoute.isOffCourse)

        // 2. ルートから60m東に外れた位置 -> isOffCourse は true (オートリルート対象)
        val infoOffRoute = evaluateRoadTurnByTurn(
            path = path,
            curLat = 35.6810,
            curLon = 139.7007,
            destName = "テスト目的地",
            isBike = false,
            scheduledArrival = now
        )
        assertTrue("ルートから60m離れたらオフコースと判定されること", infoOffRoute.isOffCourse)
        assertTrue("distToPathMetersが正しく記録されていること: ${infoOffRoute.distToPathMeters}", infoOffRoute.distToPathMeters >= 55.0)
    }
}

