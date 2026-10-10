package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Test

class RailStationIconsTest {
    @Test fun jrEastUsesLogoIncludingFullNameAndWidthVariants() {
        for (operator in listOf("JR東日本", "ＪＲ東日本", "東日本旅客鉄道株式会社", "JR East", "East Japan Railway Company")) {
            assertEquals(operator, "station-jr-east", railStationIcon(setOf(operator)))
        }
    }

    @Test fun seibuUsesSeibuSymbolIncludingAliases() {
        for (operator in listOf("西武鉄道", "西武", "Seibu", "seibu railway", "Seibu Railway Co., Ltd.")) {
            assertEquals(operator, "station-seibu", railStationIcon(setOf(operator)))
        }
    }

    @Test fun privateRailwaysUseTrainSymbol() {
        assertEquals("station-rail", railStationIcon(setOf("京王電鉄", "小田急電鉄")))
    }

    @Test fun sharedStationUsesJrAndTrainSymbols() {
        assertEquals("station-rail-both", railStationIcon(setOf("京王電鉄", "JR東日本")))
    }

    @Test fun sharedStationWithJrAndSeibuUsesJrSeibuSymbols() {
        assertEquals("station-rail-jr-seibu", railStationIcon(setOf("西武鉄道", "JR東日本")))
        assertEquals("station-rail-jr-seibu", railStationIcon(setOf("西武", "東日本旅客鉄道")))
    }

    @Test fun sharedStationWithSeibuAndMetroUsesSeibuMetroSymbols() {
        assertEquals("station-rail-seibu-metro", railStationIcon(setOf("西武鉄道", "東京メトロ")))
    }

    @Test fun sharedStationWithSeibuAndToeiUsesSeibuToeiSymbols() {
        assertEquals("station-rail-seibu-toei", railStationIcon(setOf("西武鉄道", "東京都交通局")))
    }

    @Test fun sharedStationWithJrSeibuAndMetroUsesThreeSymbols() {
        assertEquals("station-rail-jr-seibu-metro", railStationIcon(setOf("JR東日本", "西武鉄道", "東京メトロ")))
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

    @Test fun tokyoMetroUsesMetroSymbolIncludingAliasesAndVariants() {
        for (operator in listOf("東京メトロ", "東京地下鉄", "東京地下鉄株式会社", "Tokyo Metro", "Tokyo Metro Co., Ltd.", "tokyo metro")) {
            assertEquals(operator, "station-tokyo-metro", railStationIcon(setOf(operator)))
        }
    }

    @Test fun toeiUsesToeiSymbolIncludingAliases() {
        for (operator in listOf("東京都交通局", "都営地下鉄", "都営", "Toei", "toei subway")) {
            assertEquals(operator, "station-toei", railStationIcon(setOf(operator)))
        }
    }

    @Test fun sharedStationWithJrAndTokyoMetroUsesJrMetroSymbols() {
        assertEquals("station-rail-jr-metro", railStationIcon(setOf("東京メトロ", "JR東日本")))
    }

    @Test fun sharedStationWithPrivateAndMetroUsesPrivateMetroSymbols() {
        assertEquals("station-rail-private-metro", railStationIcon(setOf("東京メトロ", "小田急電鉄")))
    }

    @Test fun sharedStationWithJrPrivateAndMetroUsesThreeSymbols() {
        assertEquals("station-rail-jr-private-metro", railStationIcon(setOf("JR東日本", "京王電鉄", "東京メトロ")))
    }

    @Test fun sharedStationWithAllFourOperatorsUsesFourSymbols() {
        assertEquals("station-rail-jr-private-metro-toei", railStationIcon(setOf("JR東日本", "小田急電鉄", "東京メトロ", "都営地下鉄")))
        assertEquals("station-rail-jr-seibu-metro-toei", railStationIcon(setOf("JR東日本", "西武鉄道", "東京メトロ", "都営地下鉄")))
    }
}
