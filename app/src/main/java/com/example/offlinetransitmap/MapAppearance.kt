package com.example.offlinetransitmap

import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

// 経路や駅の色は識別に使うため、背景地図の配色だけを変える。
internal fun mapPaintColor(dark: Boolean, id: String, property: String, light: String): String {
    if (!dark || id.startsWith("route-") || id == "nav-line" || id == "stations" || id == "stations-rail") return light
    return when (property) {
        "background-color" -> "#171d25"
        "text-halo-color" -> "#171d25"
        "text-color" -> when {
            id.startsWith("pois") -> "#90caf9"
            id == "water-name" -> "#80b8d6"
            id == "station-name" -> "#ff9c97"
            id == "station-name-rail" -> "#80cbc4"
            else -> "#d6dee8"
        }
        "fill-color" -> when (id) {
            "water" -> "#183e57"
            "landuse-green", "natural-green" -> "#263c30"
            "landuse-hospital" -> "#43323c"
            "landuse-school" -> "#3b3840"
            "buildings" -> "#343e4a"
            else -> "#29333c"
        }
        "fill-outline-color" -> "#47515f"
        "line-color" -> when {
            id == "water-line" -> "#285974"
            id == "boundaries" -> "#9281aa"
            id.endsWith("casing") -> "#141a22"
            id == "roads-highway" -> "#b9914e"
            id == "roads-major" -> "#9c895c"
            id == "roads-path" -> "#9ba7b6"
            id == "transit-rail-dash" -> "#a4aebb"
            else -> "#566372"
        }
        else -> light
    }
}

internal data class PoiPresentation(val names: Boolean, val icons: Boolean) {
    val symbolsVisible: Boolean get() = names || icons
    val textSize: Float get() = if (names) 11f else 0f
    val iconSize: Float get() = if (icons) 1f else 0f
}

// スタイル読込時のライト色を保持し、切替時に正確に戻す。地図・カメラを再生成しない。
internal class MapAppearance(private val style: Style) {
    private data class PaintColor(val layer: String, val property: String, val light: String)

    private val colors: List<PaintColor> = buildList {
        if (style.getSource("protomaps") != null) {
            val layers = JSONObject(style.json).getJSONArray("layers")
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                val id = layer.getString("id")
                if (layer.optString("source") != "protomaps" && id != "background" && !id.startsWith("station-name")) continue
                val paint = layer.optJSONObject("paint") ?: continue
                for (property in listOf("background-color", "fill-color", "fill-outline-color", "line-color", "text-color", "text-halo-color")) {
                    val value = paint.opt(property)
                    if (value is String) add(PaintColor(id, property, value))
                }
            }
        }
    }

    fun apply(dark: Boolean, preferences: AppPreferences) {
        for ((id, property, light) in colors) {
            val color = mapPaintColor(dark, id, property, light)
            val value = when (property) {
                "background-color" -> PropertyFactory.backgroundColor(color)
                "fill-color" -> PropertyFactory.fillColor(color)
                "fill-outline-color" -> PropertyFactory.fillOutlineColor(color)
                "line-color" -> PropertyFactory.lineColor(color)
                "text-color" -> PropertyFactory.textColor(color)
                else -> PropertyFactory.textHaloColor(color)
            }
            style.getLayer(id)?.setProperties(value)
        }
        val poi = PoiPresentation(preferences.showPoiNames, preferences.showPoiIcons)
        fun visibility(visible: Boolean) = PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)
        style.getLayer("pois-dot")?.setProperties(visibility(poi.icons))
        style.getLayer("pois-name")?.setProperties(visibility(poi.names))
        style.getLayer("pois-symbols")?.setProperties(
            visibility(poi.symbolsVisible),
            PropertyFactory.iconSize(poi.iconSize),
            PropertyFactory.iconOpacity(if (poi.icons) 1f else 0f),
            PropertyFactory.iconOptional(!poi.icons),
            PropertyFactory.textSize(poi.textSize),
            PropertyFactory.textOpacity(if (poi.names) 1f else 0f)
        )
    }
}
