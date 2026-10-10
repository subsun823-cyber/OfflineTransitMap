package com.example.offlinetransitmap

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import org.json.JSONObject

// 起動時に現在地へ寄る倍率(大きいほど拡大)
private const val START_ZOOM = 13.0
private const val BUS_MIN_ZOOM = 13.0
private const val RAIL_MIN_ZOOM = 11.0

private val PROTOMAPS_BACKGROUND_LAYERS = listOf(
    """{"id": "landuse-green", "type": "fill", "source": "protomaps", "source-layer": "landuse", "filter": ["all", ["==", "${'$'}type", "Polygon"], ["any", ["in", "kind", "park", "garden", "nature_reserve", "forest", "golf_course", "recreation_ground", "cemetery", "grass", "wood", "playground", "pitch", "village_green", "meadow"], ["in", "pmap:kind", "park", "garden", "nature_reserve", "forest", "golf_course", "recreation_ground", "cemetery", "grass", "wood", "playground", "pitch", "village_green", "meadow"]]], "paint": {"fill-color": "#cfe8c3"}}""",
    """{"id": "landuse-hospital", "type": "fill", "source": "protomaps", "source-layer": "landuse", "filter": ["all", ["==", "${'$'}type", "Polygon"], ["any", ["in", "kind", "hospital"], ["in", "pmap:kind", "hospital"]]], "paint": {"fill-color": "#f1dcdc"}}""",
    """{"id": "landuse-school", "type": "fill", "source": "protomaps", "source-layer": "landuse", "filter": ["all", ["==", "${'$'}type", "Polygon"], ["any", ["in", "kind", "school", "university", "college"], ["in", "pmap:kind", "school", "university", "college"]]], "paint": {"fill-color": "#ebe5d6"}}""",
    """{"id": "natural-green", "type": "fill", "source": "protomaps", "source-layer": "natural", "filter": ["all", ["==", "${'$'}type", "Polygon"], ["any", ["in", "kind", "wood", "forest", "grassland", "scrub", "heath", "wetland", "park"], ["in", "pmap:kind", "wood", "forest", "grassland", "scrub", "heath", "wetland", "park"]]], "paint": {"fill-color": "#c4e0b7"}}""",
    """{"id": "water", "type": "fill", "source": "protomaps", "source-layer": "water", "filter": ["==", "${'$'}type", "Polygon"], "paint": {"fill-color": "#a6d4f7"}}""",
    """{"id": "water-line", "type": "line", "source": "protomaps", "source-layer": "water", "filter": ["==", "${'$'}type", "LineString"], "paint": {"line-color": "#a6d4f7", "line-width": ["interpolate", ["linear"], ["zoom"], 9, 0.6, 14, 2, 18, 6]}}""",
    """{"id": "buildings", "type": "fill", "source": "protomaps", "source-layer": "buildings", "minzoom": 13, "paint": {"fill-color": "#e1dcd2", "fill-outline-color": "#cfc8bb"}}""",
    """{"id": "roads-base", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 9, "filter": ["==", "${'$'}type", "LineString"], "paint": {"line-color": "#cfcabd", "line-width": ["interpolate", ["linear"], ["zoom"], 9, 0.3, 14, 1, 18, 3]}}""",
    """{"id": "roads-minor-casing", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 13, "filter": ["all", ["==", "${'$'}type", "LineString"], ["any", ["in", "kind", "minor_road", "other"], ["in", "pmap:kind", "minor_road", "other"]]], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#d9d4c7", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 1.5, 16, 5, 19, 14]}}""",
    """{"id": "roads-medium-casing", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 11, "filter": ["all", ["==", "${'$'}type", "LineString"], ["any", ["in", "kind", "medium_road"], ["in", "pmap:kind", "medium_road"]]], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#d9d4c7", "line-width": ["interpolate", ["linear"], ["zoom"], 11, 1, 14, 4, 17, 9, 19, 18]}}""",
    """{"id": "roads-major-casing", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 9, "filter": ["all", ["==", "${'$'}type", "LineString"], ["any", ["in", "kind", "major_road"], ["in", "pmap:kind", "major_road"]]], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#e2c26a", "line-width": ["interpolate", ["linear"], ["zoom"], 9, 1, 12, 3, 15, 8, 19, 20]}}""",
    """{"id": "roads-highway-casing", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 7, "filter": ["all", ["==", "${'$'}type", "LineString"], ["any", ["in", "kind", "highway"], ["in", "pmap:kind", "highway"]]], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#e0a43a", "line-width": ["interpolate", ["linear"], ["zoom"], 7, 1, 11, 3.5, 15, 9, 19, 22]}}""",
    """{"id": "roads-minor", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 13, "filter": ["all", ["==", "${'$'}type", "LineString"], ["any", ["in", "kind", "minor_road", "other"], ["in", "pmap:kind", "minor_road", "other"]]], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 0.8, 16, 3.5, 19, 11]}}""",
    """{"id": "roads-path", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 15, "filter": ["all", ["==", "${'$'}type", "LineString"], ["any", ["in", "kind", "path"], ["in", "pmap:kind", "path"]]], "layout": {"line-cap": "round"}, "paint": {"line-color": "#b8b2a3", "line-width": ["interpolate", ["linear"], ["zoom"], 15, 0.8, 19, 2.5], "line-dasharray": [1.5, 1.5]}}""",
    """{"id": "roads-medium", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 11, "filter": ["all", ["==", "${'$'}type", "LineString"], ["any", ["in", "kind", "medium_road"], ["in", "pmap:kind", "medium_road"]]], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 11, 0.6, 14, 2.8, 17, 7, 19, 15]}}""",
    """{"id": "roads-major", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 9, "filter": ["all", ["==", "${'$'}type", "LineString"], ["any", ["in", "kind", "major_road"], ["in", "pmap:kind", "major_road"]]], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#fff1b8", "line-width": ["interpolate", ["linear"], ["zoom"], 9, 0.6, 12, 2.2, 15, 6, 19, 17]}}""",
    """{"id": "roads-highway", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 7, "filter": ["all", ["==", "${'$'}type", "LineString"], ["any", ["in", "kind", "highway"], ["in", "pmap:kind", "highway"]]], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#ffcf6b", "line-width": ["interpolate", ["linear"], ["zoom"], 7, 0.6, 11, 2.5, 15, 7, 19, 19]}}""",
    """{"id": "transit-rail-casing", "type": "line", "source": "protomaps", "source-layer": "transit", "minzoom": 9, "filter": ["==", "${'$'}type", "LineString"], "paint": {"line-color": "#8e8e8e", "line-width": ["interpolate", ["linear"], ["zoom"], 9, 0.8, 14, 2, 18, 4]}}""",
    """{"id": "transit-rail-dash", "type": "line", "source": "protomaps", "source-layer": "transit", "minzoom": 13, "filter": ["==", "${'$'}type", "LineString"], "paint": {"line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 0.8, 18, 2], "line-dasharray": [3, 3]}}""",
    """{"id": "boundaries", "type": "line", "source": "protomaps", "source-layer": "boundaries", "minzoom": 8, "paint": {"line-color": "#b7a8c8", "line-width": 1, "line-dasharray": [3, 2]}}"""
)

