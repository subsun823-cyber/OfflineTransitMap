package com.example.offlinetransitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Wikipedia等から収録した電車の実写写真による透かし背景
 * バスは透かしを適用せず元のシンプルな表示に戻す
 */
object TrainPhotoResolver {

    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()

    // 八高北線（高麗川以北）の駅名
    private val HACHIKO_NORTH_STATIONS = setOf(
        "毛呂", "越生", "明覚", "小川町", "竹沢", "折原", "寄居",
        "用土", "松久", "児玉", "丹荘", "群馬藤岡", "北藤岡", "倉賀野", "高崎"
    )

    // 川越線西側（高麗川～川越間）の中間駅
    private val KAWAGOE_WEST_STATIONS = setOf(
        "西川越", "的場", "笠幡", "武蔵高萩"
    )

    // 川越線東側（大宮～川越間）の中間駅
    private val KAWAGOE_EAST_STATIONS = setOf(
        "日進", "西大宮", "指扇", "南古谷"
    )

    // 東武越生線の駅名
    private val TOBU_OGOSE_STATIONS = setOf(
        "坂戸", "一本松", "西大家", "川角", "武州長瀬", "東毛呂", "武州唐沢", "越生"
    )

    // 東武小泉線・佐野線・桐生線の主要駅名
    private val TOBU_BRANCH_STATIONS = setOf(
        "館林", "渡瀬", "田島", "佐野", "堀米", "吉水", "田沼", "多田", "葛生",
        "成島", "本中野", "篠塚", "十王", "西小泉", "竜舞", "太田",
        "三枚橋", "治良門橋", "藪塚", "阿左美", "新桐生", "相老", "赤城"
    )

