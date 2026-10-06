package com.example.offlinetransitmap

import android.content.Context
import androidx.core.content.ContextCompat
import org.maplibre.android.maps.Style

// 地図の分類が明示されている地点だけに記号を出す。
// crossing は横断歩道等にも使われるため、踏切として扱わない。
internal const val POI_ICON_EXPRESSION = """["match", ["get", "kind"],
    ["restaurant", "cafe", "fast_food", "food_court", "bar", "pub"], "poi-food",
    ["hospital", "clinic", "doctors"], "poi-medical",
    ["junction", "intersection"], "poi-junction",
    "level_crossing", "poi-level-crossing",
    ""]"""

// 同梱のベクター画像を登録するので、アイコン取得のための通信は不要。
internal fun Style.Builder.withPoiIcons(context: Context): Style.Builder = apply {
    for ((id, drawable) in listOf(
        "poi-food" to R.drawable.map_poi_food,
        "poi-medical" to R.drawable.map_poi_medical,
        "poi-junction" to R.drawable.map_poi_junction,
        "poi-level-crossing" to R.drawable.map_poi_level_crossing
    )) {
        withImage(id, requireNotNull(ContextCompat.getDrawable(context, drawable)))
    }
}