private val COMMON_ROUTE_LAYERS = listOf(
    """{"id": "route-casing", "type": "line", "source": "route", "filter": ["==", ["get", "walk"], false], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#ffffff", "line-width": 9}}""",
    """{"id": "route-bus", "type": "line", "source": "route", "filter": ["==", ["get", "walk"], false], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": ["get", "color"], "line-width": 5.5}}""",
    """{"id": "route-walk", "type": "line", "source": "route", "filter": ["==", ["get", "walk"], true], "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#1a73e8", "line-width": 6, "line-dasharray": [0.01, 2]}}""",
    """{"id": "nav-line", "type": "line", "source": "nav-line", "layout": {"line-cap": "round", "line-join": "round"}, "paint": {"line-color": "#1a73e8", "line-width": 6, "line-dasharray": [0.01, 2]}}""",
    """{"id": "destination-circle-pulse", "type": "circle", "source": "destination", "paint": {"circle-radius": 14, "circle-color": "#ea4335", "circle-opacity": 0.25}}""",
    """{"id": "destination-circle", "type": "circle", "source": "destination", "paint": {"circle-radius": 7, "circle-color": "#ea4335", "circle-stroke-color": "#ffffff", "circle-stroke-width": 2.5}}""",
    """{"id": "destination-name", "type": "symbol", "source": "destination", "layout": {"text-field": ["get", "name"], "text-font": ["NotoSansRegular"], "text-size": 12, "text-max-width": 8, "text-anchor": "top", "text-offset": [0, 0.9]}, "paint": {"text-color": "#c5221f", "text-halo-color": "#ffffff", "text-halo-width": 2.0}}"""
)

