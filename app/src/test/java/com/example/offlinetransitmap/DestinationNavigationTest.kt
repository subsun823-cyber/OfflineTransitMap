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
}

