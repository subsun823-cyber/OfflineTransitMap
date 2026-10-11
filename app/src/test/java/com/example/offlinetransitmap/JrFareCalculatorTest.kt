package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JrFareCalculatorTest {

    @Test
    fun testIsJrRoute() {
        assertTrue(JrFareCalculator.isJrRoute("JR_11", "JR東日本"))
        assertTrue(JrFareCalculator.isJrRoute("JR_42", "JR東日本"))
        assertTrue(JrFareCalculator.isJrRoute("jr_sub", "JR"))
        assertTrue(JrFareCalculator.isJrRoute("R_123", "東日本旅客鉄道"))
        assertFalse(JrFareCalculator.isJrRoute("TB_10", "東武鉄道"))
        assertFalse(JrFareCalculator.isJrRoute("SB_01", "西武鉄道"))
        assertFalse(JrFareCalculator.isJrRoute("BUS_01", "西東京バス"))
    }

    @Test
    fun testCalculateFareDenchaTokutei() {
        // 1〜3km (初乗り)
        assertEquals(150, JrFareCalculator.calculateFare(1))
        assertEquals(150, JrFareCalculator.calculateFare(3))

        // 4〜6km
        assertEquals(170, JrFareCalculator.calculateFare(4))
        assertEquals(170, JrFareCalculator.calculateFare(6))

        // 7〜10km
        assertEquals(180, JrFareCalculator.calculateFare(7))
        assertEquals(180, JrFareCalculator.calculateFare(10))

        // 11〜15km
        assertEquals(230, JrFareCalculator.calculateFare(11))
        assertEquals(230, JrFareCalculator.calculateFare(15))

        // 16〜20km
        assertEquals(320, JrFareCalculator.calculateFare(16))
        assertEquals(320, JrFareCalculator.calculateFare(20))

        // 21〜25km
        assertEquals(410, JrFareCalculator.calculateFare(21))
        assertEquals(410, JrFareCalculator.calculateFare(25))

        // 26〜30km (例: 新宿〜立川 27km)
        assertEquals(490, JrFareCalculator.calculateFare(26))
        assertEquals(490, JrFareCalculator.calculateFare(30))

        // 31〜35km (例: 新宿〜日野 33km)
        assertEquals(580, JrFareCalculator.calculateFare(31))
        assertEquals(580, JrFareCalculator.calculateFare(35))

        // 41〜45km (例: 新宿〜八王子 41km)
        assertEquals(740, JrFareCalculator.calculateFare(41))
        assertEquals(740, JrFareCalculator.calculateFare(45))

        // 46〜50km (例: 新宿〜高尾 47km)
        assertEquals(830, JrFareCalculator.calculateFare(47))

        // 51〜60km
        assertEquals(950, JrFareCalculator.calculateFare(55))

        // 61〜70km
        assertEquals(1110, JrFareCalculator.calculateFare(65))
    }

    @Test
    fun testCalculateFareYamanoteZone() {
        // 山手線内特定運賃
        assertEquals(150, JrFareCalculator.calculateFare(2, isYamanoteOnly = true))
        assertEquals(170, JrFareCalculator.calculateFare(5, isYamanoteOnly = true))
        assertEquals(180, JrFareCalculator.calculateFare(9, isYamanoteOnly = true))
        // 11〜15km: 電車特定区間 230円に対し山手線内 210円
        assertEquals(210, JrFareCalculator.calculateFare(12, isYamanoteOnly = true))
        // 16〜20km: 電車特定区間 320円に対し山手線内 280円
        assertEquals(280, JrFareCalculator.calculateFare(18, isYamanoteOnly = true))
    }

    @Test
    fun testCalculateThroughFare() {
        // 中央線 立川〜新宿 (27km: 490円) + 山手線 新宿〜渋谷 (4km: 170円)
        // 単独合算だと 660円、通算営業キロ 31km だと 580円
        val throughFare = JrFareCalculator.calculateThroughFare(listOf(27, 4))
        assertEquals(580, throughFare)
    }

    @Test
    fun testEstimateOperatingKm() {
        // 新宿駅 (35.6909, 139.7003) -> 渋谷駅 (35.6580, 139.7016)
        // 直線距離 約 3.6km -> 補正 & 切り上げで 4km
        val shinjuku = StationEntry("s1", "新宿", "しんじゅく", 35.6909, 139.7003, group = "shinjuku")
        val shibuya = StationEntry("s2", "渋谷", "しぶや", 35.6580, 139.7016, group = "shibuya")
        val km = JrFareCalculator.estimateOperatingKm(listOf(shinjuku, shibuya))
        assertEquals(4, km)

        val fare = JrFareCalculator.calculateLegFare(listOf(shinjuku, shibuya), "JR_42", "JR東日本")
        assertEquals(170, fare)
    }
}