private val PROTOMAPS_LABEL_LAYERS = listOf(
    """{"id": "water-name", "type": "symbol", "source": "protomaps", "source-layer": "water", "minzoom": 10, "filter": ["all", ["==", "${'$'}type", "Point"], ["has", "name"]], "layout": {"text-field": ["get", "name"], "text-font": ["NotoSansRegular"], "text-size": 12, "text-max-width": 8}, "paint": {"text-color": "#4a7fb0", "text-halo-color": "#ffffff", "text-halo-width": 1.2}}""",
    """{"id": "roads-name", "type": "symbol", "source": "protomaps", "source-layer": "roads", "minzoom": 14, "filter": ["all", ["==", "${'$'}type", "LineString"], ["has", "name"]], "layout": {"symbol-placement": "line", "text-field": ["get", "name"], "text-font": ["NotoSansRegular"], "text-size": ["interpolate", ["linear"], ["zoom"], 14, 10, 18, 13], "text-letter-spacing": 0.05, "symbol-spacing": 280}, "paint": {"text-color": "#5f6368", "text-halo-color": "#ffffff", "text-halo-width": 1.5}}""",
    """{"id": "places", "type": "symbol", "source": "protomaps", "source-layer": "places", "minzoom": 8, "filter": ["all", ["==", "${'$'}type", "Point"], ["has", "name"]], "layout": {"text-field": ["get", "name"], "text-font": ["NotoSansRegular"], "text-size": ["interpolate", ["linear"], ["zoom"], 8, 12, 12, 14, 16, 16], "text-max-width": 8, "text-padding": 4}, "paint": {"text-color": "#3c4043", "text-halo-color": "#ffffff", "text-halo-width": 1.6}}""",
    """{"id": "pois-dot", "type": "circle", "source": "protomaps", "source-layer": "pois", "minzoom": 15, "filter": ["all", ["==", "${'$'}type", "Point"], ["has", "name"], ["all", ["!in", "kind", "bus_stop", "bus_station"], ["!in", "pmap:kind", "bus_stop", "bus_station"]]], "paint": {"circle-radius": 3.5, "circle-color": "#1a73e8", "circle-stroke-color": "#ffffff", "circle-stroke-width": 1}}""",
    """{"id": "pois-name", "type": "symbol", "source": "protomaps", "source-layer": "pois", "minzoom": 15.5, "filter": ["all", ["==", ["geometry-type"], "Point"], ["has", "name"], ["!=", ["get", "kind"], "bus_stop"], ["!=", ["get", "kind"], "bus_station"], ["!=", ["get", "pmap:kind"], "bus_stop"], ["!=", ["get", "pmap:kind"], "bus_station"], ["==", $POI_ICON_EXPRESSION, ""]], "layout": {"text-field": ["get", "name"], "text-font": ["NotoSansRegular"], "text-size": 11, "text-max-width": 7, "text-anchor": "top", "text-offset": [0, 0.6], "text-optional": true}, "paint": {"text-color": "#1a5fb4", "text-halo-color": "#ffffff", "text-halo-width": 1.3}}""",
    """{"id": "pois-symbols", "type": "symbol", "source": "protomaps", "source-layer": "pois", "minzoom": 15.5, "filter": ["all", ["==", ["geometry-type"], "Point"], ["!=", $POI_ICON_EXPRESSION, ""]], "layout": {"icon-image": $POI_ICON_EXPRESSION, "icon-size": 1, "icon-padding": 2, "text-field": ["coalesce", ["get", "name"], ""], "text-font": ["NotoSansRegular"], "text-size": 11, "text-max-width": 7, "text-anchor": "top", "text-offset": [0, 0.6], "text-optional": true}, "paint": {"text-color": "#1a5fb4", "text-halo-color": "#ffffff", "text-halo-width": 1.3}}"""
)

