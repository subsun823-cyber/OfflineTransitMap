package com.example.offlinetransitmap

import android.content.Context
import androidx.core.content.ContextCompat
import org.maplibre.android.maps.Style

// 地図の分類が明示されている地点だけに記号を出す。
// crossing は横断歩道等にも使われるため、踏切として扱わない。
// 神社は神道と明示された宗教施設だけを鳥居にする。名称からは推測しない。
internal const val POI_ICON_EXPRESSION = """["case",
    ["all", ["==", ["get", "kind"], "place_of_worship"], ["==", ["get", "kind_detail"], "shinto"]], "poi-shrine",
    ["match", ["get", "kind"],
        ["restaurant", "cafe", "fast_food", "food_court", "bar", "pub"], "poi-food",
        ["hospital", "clinic", "doctors", "dentist", "pharmacy"], "poi-medical",
        ["junction", "intersection", "traffic_signals"], "poi-junction",
        "level_crossing", "poi-level-crossing",
        ["park", "garden", "playground", "dog_park", "national_park", "nature_reserve", "forest", "picnic_site"], "poi-park",
        ["convenience", "supermarket", "grocery", "department_store", "mall", "bakery", "clothes", "books", "florist", "gift"], "poi-shop",
        "shrine", "poi-shrine",
        ["place_of_worship", "temple", "church", "mosque", "townhall", "community_centre", "post_office", "police", "fire_station", "bank", "library", "museum"], "poi-building",
        ["school", "college", "university", "kindergarten"], "poi-school",
        ["parking", "bicycle_parking", "motorcycle_parking"], "poi-parking",
        "toilets", "poi-toilets",
        ["hotel", "motel", "hostel", "guest_house", "bed_and_breakfast"], "poi-lodging",
        ""]]"""

// 同梱の施設・駅・バス停の画像を登録するので、通信は不要。
internal fun Style.Builder.withPoiIcons(context: Context): Style.Builder = apply {
    for ((id, drawable) in listOf(
        "bus-stop" to R.drawable.map_bus_stop,
        "station-jr-east" to R.drawable.map_station_jr_east,
        "station-tokyo-metro" to R.drawable.map_station_tokyo_metro,
        "station-rail" to R.drawable.map_station_rail,
        "station-rail-both" to R.drawable.map_station_rail_both,
        "poi-food" to R.drawable.map_poi_food,
        "poi-medical" to R.drawable.map_poi_medical,
        "poi-junction" to R.drawable.map_poi_junction,
        "poi-level-crossing" to R.drawable.map_poi_level_crossing,
        "poi-park" to R.drawable.map_poi_park,
        "poi-shop" to R.drawable.map_poi_shop,
        "poi-shrine" to R.drawable.map_poi_shrine,
        "poi-building" to R.drawable.map_poi_building,
        "poi-school" to R.drawable.map_poi_school,
        "poi-parking" to R.drawable.map_poi_parking,
        "poi-toilets" to R.drawable.map_poi_toilets,
        "poi-lodging" to R.drawable.map_poi_lodging
    )) {
        withImage(id, requireNotNull(ContextCompat.getDrawable(context, drawable)))
    }
}
