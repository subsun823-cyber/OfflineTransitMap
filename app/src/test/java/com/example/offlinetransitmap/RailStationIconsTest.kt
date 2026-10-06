package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Test

class RailStationIconsTest {
    @Test fun jrEastUsesLogoIncludingFullNameAndWidthVariants() {
        for (operator in listOf("JR東日本", "ＪＲ東日本", "東日本旅客鉄道株式会社", "JR East", "East Japan Railway Company")) {
            assertEquals(operator, "station-jr-east", railStationIcon(setOf(operator)))
        }
    }

    @Test fun privateRailwaysUseTrainSymbol() {
        assertEquals("station-rail", railStationIcon(setOf("京王電鉄", "小田急電鉄")))
    }

    @Test fun sharedStationUsesJrAndTrainSymbols() {
        assertEquals("station-rail-both", railStationIcon(setOf("京王電鉄", "JR東日本")))
    }

    @Test fun duplicateJrAliasesDoNotAddASecondSymbol() {
        assertEquals("station-jr-east", railStationIcon(setOf("JR東日本", "東日本旅客鉄道株式会社", " ")))
    }

    @Test fun missingOperatorUsesGenericSymbolWithoutInventingACompany() {
        assertEquals("station-rail", railStationIcon(emptySet()))
        assertEquals("station-rail", railStationIcon(setOf("", "　")))
    }

    @Test fun otherJrCompaniesAndSimilarNamesDoNotUseEastLogo() {
        for (operator in listOf("JR東海", "JR西日本", "JR東日本バス", "東日本交通")) {
            assertEquals(operator, "station-rail", railStationIcon(setOf(operator)))
        }
    }
}