private fun commonStationLayers(stationMinZoom: Double): List<String> = listOf(
    """{"id": "stations", "type": "symbol", "source": "stations", "minzoom": ${maxOf(stationMinZoom, BUS_MIN_ZOOM)}, "filter": ["!=", "kind", "rail"], "layout": {"icon-image": "bus-stop", "icon-size": 1, "icon-allow-overlap": true, "icon-ignore-placement": true}}""",
    """{"id": "stations-rail", "type": "symbol", "source": "stations", "minzoom": ${maxOf(stationMinZoom - 2.0, RAIL_MIN_ZOOM)}, "filter": ["==", "kind", "rail"], "layout": {"icon-image": ["match", ["get", "rail_icon"], "station-jr-east", "station-jr-east", "station-tokyo-metro", "station-tokyo-metro", "station-toei", "station-toei", "station-seibu", "station-seibu", "station-tobu", "station-tobu", "station-rail-both", "station-rail-both", "station-rail-jr-metro", "station-rail-jr-metro", "station-rail-jr-toei", "station-rail-jr-toei", "station-rail-private-metro", "station-rail-private-metro", "station-rail-private-toei", "station-rail-private-toei", "station-rail-metro-toei", "station-rail-metro-toei", "station-rail-jr-private-metro", "station-rail-jr-private-metro", "station-rail-jr-private-toei", "station-rail-jr-private-toei", "station-rail-jr-metro-toei", "station-rail-jr-metro-toei", "station-rail-private-metro-toei", "station-rail-private-metro-toei", "station-rail-jr-private-metro-toei", "station-rail-jr-private-metro-toei", "station-rail-jr-seibu", "station-rail-jr-seibu", "station-rail-seibu-metro", "station-rail-seibu-metro", "station-rail-seibu-toei", "station-rail-seibu-toei", "station-rail-seibu-private", "station-rail-seibu-private", "station-rail-jr-seibu-metro", "station-rail-jr-seibu-metro", "station-rail-jr-seibu-toei", "station-rail-jr-seibu-toei", "station-rail-seibu-metro-toei", "station-rail-seibu-metro-toei", "station-rail-jr-seibu-metro-toei", "station-rail-jr-seibu-metro-toei", "station-rail-jr-tobu", "station-rail-jr-tobu", "station-rail-tobu-metro", "station-rail-tobu-metro", "station-rail-tobu-toei", "station-rail-tobu-toei", "station-rail-tobu-private", "station-rail-tobu-private", "station-rail-tobu-seibu", "station-rail-tobu-seibu", "station-rail-jr-tobu-metro", "station-rail-jr-tobu-metro", "station-rail-jr-tobu-toei", "station-rail-jr-tobu-toei", "station-rail-tobu-metro-toei", "station-rail-tobu-metro-toei", "station-rail-jr-tobu-seibu", "station-rail-jr-tobu-seibu", "station-rail-jr-tobu-metro-toei", "station-rail-jr-tobu-metro-toei", "station-rail-jr-seibu-tobu-metro", "station-rail-jr-seibu-tobu-metro", "station-rail"], "icon-size": 1, "icon-allow-overlap": true, "icon-ignore-placement": true}}""",

    """{"id": "station-name", "type": "symbol", "source": "stations", "minzoom": 14.5, "filter": ["!=", "kind", "rail"], "layout": {"text-field": ["get", "name"], "text-font": ["NotoSansRegular"], "text-size": 12, "text-max-width": 7, "text-anchor": "top", "text-offset": [0, 0.9], "text-optional": true}, "paint": {"text-color": "#b71c1c", "text-halo-color": "#ffffff", "text-halo-width": 1.5}}""",
    """{"id": "station-name-rail", "type": "symbol", "source": "stations", "minzoom": 12, "filter": ["==", "kind", "rail"], "layout": {"text-field": ["get", "name"], "text-font": ["NotoSansRegular"], "text-size": 13, "text-max-width": 7, "text-anchor": "top", "text-offset": [0, 1.2], "text-optional": true}, "paint": {"text-color": "#004d40", "text-halo-color": "#ffffff", "text-halo-width": 1.8}}"""
)

