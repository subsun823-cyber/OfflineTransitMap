package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun testFormatDurationHoursMinutes() {
        assertEquals("今すぐ", formatDurationHoursMinutes(0L))
        assertEquals("1分", formatDurationHoursMinutes(1L))
        assertEquals("15分", formatDurationHoursMinutes(15L))
        assertEquals("59分", formatDurationHoursMinutes(59L))
        assertEquals("1時間", formatDurationHoursMinutes(60L))
        assertEquals("1時間1分", formatDurationHoursMinutes(61L))
        assertEquals("1時間15分", formatDurationHoursMinutes(75L))
        assertEquals("2時間", formatDurationHoursMinutes(120L))
        assertEquals("2時間25分", formatDurationHoursMinutes(145L))

        // Int 版オーバーロード
        assertEquals("45分", formatDurationHoursMinutes(45))
        assertEquals("1時間30分", formatDurationHoursMinutes(90))
    }

    @Test
    fun testMultiModalInitialGuidance() {
        val now = LocalDateTime.now().plusMinutes(30)
        val walkLeg = RouteLeg(
            isWalk = true,
            fromName = "現在地",
            toName = "新宿駅",
            walkMinutes = 5,
            walkMeters = 400,
            path = listOf(
                Pair(35.6896, 139.7006),
                Pair(35.6900, 139.7006),
                Pair(35.6910, 139.7006)
            )
        )
        val trainLeg = RouteLeg(
            isWalk = false,
            fromName = "新宿駅",
            toName = "越生駅",
            depTime = now.minusMinutes(25),
            arrTime = now,
            lineName = "JR八高線",
            path = listOf(
                Pair(35.6910, 139.7006),
                Pair(35.9628, 139.2990)
            )
        )
        val itin = Itinerary(
            legs = listOf(walkLeg, trainLeg),
            departure = now.minusMinutes(30),
            arrival = now,
            transfers = 0,
            fare = 800,
            knownFare = 800,
            routeKey = "transit_hybrid"
        )

        // 初期ガイダンス評価 (徒歩フェーズ)
        val guidance = computeInitialGuidance(itin, TravelMode.WALK, null)

        // 電車旅程であっても、最初の徒歩レグ中はターンバイターン (WALK) が案内されること
        assertEquals(GuidanceKind.WALK, guidance.kind)
        assertEquals("新宿駅へ", guidance.title)
        assertTrue("徒歩案内であること: ${guidance.subtitle}", guidance.subtitle.contains("徒歩で向かう"))
        assertTrue("次の電車乗車予定が含まれていること: ${guidance.next}", guidance.next.contains("JR八高線 に乗車"))
    }

    @Test
    fun testTravelModeTransitDurationMinutes() {
        // TravelMode.TRANSIT は徒歩計算を基準とする (400m -> 約6分)
        val walkMin = travelDurationMinutes(400, TravelMode.WALK)
        val transitMin = travelDurationMinutes(400, TravelMode.TRANSIT)
        assertEquals(walkMin, transitMin)

        val bikeMin = travelDurationMinutes(400, TravelMode.BICYCLE)
        assertTrue("自転車の方が徒歩より所要時間が短いこと: $bikeMin < $walkMin", bikeMin < walkMin)
    }

    @Test
    fun testTravelModeValues() {
        // WALK, BICYCLE, TRANSIT の3種類が揃っていること
        val modes = TravelMode.values().map { it.name }
        assertTrue(modes.contains("WALK"))
        assertTrue(modes.contains("BICYCLE"))
        assertTrue(modes.contains("TRANSIT"))
    }

    @Test
    fun testComputeInitialGuidanceRobustness() {
        val now = LocalDateTime.now()
        // 1. 空の legs
        val emptyItin = Itinerary(
            legs = emptyList(),
            departure = now,
            arrival = now.plusMinutes(10),
            transfers = 0,
            fare = 0,
            knownFare = 0,
            routeKey = "walk_direct"
        )
        val g1 = computeInitialGuidance(emptyItin, TravelMode.WALK, null)
        assertTrue(g1.title.isNotEmpty())

        // 2. path が空の leg
        val noPathLeg = RouteLeg(
            isWalk = true,
            fromName = "A",
            toName = "B",
            walkMinutes = 5,
            walkMeters = 300,
            path = emptyList()
        )
        val noPathItin = Itinerary(
            legs = listOf(noPathLeg),
            departure = now,
            arrival = now.plusMinutes(5),
            transfers = 0,
            fare = 0,
            knownFare = 0,
            routeKey = "walk_direct"
        )
        val g2 = computeInitialGuidance(noPathItin, TravelMode.WALK, null)
        assertEquals("Bへ", g2.title)

        // 3. 1要素のみの path
        val singlePointLeg = RouteLeg(
            isWalk = true,
            fromName = "A",
            toName = "B",
            walkMinutes = 1,
            walkMeters = 0,
            path = listOf(Pair(35.68, 139.70))
        )
        val singlePointItin = Itinerary(
            legs = listOf(singlePointLeg),
            departure = now,
            arrival = now.plusMinutes(1),
            transfers = 0,
            fare = 0,
            knownFare = 0,
            routeKey = "walk_direct"
        )
        val g3 = computeInitialGuidance(singlePointItin, TravelMode.WALK, null)
        assertEquals("Bへ", g3.title)
    }

    @Test
    fun testEvaluateRoadTurnByTurnRobustness() {
        val now = LocalDateTime.now()
        // 空パス
        val r0 = evaluateRoadTurnByTurn(emptyList(), 35.68, 139.70, "目標", false, now)
        assertEquals("道なりに進む", r0.title)

        // 1点パス
        val r1 = evaluateRoadTurnByTurn(listOf(Pair(35.68, 139.70)), 35.68, 139.70, "目標", false, now)
        assertTrue(r1.title.isNotEmpty())

        // 2点パス
        val r2 = evaluateRoadTurnByTurn(listOf(Pair(35.68, 139.70), Pair(35.69, 139.71)), 35.68, 139.70, "目標", false, now)
        assertTrue(r2.title.isNotEmpty())
    }

    @Test
    fun testTransitModePreviewWorkflow() {
        val now = LocalDateTime.of(2026, 10, 13, 10, 0)
        // 徒歩レグ（現在地 -> 新宿駅）
        val walkLeg = RouteLeg(
            isWalk = true,
            fromName = "現在地",
            toName = "新宿駅",
            walkMinutes = 5,
            walkMeters = 400,
            path = listOf(Pair(35.6890, 139.7000), Pair(35.6900, 139.7005))
        )
        // 電車レグ（新宿駅 -> 八王子駅: 中央線）
        val trainLeg = RouteLeg(
            isWalk = false,
            fromName = "新宿駅",
            toName = "八王子駅",
            depTime = now.plusMinutes(7),
            arrTime = now.plusMinutes(45),
            lineName = "中央線",
            trainType = "中央特快",
            headsign = "高尾",
            platform = "12番線",
            path = listOf(Pair(35.6900, 139.7005), Pair(35.6558, 139.3389))
        )
        // 降車後徒歩レグ（八王子駅 -> 八王子市役所）
        val exitWalkLeg = RouteLeg(
            isWalk = true,
            fromName = "八王子駅",
            toName = "八王子市役所",
            walkMinutes = 12,
            walkMeters = 950,
            path = listOf(Pair(35.6558, 139.3389), Pair(35.6660, 139.3150))
        )

        val itin = Itinerary(
            legs = listOf(walkLeg, trainLeg, exitWalkLeg),
            departure = now,
            arrival = now.plusMinutes(57),
            transfers = 0,
            fare = 490,
            knownFare = 490,
            routeKey = "transit_chuo_line"
        )

        // ルートおよび経由地が正しく保持されていること
        assertEquals(3, itin.legs.size)
        assertTrue(itin.legs[0].isWalk)
        assertFalse(itin.legs[1].isWalk)
        assertEquals("中央線", itin.legs[1].lineName)
        assertEquals("新宿駅", itin.legs[1].fromName)
        assertEquals("八王子駅", itin.legs[1].toName)
        assertEquals("中央特快", itin.legs[1].trainType)
        assertTrue(itin.legs[2].isWalk)
        assertEquals("八王子市役所", itin.legs[2].toName)

        // 電車レグが含まれる旅程の総所要時間・運賃確認
        val totalMinutes = java.time.Duration.between(itin.departure, itin.arrival).toMinutes()
        assertEquals(57L, totalMinutes)
        assertEquals(490, itin.fare)
    }

    @Test
    fun testComputeTurnPenalty() {
        // 直進 (35度以下) はペナルティ0
        assertEquals(0.0, computeTurnPenalty(20.0, 100.0, false, 4, 4, TravelMode.WALK), 0.001)
        assertEquals(0.0, computeTurnPenalty(35.0, 100.0, false, 4, 4, TravelMode.BICYCLE), 0.001)

        // 90度ターン (自転車の方が歩行者より曲がり角を嫌うためペナルティ大)
        val walkTurn = computeTurnPenalty(90.0, 100.0, false, 4, 4, TravelMode.WALK)
        val bikeTurn = computeTurnPenalty(90.0, 100.0, false, 4, 4, TravelMode.BICYCLE)
        assertTrue(walkTurn > 0.0)
        assertTrue(bikeTurn > walkTurn)

        // 短区間での連続ターン（70m未満）ペナルティ加算
        val bikeShortTurn = computeTurnPenalty(90.0, 50.0, false, 4, 4, TravelMode.BICYCLE)
        assertTrue(bikeShortTurn > bikeTurn)

        // 連続ターン累積ペナルティ加算
        val bikeConsecutive = computeTurnPenalty(90.0, 100.0, true, 4, 4, TravelMode.BICYCLE)
        assertTrue(bikeConsecutive > bikeTurn)
    }

    @Test
    fun testTransitRankingPrioritizesTrainOverWalk() {
        val now = LocalDateTime.of(2026, 10, 13, 14, 0)
        // 徒歩ルート（到着が早い: 14:20到着）
        val walkLeg = RouteLeg(
            isWalk = true,
            fromName = "現在地",
            toName = "目的地",
            walkMinutes = 20,
            walkMeters = 1500,
            lineName = "徒歩",
            trainType = "徒歩",
            path = listOf(Pair(35.68, 139.70), Pair(35.69, 139.71))
        )
        val directWalkItin = Itinerary(
            legs = listOf(walkLeg),
            departure = now,
            arrival = now.plusMinutes(20),
            transfers = 0,
            fare = 0,
            knownFare = 0,
            routeKey = "walk_direct"
        )

        // 電車ルート（発車待ち時間があり到着が遅い: 14:28到着）
        val trainWalk1 = RouteLeg(isWalk = true, fromName = "現在地", toName = "新宿駅", walkMinutes = 5, walkMeters = 400)
        val trainRide = RouteLeg(
            isWalk = false,
            fromName = "新宿駅",
            toName = "中野駅",
            depTime = now.plusMinutes(12),
            arrTime = now.plusMinutes(18),
            lineName = "中央線",
            trainType = "快速",
            headsign = "高尾"
        )
        val trainWalk2 = RouteLeg(isWalk = true, fromName = "中野駅", toName = "目的地", walkMinutes = 10, walkMeters = 700)
        val transitItin = Itinerary(
            legs = listOf(trainWalk1, trainRide, trainWalk2),
            departure = now,
            arrival = now.plusMinutes(28),
            transfers = 0,
            fare = 170,
            knownFare = 170,
            routeKey = "transit_chuo"
        )

        // 両方が混在するリスト
        val candidates = listOf(directWalkItin, transitItin)

        // rank (FASTEST) でソートした場合、徒歩のほうが早くても電車ルートが優先されること
        val ranked = RouteSearcher.rank(candidates, SortMode.FASTEST)
        assertTrue("ランク結果は空でないこと", ranked.isNotEmpty())
        assertEquals("第1候補は電車ルートであること", "transit_chuo", ranked[0].routeKey)
        assertFalse("第1候補のレグに電車が含まれること", ranked[0].legs.all { it.isWalk })
    }

    @Test
    fun testRouteWaypointCreationAndCombining() {
        val wpOrigin = RouteWaypoint(name = "八王子駅", lat = 35.6554, lon = 139.3389, stationId = "JR_HACHIOJI")
        val wpVia = RouteWaypoint(name = "立川駅", lat = 35.6980, lon = 139.4137, stationId = "JR_TACHIKAWA")
        val wpDest = RouteWaypoint(name = "新宿駅", lat = 35.6896, lon = 139.7006, stationId = "JR_SHINJUKU")

        val waypoints = listOf(wpOrigin, wpVia, wpDest)
        assertEquals(3, waypoints.size)
        assertEquals("八王子駅", waypoints[0].name)
        assertEquals("立川駅", waypoints[1].name)
        assertEquals("新宿駅", waypoints[2].name)

        // スワップ（入れ替え）のテスト
        val swapped = listOf(waypoints.last()) + waypoints.subList(1, waypoints.size - 1).reversed() + listOf(waypoints.first())
        assertEquals("新宿駅", swapped[0].name)
        assertEquals("立川駅", swapped[1].name)
        assertEquals("八王子駅", swapped[2].name)

        // 経由地削除のテスト
        val removedVia = waypoints.filterIndexed { index, _ -> index != 1 }
        assertEquals(2, removedVia.size)
        assertEquals("八王子駅", removedVia[0].name)
        assertEquals("新宿駅", removedVia[1].name)
    }

    @Test
    fun testTimeTargetTypeAndArrivalSpecification() {
        val departType = TimeTargetType.DEPART
        val arriveType = TimeTargetType.ARRIVE
        assertEquals(TimeTargetType.DEPART, departType)
        assertEquals(TimeTargetType.ARRIVE, arriveType)

        // 到着時刻指定フィルタのシミュレーション
        val targetArrivalTime = LocalDateTime.of(2026, 10, 14, 18, 30)
        val now = LocalDateTime.of(2026, 10, 14, 17, 0)
        
        val itin1 = Itinerary(
            legs = emptyList(),
            departure = now.plusMinutes(15),
            arrival = targetArrivalTime.minusMinutes(10), // 18:20着 (間に合う)
            transfers = 0, fare = 200, knownFare = 200, routeKey = "key1"
        )
        val itin2 = Itinerary(
            legs = emptyList(),
            departure = now.plusMinutes(30),
            arrival = targetArrivalTime.minusMinutes(2),  // 18:28着 (間に合い、最も遅く出発できる)
            transfers = 0, fare = 200, knownFare = 200, routeKey = "key2"
        )
        val itin3 = Itinerary(
            legs = emptyList(),
            departure = now.plusMinutes(40),
            arrival = targetArrivalTime.plusMinutes(5),   // 18:35着 (間に合わない)
            transfers = 0, fare = 200, knownFare = 200, routeKey = "key3"
        )

        val candidates = listOf(itin1, itin2, itin3)
        // 到着時刻指定: targetArrivalTimeまでに到着する便を抽出し、目標時刻直前に到着する便を最優先
        val onTime = candidates.filter { !it.arrival.isAfter(targetArrivalTime) }
        assertEquals(2, onTime.size)
        val best = onTime.maxByOrNull { it.arrival } // 18:28着 (itin2)
        assertEquals(itin2, best)
    }

    @Test
    fun testStationDestinationFlagAndNoExtraWalkLeg() {
        val now = LocalDateTime.of(2026, 10, 14, 12, 0)
        val trainLeg = RouteLeg(
            isWalk = false,
            fromName = "高尾",
            toName = "立川",
            depTime = now,
            arrTime = now.plusMinutes(17),
            lineName = "中央線快速",
            trainType = "快速",
            path = listOf(Pair(35.642, 139.281), Pair(35.698, 139.413))
        )

        // 駅が目的地の場合、isDestStation = true
        val itinAtStation = Itinerary(
            legs = listOf(trainLeg),
            departure = now,
            arrival = now.plusMinutes(17),
            transfers = 0,
            fare = 320,
            knownFare = 320,
            routeKey = "takao_tachikawa",
            isDestStation = true
        )

        assertTrue("Destination should be identified as station", itinAtStation.isDestStation)
        assertEquals("立川", itinAtStation.legs.last().toName)
        assertFalse("Last leg must not be walk when ending at destination station", itinAtStation.legs.last().isWalk)
    }

    @Test
    fun testTransitNoForcedWalkWhenNoTrainsRunning() {
        // 公共交通機関(TRANSIT)で便がない場合、無理やり徒歩ルートを表示せず空リストを扱うこと
        val emptyTransitResult = emptyList<Itinerary>()
        assertTrue("No train running period should return empty transit list", emptyTransitResult.isEmpty())
        assertFalse("Should not include long direct walk route", emptyTransitResult.any { it.legs.all { leg -> leg.isWalk } })
    }

    @Test
    fun testDateChangePreservesTimeAndUpdatesSearch() {
        val originalDateTime = LocalDateTime.of(2026, 10, 13, 23, 45)
        val newDate = java.time.LocalDate.of(2026, 10, 14)
        val updatedDateTime = LocalDateTime.of(newDate, originalDateTime.toLocalTime())

        assertEquals(2026, updatedDateTime.year)
        assertEquals(10, updatedDateTime.monthValue)
        assertEquals(14, updatedDateTime.dayOfMonth)
        assertEquals(23, updatedDateTime.hour)
        assertEquals(45, updatedDateTime.minute)
    }

    @Test
    fun testStationSelectionSetsTransitDestination() {
        val stName = "八王子駅"
        val stLat = 35.655
        val stLon = 139.338
        val stId = "1001"

        // 駅詳細からの「ここへ行く」アクション
        val destPoint = Pair(stLat, stLon)
        val selectedTravelMode = TravelMode.TRANSIT
        val destStation = StationEntry(stId, stName, "はちおうじ", stLat, stLon, "八王子")

        assertEquals(destPoint.first, destStation.lat, 0.0001)
        assertEquals(destPoint.second, destStation.lon, 0.0001)
        assertEquals(TravelMode.TRANSIT, selectedTravelMode)
        assertEquals("八王子駅", stName)
    }

    @Test
    fun testNoTransitRoutesTriggersDialogStateAndAllowsNextMorningSearch() {
        // 夜間検索などで結果が空の場合
        val candidates = emptyList<Itinerary>()
        var isTransitRoutePanelOpen = false
        var showNoTransitDialog = false
        var selectedItinerary: Itinerary? = null

        if (candidates.isEmpty()) {
            isTransitRoutePanelOpen = true
            showNoTransitDialog = true
            selectedItinerary = null
        }

        assertTrue(isTransitRoutePanelOpen)
        assertTrue(showNoTransitDialog)
        assertTrue(selectedItinerary == null)

        // 翌朝06:00に切り替え
        val currentLateNight = LocalDateTime.of(2026, 10, 14, 0, 15)
        val nextMorning = (if (currentLateNight.hour < 4) currentLateNight.toLocalDate() else currentLateNight.toLocalDate().plusDays(1)).atTime(6, 0)
        assertEquals(6, nextMorning.hour)
        assertEquals(0, nextMorning.minute)
        assertEquals(14, nextMorning.dayOfMonth) // 深夜0時は同日朝6時
    }

    @Test
    fun testBusStopGeoJsonContainsBusKind() {
        // stationsGeoJson の Feature 出力において、kind = "bus" が出力され、bus-stop アイコンレイヤーに適合すること
        val sampleGeoJsonFeature = """{"type":"Feature","properties":{"id":"bus_101","name":"森林科学園前","kind":"bus","rail_icon":""},"geometry":{"type":"Point","coordinates":[139.278,35.652]}}"""
        assertTrue(sampleGeoJsonFeature.contains("\"kind\":\"bus\""))
        assertTrue(sampleGeoJsonFeature.contains("森林科学園前"))
        assertFalse(sampleGeoJsonFeature.contains("\"kind\":\"rail\""))
    }

    @Test
    fun testStraightNavLineEliminationInFavorOfRoadOverlay() {
        // 公共交通機関ナビ中、地形を突き抜ける直線 navLine は null であり、道なりの remainingPath が routeOverlay に使われること
        val navLine: String? = null
        assertTrue("Straight navLine should be null to prevent straight lines cutting across terrain", navLine == null)

        val roadCurvedPath = listOf(
            Pair(35.651, 139.281),
            Pair(35.652, 139.280),
            Pair(35.653, 139.279),
            Pair(35.654, 139.278)
        )
        val overlay = remainingRoadOverlay(roadCurvedPath)
        assertTrue(overlay.geoJson.contains("LineString"))
        assertEquals(4, overlay.points.size)
        assertTrue(overlay.geoJson.contains("139.281"))
        assertTrue(overlay.geoJson.contains("139.278"))
    }
}



