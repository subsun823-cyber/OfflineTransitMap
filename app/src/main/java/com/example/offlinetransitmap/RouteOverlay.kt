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