private fun expandLayerForPrefecture(templateJson: String, prefId: String): String {
    val prefix = "{\"id\": \""
    val expanded = if (templateJson.startsWith(prefix)) {
        val idEnd = templateJson.indexOf('"', prefix.length)
        if (idEnd > 0) {
            val originalId = templateJson.substring(prefix.length, idEnd)
            "{\"id\": \"$originalId-$prefId" + templateJson.substring(idEnd)
        } else templateJson
    } else templateJson
    return expanded.replace("\"source\": \"protomaps\"", "\"source\": \"protomaps-$prefId\"")
}

internal fun offlineMultiStyleJson(
    downloadedPrefectures: List<Pair<PrefectureMapInfo, File>>,
    stationsGeoJson: String,
    stationMinZoom: Double
): String {
    val sourcesJson = buildString {
        append("{\n")
        for (pair in downloadedPrefectures) {
            val (pref, file) = pair
            val escapedPath = file.absolutePath.replace("\\", "/")
            append("""    "protomaps-${pref.id}": { "type": "vector", "url": "pmtiles://file://$escapedPath", "attribution": "© OpenStreetMap contributors" },""")
            append("\n")
        }
        append("""    "stations": { "type": "geojson", "data": $stationsGeoJson },""")
        append("\n")
        append("""    "route": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },""")
        append("\n")
        append("""    "nav-line": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },""")
        append("\n")
        append("""    "destination": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } }""")
        append("\n  }")
    }

    val layersJson = buildList {
        add("""    {"id": "background", "type": "background", "paint": {"background-color": "#f1efe9"}}""")
        for (tmpl in PROTOMAPS_BACKGROUND_LAYERS) {
            for ((pref, _) in downloadedPrefectures) {
                add("    " + expandLayerForPrefecture(tmpl, pref.id))
            }
        }
        for (tmpl in COMMON_ROUTE_LAYERS) {
            add("    " + tmpl)
        }
        for (tmpl in PROTOMAPS_LABEL_LAYERS) {
            for ((pref, _) in downloadedPrefectures) {
                add("    " + expandLayerForPrefecture(tmpl, pref.id))
            }
        }
        for (tmpl in commonStationLayers(stationMinZoom)) {
            add("    " + tmpl)
        }
    }.joinToString(",\n")

    return """
{
  "version": 8,
  "glyphs": "asset://{fontstack}/{range}.pbf",
  "sources": $sourcesJson,
  "layers": [
$layersJson
  ]
}
""".trimIndent()
}

internal fun offlineStyleJson(
    pmtilesPath: String,
    stationsGeoJson: String,
    stationMinZoom: Double
): String {
    if (pmtilesPath.isBlank()) {
        return stationsOnlyStyleJson(stationsGeoJson, stationMinZoom)
    }
    val file = File(pmtilesPath)
    val info = KantoPrefectures.all.firstOrNull { it.fileName == file.name }
        ?: PrefectureMapInfo(
            id = "map",
            name = "地図",
            fileName = file.name,
            approximateSizeMb = (file.length() / (1024 * 1024)).toInt(),
            west = 138.0, south = 34.0, east = 141.0, north = 38.0,
            centerLat = 35.68, centerLon = 139.69,
            downloadUrl = ""
        )
    return offlineMultiStyleJson(listOf(info to file), stationsGeoJson, stationMinZoom)
}

// 地図ファイルが未配布でも同梱駅・経路をオフラインで表示する。
internal fun stationsOnlyStyleJson(stationsGeoJson: String, stationMinZoom: Double): String {
    return offlineMultiStyleJson(emptyList(), stationsGeoJson, stationMinZoom)
}

