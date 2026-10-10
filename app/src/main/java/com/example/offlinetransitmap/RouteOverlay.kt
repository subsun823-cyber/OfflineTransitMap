package com.example.offlinetransitmap

import org.json.JSONArray
import org.json.JSONObject

// 地図に出す経路の線(GeoJSON)と、画面に収めるための点
data class RouteOverlay(
    val geoJson: String,
    val points: List<Pair<Double, Double>>
)

// 経路が選ばれていないときの空のデータ
const val EMPTY_ROUTE_JSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"

// 経路の各区間を、停留所を結ぶ線にする
fun Itinerary.toOverlay(): RouteOverlay {
    val features = JSONArray()
    val points = ArrayList<Pair<Double, Double>>()
    for (leg in legs) {
        if (leg.path.size < 2) continue
        val coords = JSONArray()
        for ((lat, lon) in leg.path) {
            coords.put(JSONArray().put(lon).put(lat))
            points.add(Pair(lat, lon))
        }
        val props = JSONObject()
            .put("walk", leg.isWalk)
            .put("color", "#%06X".format(leg.lineColor and 0xFFFFFFL))
        val geometry = JSONObject()
            .put("type", "LineString")
            .put("coordinates", coords)
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("properties", props)
                .put("geometry", geometry)
        )
    }
    val collection = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", features)
    return RouteOverlay(collection.toString(), points)
}

// ナビ走行中、現在地から目的地までの前方の道路ルート線(GeoJSON)
fun remainingRoadOverlay(path: List<Pair<Double, Double>>): RouteOverlay {
    if (path.size < 2) return RouteOverlay(EMPTY_ROUTE_JSON, emptyList())
    val coords = path.joinToString(",") { (lat, lon) -> "[${lon},${lat}]" }
    val json = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"walk\":true,\"color\":\"#1a73e8\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$coords]}}]}"
    return RouteOverlay(json, path)
}

// 地図に目的地マーク(ピン)を出すGeoJSON
fun destinationGeoJson(point: Pair<Double, Double>?, name: String = "目的地"): String {
    if (point == null) return EMPTY_ROUTE_JSON
    val cleanName = name.replace("\"", "")
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"name\":\"$cleanName\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[${point.second},${point.first}]}}]}"
}