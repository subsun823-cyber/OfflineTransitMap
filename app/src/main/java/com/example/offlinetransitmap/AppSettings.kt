package com.example.offlinetransitmap

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val label: String) {
    SYSTEM("端末に合わせる"), LIGHT("ライト"), DARK("ダーク");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: LIGHT
    }
}

data class AppPreferences(
    val theme: ThemeMode = ThemeMode.LIGHT,
    val showPoiNames: Boolean = true,
    val showPoiIcons: Boolean = true,
    val keepScreenOnDuringNavigation: Boolean = false
)

// 小さな設定だけを保存する。地図・時刻表データには触れない。
class AppSettings(private val storage: SharedPreferences) {
    var value by mutableStateOf(
        AppPreferences(
            theme = ThemeMode.fromStored(storage.getString("theme", null)),
            showPoiNames = storage.getBoolean("poi_names", true),
            showPoiIcons = storage.getBoolean("poi_icons", true),
            keepScreenOnDuringNavigation = storage.getBoolean("navigation_screen_on", false)
        )
    )
        private set

    fun update(preferences: AppPreferences) {
        storage.edit()
            .putString("theme", preferences.theme.name)
            .putBoolean("poi_names", preferences.showPoiNames)
            .putBoolean("poi_icons", preferences.showPoiIcons)
            .putBoolean("navigation_screen_on", preferences.keepScreenOnDuringNavigation)
            .apply()
        value = preferences
    }
}