// 現在地マーク(青い点)を有効にする。位置情報の許可を得てから呼ぶこと
@SuppressLint("MissingPermission")
private fun enableLocationMarker(context: Context, map: MapLibreMap, style: Style) {
    val lc = map.locationComponent
    if (!lc.isLocationComponentActivated) {
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style).build()
        )
    }
    lc.isLocationComponentEnabled = true
    lc.cameraMode = CameraMode.NONE
    lc.renderMode = RenderMode.NORMAL
}

@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    stationsGeoJson: String,
    preferences: AppPreferences = AppPreferences(),
    darkTheme: Boolean = false,
    stationMinZoom: Double = 0.0,
    locationGranted: Boolean = false,
    recenterRequest: Int = 0,
    routeOverlay: RouteOverlay? = null,
    followMode: Boolean = false,
    navLineJson: String? = null,
    destinationPoint: Pair<Double, Double>? = null,
    mapReloadKey: Int = 0,
    onFollowLostChange: (Boolean) -> Unit = {},
    onBearingChange: (Double) -> Unit = {},
    onStationClick: (id: String, name: String) -> Unit = { _, _ -> },
    onMapClick: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    onMapLongClick: (lat: Double, lon: Double) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var mapState by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }

    val currentOnStationClick by rememberUpdatedState(onStationClick)
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnMapLongClick by rememberUpdatedState(onMapLongClick)

    fun loadStyleForMap(map: MapLibreMap) {
        val downloaded = KantoPrefectures.getDownloadedList(context).map { it to KantoPrefectures.getFile(context, it) }
        val styleJson = offlineMultiStyleJson(downloaded, stationsGeoJson, stationMinZoom)
        map.setStyle(Style.Builder().fromJson(styleJson).withPoiIcons(context)) { style -> loadedStyle = style }
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { view ->
            view.getMapAsync { map ->
                mapState = map
                loadStyleForMap(map)
                val downloaded = KantoPrefectures.getDownloadedList(context)
                val initialTarget = downloaded.find { it.id == "tokyo" }?.let { LatLng(it.centerLat, it.centerLon) }
                    ?: downloaded.firstOrNull()?.let { LatLng(it.centerLat, it.centerLon) }
                    ?: LatLng(35.67, 139.45)
                map.cameraPosition = CameraPosition.Builder()
                    .target(initialTarget)
                    .zoom(if (downloaded.isNotEmpty()) 9.5 else 11.0)
                    .build()

                // 駅・バス停のマークまたは地図をタップしたときの処理
                map.addOnMapClickListener { latLng ->
                    val p = map.projection.toScreenLocation(latLng)
                    val area = RectF(p.x - 40f, p.y - 40f, p.x + 40f, p.y + 40f)
                    val feature = map.queryRenderedFeatures(area, "stations-rail", "stations").firstOrNull()
                    val id = feature?.getStringProperty("id") ?: ""
                    val name = feature?.getStringProperty("name")
                    if (name != null) {
                        currentOnStationClick(id, name)
                        true
                    } else {
                        currentOnMapClick(latLng.latitude, latLng.longitude)
                        false
                    }
                }

                // 地図を長押ししたときの処理(目的地設定など)
                map.addOnMapLongClickListener { latLng ->
                    currentOnMapLongClick(latLng.latitude, latLng.longitude)
                    true
                }
            }
        }
    }

    val appearance = remember(loadedStyle) { loadedStyle?.let { MapAppearance(it) } }
    LaunchedEffect(loadedStyle, stationsGeoJson) {
        loadedStyle?.getSourceAs<GeoJsonSource>("stations")?.setGeoJson(stationsGeoJson)
    }
    LaunchedEffect(loadedStyle, destinationPoint) {
        loadedStyle?.getSourceAs<GeoJsonSource>("destination")?.setGeoJson(destinationGeoJson(destinationPoint))
    }
    LaunchedEffect(appearance, darkTheme, preferences.showPoiNames, preferences.showPoiIcons) {
        appearance?.apply(darkTheme, preferences)
    }

    LaunchedEffect(mapReloadKey) {
        val map = mapState ?: return@LaunchedEffect
        if (mapReloadKey > 0) {
            loadStyleForMap(map)
        }
    }

    // 位置情報が許可され、地図のスタイルも読み込まれたら、現在地マークを出して現在地へ寄る
    LaunchedEffect(mapState, loadedStyle, locationGranted) {
        val map = mapState
        val style = loadedStyle
        if (map == null || style == null || !locationGranted) return@LaunchedEffect

        enableLocationMarker(context, map, style)

        // 最初の位置が取れるまで待つ(0.5秒 × 60回 = 最大約30秒)
        repeat(60) {
            val loc = map.locationComponent.lastKnownLocation
            if (loc != null) {
                val inMapArea = KantoPrefectures.isInAnyDownloadedArea(context, loc.latitude, loc.longitude)
                if (inMapArea) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(loc.latitude, loc.longitude),
                            START_ZOOM
                        ),
                        1000
                    )
                }
                return@LaunchedEffect
            }
            delay(500)
        }
    }

    // 「現在地に戻る」ボタンが押されたとき(ナビ中は、現在地への追従を再開する)
    LaunchedEffect(recenterRequest) {
        if (recenterRequest == 0) return@LaunchedEffect
        val map = mapState ?: return@LaunchedEffect
        if (!locationGranted || !map.locationComponent.isLocationComponentActivated) {
            return@LaunchedEffect
        }
        if (followMode) {
            map.locationComponent.cameraMode = CameraMode.TRACKING_COMPASS
            map.locationComponent.zoomWhileTracking(17.0)
            return@LaunchedEffect
        }
        val loc = map.locationComponent.lastKnownLocation ?: return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(loc.latitude, loc.longitude),
                maxOf(map.cameraPosition.zoom, START_ZOOM)
            ),
            800
        )
    }

    // ナビ中: 進む向きが上になるように、現在地に追従させる
    var wasFollowing by remember { mutableStateOf(false) }
    LaunchedEffect(followMode, mapState, loadedStyle, locationGranted) {
        val map = mapState ?: return@LaunchedEffect
        val style = loadedStyle ?: return@LaunchedEffect
        if (!locationGranted) return@LaunchedEffect
        enableLocationMarker(context, map, style)
        val lc = map.locationComponent
        if (followMode) {
            wasFollowing = true
            lc.renderMode = RenderMode.COMPASS
            lc.cameraMode = CameraMode.TRACKING_COMPASS
            lc.zoomWhileTracking(17.0)
            while (true) {
                onFollowLostChange(lc.cameraMode == CameraMode.NONE)
                onBearingChange(map.cameraPosition.bearing)
                delay(300)
            }
        } else {
            lc.renderMode = RenderMode.NORMAL
            lc.cameraMode = CameraMode.NONE
            onFollowLostChange(false)
            if (wasFollowing) {
                wasFollowing = false
                map.animateCamera(CameraUpdateFactory.bearingTo(0.0), 500)
            }
        }
    }

    // ナビ中の「現在地 → 目標の停留所」の点線
    LaunchedEffect(loadedStyle, navLineJson) {
        val style = loadedStyle ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>("nav-line") ?: return@LaunchedEffect
        source.setGeoJson(navLineJson ?: EMPTY_ROUTE_JSON)
    }

    // 経路が選ばれたとき: 線を描き、経路全体が(下のパネルに隠れずに)見えるように地図を動かす
    LaunchedEffect(loadedStyle, routeOverlay) {
        val style = loadedStyle ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>("route") ?: return@LaunchedEffect
        source.setGeoJson(routeOverlay?.geoJson ?: EMPTY_ROUTE_JSON)
        val map = mapState
        if (routeOverlay != null && map != null && routeOverlay.points.size >= 2) {
            try {
                val builder = LatLngBounds.Builder()
                for ((lat, lon) in routeOverlay.points) builder.include(LatLng(lat, lon))
                val metrics = context.resources.displayMetrics
                val pad = (32 * metrics.density).toInt()
                val bottom = (metrics.heightPixels * 0.5).toInt()
                map.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(builder.build(), pad, pad, pad, bottom),
                    900
                )
            } catch (e: Exception) {
                // 範囲を作れなかったときは、地図を動かさない
            }
        }
    }

    val lifecycle = (context as ComponentActivity).lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
