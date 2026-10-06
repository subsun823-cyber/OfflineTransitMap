package com.example.offlinetransitmap

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "settings-test-${System.nanoTime()}"
    private lateinit var storage: SharedPreferences

    @Before
    fun setUp() {
        storage = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(name)
    }

    @Test
    fun readsDefaultsWithoutChangingAnyUserData() {
        assertEquals(AppPreferences(), AppSettings(storage).value)
        assertEquals(emptyMap<String, Any>(), storage.all)
    }

    @Test
    fun recreatingSettingsRestoresAllSavedOptions() {
        val settings = AppSettings(storage)
        val changed = AppPreferences(ThemeMode.DARK, showPoiNames = false, showPoiIcons = false, keepScreenOnDuringNavigation = true)
        settings.update(changed)
        assertEquals(changed, settings.value)
        assertEquals(changed, AppSettings(context.getSharedPreferences(name, Context.MODE_PRIVATE)).value)
        settings.update(AppPreferences(theme = ThemeMode.SYSTEM))
        assertEquals(AppPreferences(theme = ThemeMode.SYSTEM), AppSettings(storage).value)
    }

    @Test
    fun partialOrUnknownSettingsRetainUnspecifiedDefaults() {
        storage.edit().putString("theme", "future-mode").putBoolean("poi_icons", false).commit()
        assertEquals(AppPreferences(showPoiIcons = false), AppSettings(storage).value)
    }
}
