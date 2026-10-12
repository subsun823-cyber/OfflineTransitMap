package com.example.offlinetransitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
            // JR 中央線・青梅線・五日市線 (E233系0番台)
            lineLower.contains("中央") || lineLower.contains("青梅") || lineLower.contains("五日市") -> "chuo"

            // JR 山手線 (E235系0番台)
            lineLower.contains("山手") -> "yamanote"

            // JR 京浜東北線・根岸線 (E233系1000番台)
            lineLower.contains("京浜東北") || lineLower.contains("根岸") -> "keihin_tohoku"

            // JR 総武線各駅停車 (E231系500番台)
            lineLower.contains("総武") -> "sobu"

            // JR 湘南新宿ライン・上野東京ライン・東海道線・高崎線・宇都宮線 (湘南色)
            lineLower.contains("湘南新宿") || lineLower.contains("上野東京") ||
                    lineLower.contains("東海道") || lineLower.contains("高崎") ||
                    lineLower.contains("宇都宮") || lineLower.contains("埼京") ||
                    lineLower.contains("横浜") || lineLower.contains("武蔵野") ||
                    lineLower.contains("京葉") -> "shonan"

            // 東急東横線・みなとみらい線 (東急5050系)
            lineLower.contains("東横") || lineLower.contains("みなとみらい") -> "tokyu_toyoko"

            // 東急田園都市線・大井町線 (東急2020系)
            lineLower.contains("田園都市") || lineLower.contains("大井町") || lineLower.contains("目黒") || lineLower.contains("新横浜") -> "tokyu_denentoshi"

            // 東急池上線・多摩川線 (東急7000系)
            lineLower.contains("池上") || lineLower.contains("多摩川") -> "tokyu_ikegami"

            // 京王線・相模原線・高尾線 (京王5000系)
            lineLower.contains("京王") && !lineLower.contains("井の頭") -> "keio_line"

            // 京王井の頭線 (京王1000系)
            lineLower.contains("井の頭") -> "keio_inokashira"

            // 小田急線各線 (小田急5000形)
            lineLower.contains("小田急") || lineLower.contains("小田原") || lineLower.contains("江ノ島") || lineLower.contains("多摩") -> "odakyu"

            // 西武線各線 (西武40000系)
            lineLower.contains("西武") || lineLower.contains("新宿") && lineLower.contains("線") && !lineLower.contains("都営") ||
                    lineLower.contains("拝島") || lineLower.contains("国分寺") || lineLower.contains("池袋") && lineLower.contains("線") -> "seibu"

            // 東武線各線 (東武70000系)
            lineLower.contains("東武") || lineLower.contains("スカイツリー") || lineLower.contains("伊勢崎") || lineLower.contains("日光") || lineLower.contains("東上") -> "tobu"

            // 東京メトロ丸ノ内線 (2000系)
            lineLower.contains("丸ノ内") || lineLower.contains("丸の内") -> "metro_marunouchi"

            // 東京メトロ銀座線 (1000系)
            lineLower.contains("銀座") -> "metro_ginza"

            // 東京メトロ日比谷線 (13000系)
            lineLower.contains("日比谷") -> "metro_hibiya"

            // 東京メトロ東西線 (15000系)
            lineLower.contains("東西") -> "metro_tozai"

            // 東京メトロ千代田線 (16000系)
            lineLower.contains("千代田") -> "metro_chiyoda"

            // 東京メトロ有楽町線・副都心線 (17000系)
            lineLower.contains("有楽町") || lineLower.contains("副都心") -> "metro_yurakucho"

            // 東京メトロ半蔵門線 (18000系)
            lineLower.contains("半蔵門") -> "metro_hanzomon"

            // 都営地下鉄大江戸線 (12-600形)
            lineLower.contains("大江戸") -> "toei_oedo"

            // 都営地下鉄浅草線 (5500形)
            lineLower.contains("浅草") -> "toei_asakusa"

            // 都営地下鉄三田線 (6500形)
            lineLower.contains("三田") -> "toei_mita"

            // 都営地下鉄新宿線 (10-300形)
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
 * 文字などは入れず、写真画像のみをスタイリッシュに半透明表示
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
        Box(modifier = modifier.fillMaxSize()) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.18f) // 絶妙な透かし具合（文字の可読性を100%確保）
            )
        }
    }
}