    /**
     * 駅名、路線情報から代表電車のフォトキーを解決
     * バス停・バス路線の場合は null を返し、透かし背景を適用しない（元の表示に戻す）
     */
    fun resolvePhotoKey(
        stationName: String,
        railLines: List<StationLine>,
        selectedLine: String?,
        departures: List<Departure>
    ): String? {
        val targetLine = selectedLine
            ?: railLines.firstOrNull()?.name
            ?: departures.firstOrNull()?.lineName
            ?: ""

        // バス停または路線バスの場合は透かしなし（null）
        val isBus = targetLine.contains("系統") ||
                targetLine.contains("バス") ||
                (railLines.isEmpty() && departures.isNotEmpty() && departures.all { it.isBus })
        if (isBus) {
            return null
        }

        val lineLower = targetLine.lowercase()
        val stationLower = stationName.lowercase()

        return when {
            // 横浜線: E233系6000番台
            lineLower.contains("横浜") -> "yokohama"

            // 南武線: E233系8000番台
            lineLower.contains("南武") -> "nambu"

            // 総武快速線 / 横須賀線: E235系1000番台
            lineLower.contains("総武快速") || lineLower.contains("総武線快速") ||
                    (lineLower.contains("総武") && lineLower.contains("快速")) ||
                    lineLower.contains("横須賀") -> "sobu_rapid"

            // 総武本線 / 房総各線 (内房線・外房線・成田線本線・東金線): 209系2000番台
            lineLower.contains("総武本線") || lineLower.contains("内房") ||
                    lineLower.contains("外房") || lineLower.contains("成田") ||
                    lineLower.contains("東金") -> "boso_209"

            // 総武緩行線 (中央・総武各駅停車): E231系500番台
            lineLower.contains("総武") -> "sobu"

            // 京葉線: E233系5000番台
            lineLower.contains("京葉") -> "keiyo"

            // 常磐緩行線 (各駅停車): E233系2000番台
            lineLower.contains("常磐") && (lineLower.contains("各駅") || lineLower.contains("緩行") || lineLower.contains("各停")) -> "joban_local"

            // 常磐快速線 / 成田線我孫子支線: E231系0番台 (緑快速)
            (lineLower.contains("常磐") && lineLower.contains("快速")) ||
                    (lineLower.contains("成田") && (
                            lineLower.contains("我孫子") || stationLower.contains("我孫子") ||
                            stationLower.contains("東我孫子") || stationLower.contains("湖北") ||
                            stationLower.contains("新木") || stationLower.contains("布佐") ||
                            stationLower.contains("木下") || stationLower.contains("小林") ||
                            stationLower.contains("安食") || stationLower.contains("下総松崎")
                    )) -> "joban_rapid_e231"

            // 常磐線 (中距離・本線): E531系
            lineLower.contains("常磐") -> "joban_e531"

            // 川越線（西側: 高麗川〜川越）: 八高南線と同じく E231系3000番台
            lineLower.contains("川越-高麗川") || lineLower.contains("高麗川-川越") ||
                    (lineLower.contains("川越") && KAWAGOE_WEST_STATIONS.contains(stationName)) ||
                    (lineLower.contains("川越") && stationName == "川越" &&
                            departures.any { it.headsign.contains("高麗川") || it.headsign.contains("八王子") }) ||
                    (lineLower.contains("川越") && stationName == "高麗川" &&
                            !departures.any { it.headsign.contains("高崎") }) -> "hachiko"

            // 埼京線 / 川越線（東側: 川越〜大宮）: E233系7000番台
            lineLower.contains("埼京") ||
                    (lineLower.contains("川越") && (
                            KAWAGOE_EAST_STATIONS.contains(stationName) ||
                            stationName == "大宮" ||
                            (stationName == "川越" && !departures.any { it.headsign.contains("高麗川") || it.headsign.contains("八王子") })
                    )) -> "saikyo_e233_7000"

            // 八高線（高麗川以北）: HB-E220系
            lineLower.contains("八高") && (
                    HACHIKO_NORTH_STATIONS.contains(stationName) ||
                    departures.any { dep ->
                        val h = dep.headsign
                        h.contains("高崎") || h.contains("寄居") || h.contains("小川町") || h.contains("児玉")
                    }
            ) -> "hachiko_hbe220"

            // 八高線（高麗川以南）: E231系3000番台
            lineLower.contains("八高") -> "hachiko"

            // 武蔵野線: E231系0番台 (武蔵野線向け)
            lineLower.contains("武蔵野") -> "musashino"

            // JR 中央線・青梅線・五日市線: E233系0番台
            lineLower.contains("中央") || lineLower.contains("青梅") || lineLower.contains("五日市") -> "chuo"

            // JR 山手線: E235系0番台
            lineLower.contains("山手") -> "yamanote"

            // JR 京浜東北線・根岸線: E233系1000番台
            lineLower.contains("京浜東北") || lineLower.contains("根岸") -> "keihin_tohoku"

            // JR 湘南新宿ライン・上野東京ライン・東海道線・高崎線・宇都宮線: 湘南色等
            lineLower.contains("湘南新宿") || lineLower.contains("上野東京") ||
                    lineLower.contains("東海道") || lineLower.contains("高崎") ||
                    lineLower.contains("宇都宮") -> "shonan"

            // 東急東横線・みなとみらい線: 東急5050系
            lineLower.contains("東横") || lineLower.contains("みなとみらい") -> "tokyu_toyoko"

            // 東急田園都市線・大井町線・目黒線・新横浜線: 東急2020系
            lineLower.contains("田園都市") || lineLower.contains("大井町") ||
                    lineLower.contains("目黒") || lineLower.contains("新横浜") -> "tokyu_denentoshi"

            // 東急池上線・多摩川線: 東急7000系
            lineLower.contains("池上") || lineLower.contains("多摩川") -> "tokyu_ikegami"

            // 京王線・相模原線・高尾線: 京王5000系
            lineLower.contains("京王") && !lineLower.contains("井の頭") -> "keio_line"

            // 京王井の頭線: 京王1000系
            lineLower.contains("井の頭") -> "keio_inokashira"

            // 小田急線各線: 小田急5000形
            lineLower.contains("小田急") || lineLower.contains("小田原") ||
                    lineLower.contains("江ノ島") || lineLower.contains("多摩") -> "odakyu"

            // 西武線各線: 西武40000系
            lineLower.contains("西武") || lineLower.contains("新宿") && lineLower.contains("線") && !lineLower.contains("都営") ||
                    lineLower.contains("拝島") || lineLower.contains("国分寺") ||
                    lineLower.contains("池袋") && lineLower.contains("線") -> "seibu"

            // 東武越生線: 東武8000系
            lineLower.contains("越生") ||
                    (lineLower.contains("東武") && TOBU_OGOSE_STATIONS.contains(stationName) && stationName != "坂戸") -> "tobu_8000"

            // 東武小泉線・佐野線・桐生線など: 東武10000系
            lineLower.contains("小泉") || lineLower.contains("佐野") || lineLower.contains("桐生") ||
                    (lineLower.contains("東武") && TOBU_BRANCH_STATIONS.contains(stationName)) -> "tobu_10000"

            // 東武線本線系（スカイツリーライン・伊勢崎線・日光線・東上線等）: 東武70000系
            lineLower.contains("東武") || lineLower.contains("スカイツリー") ||
                    lineLower.contains("伊勢崎") || lineLower.contains("日光") ||
                    lineLower.contains("東上") -> "tobu"

            // 東京メトロ丸ノ内線: 2000系
            lineLower.contains("丸ノ内") || lineLower.contains("丸の内") -> "metro_marunouchi"

            // 東京メトロ銀座線: 1000系
            lineLower.contains("銀座") -> "metro_ginza"

            // 東京メトロ日比谷線: 13000系
            lineLower.contains("日比谷") -> "metro_hibiya"

            // 東京メトロ東西線: 15000系
            lineLower.contains("東西") -> "metro_tozai"

            // 東京メトロ千代田線: 16000系
            lineLower.contains("千代田") -> "metro_chiyoda"

            // 東京メトロ有楽町線・副都心線: 17000系
            lineLower.contains("有楽町") || lineLower.contains("副都心") -> "metro_yurakucho"

            // 東京メトロ半蔵門線: 18000系
            lineLower.contains("半蔵門") -> "metro_hanzomon"

            // 都営地下鉄大江戸線: 12-600形
            lineLower.contains("大江戸") -> "toei_oedo"

            // 都営地下鉄浅草線: 5500形
            lineLower.contains("浅草") -> "toei_asakusa"

            // 都営地下鉄三田線: 6500形
            lineLower.contains("三田") -> "toei_mita"

            // 都営地下鉄新宿線: 10-300形
            lineLower.contains("新宿線") || lineLower.contains("都営新宿") -> "toei_shinjuku"

            // その他鉄道（単独駅や駅名からマッチング）
            stationLower.contains("駅") || railLines.isNotEmpty() -> "chuo"

            else -> null
        }
    }

    /**
     * assets/train_photos/$key.jpg から Bitmap を安全にロード
     */
    fun loadBitmap(context: Context, key: String): Bitmap? {
        bitmapCache[key]?.let { return it }
        return try {
            val stream: InputStream = context.assets.open("train_photos/$key.jpg")
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            if (bmp != null) {
                bitmapCache[key] = bmp
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 時刻表シートの背景に表示する実写電車の透かし背景
 * 親のBoxのサイズを引き伸ばさないように modifier（matchParentSize）を受け取る
 */
@Composable
fun TrainPhotoWatermark(
    photoKey: String?,
    modifier: Modifier = Modifier
) {
    if (photoKey == null) return

    val context = LocalContext.current
    val bitmap = remember(photoKey) {
        TrainPhotoResolver.loadBitmap(context, photoKey)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.alpha(0.18f)
        )
    }
}
