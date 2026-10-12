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

            // 総武本線 / 総武快速線 / 横須賀線: E235系1000番台
            lineLower.contains("総武本線") || lineLower.contains("総武快速") ||
                    lineLower.contains("総武線快速") ||
                    (lineLower.contains("総武") && lineLower.contains("快速")) ||
                    lineLower.contains("横須賀") -> "sobu_rapid"

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

            // 八高線: E231系3000番台
            lineLower.contains("八高") -> "hachiko"

            // 武蔵野線: E231系0番台 (武蔵野線向け)
            lineLower.contains("武蔵野") -> "musashino"

            // 房総各線 (内房線・外房線・成田線本線・東金線): 209系2000番台 (※総武本線は除外済み)
            lineLower.contains("内房") || lineLower.contains("外房") ||
                    lineLower.contains("成田") || lineLower.contains("東金") -> "boso_209"

            // JR 中央線・青梅線・五日市線: E233系0番台
            lineLower.contains("中央") || lineLower.contains("青梅") || lineLower.contains("五日市") -> "chuo"

            // JR 山手線: E235系0番台
            lineLower.contains("山手") -> "yamanote"

            // JR 京浜東北線・根岸線: E233系1000番台
            lineLower.contains("京浜東北") || lineLower.contains("根岸") -> "keihin_tohoku"

            // JR 湘南新宿ライン・上野東京ライン・東海道線・高崎線・宇都宮線・埼京線・川越線: 湘南色等
            lineLower.contains("湘南新宿") || lineLower.contains("上野東京") ||
                    lineLower.contains("東海道") || lineLower.contains("高崎") ||
                    lineLower.contains("宇都宮") || lineLower.contains("埼京") ||
                    lineLower.contains("川越") -> "shonan"

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

            // 東武線各線: 東武70000系
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
