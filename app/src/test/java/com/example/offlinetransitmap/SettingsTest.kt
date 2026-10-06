package com.example.offlinetransitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun defaultsPreserveExistingAppearanceAndScreenTimeout() {
        val preferences = AppPreferences()
        assertEquals(ThemeMode.LIGHT, preferences.theme)
        assertTrue(preferences.showPoiNames)
        assertTrue(preferences.showPoiIcons)
        assertFalse(preferences.keepScreenOnDuringNavigation)
    }

    @Test
    fun themeSupportsSystemAndExplicitOverrides() {
        assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertFalse(ThemeMode.SYSTEM.isDark(false))
        for (systemDark in listOf(true, false)) {
            assertTrue(ThemeMode.DARK.isDark(systemDark))
            assertFalse(ThemeMode.LIGHT.isDark(systemDark))
        }
    }

    @Test
    fun unknownOrMissingStoredThemeFallsBackWithoutCrashing() {
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.fromStored(mode.name))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored("future-mode"))
    }

    @Test
    fun facilityNamesAndIconsCanBeEnabledIndependently() {
        for (names in listOf(false, true)) for (icons in listOf(false, true)) {
            val presentation = PoiPresentation(names, icons)
            assertEquals(names || icons, presentation.symbolsVisible)
            assertEquals(if (names) 11f else 0f, presentation.textSize)
            assertEquals(if (icons) 1f else 0f, presentation.iconSize)
        }
    }

    @Test
    fun lightModeRestoresTheOriginalPaintValue() {
        for (light in listOf("#f1efe9", "rgba(10, 20, 30, 1)")) {
            assertEquals(light, mapPaintColor(false, "background", "background-color", light))
            assertEquals(light, mapPaintColor(false, "pois-name", "text-color", light))
        }
    }

    @Test
    fun darkModeChangesBaseMapAndLabelHaloButKeepsRouteAndStationColors() {
        assertNotEquals("#f1efe9", mapPaintColor(true, "background", "background-color", "#f1efe9"))
        assertNotEquals("#ffffff", mapPaintColor(true, "pois-symbols", "text-halo-color", "#ffffff"))
        assertEquals("#90caf9", mapPaintColor(true, "pois-symbols", "text-color", "#1a5fb4"))
        for (id in listOf("route-bus", "route-walk", "route-casing", "nav-line", "stations", "stations-rail")) {
            assertEquals("#123456", mapPaintColor(true, id, "line-color", "#123456"))
        }
    }
}
