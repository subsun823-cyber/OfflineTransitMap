package com.example.offlinetransitmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb

/**
 * 駅を走る代表車両の定義と透かしアート（ウォーターマーク）描画
 */
data class TrainModelInfo(
    val seriesName: String,       // 例: "E233系0番台"
    val lineTitle: String,        // 例: "JR中央線快速"
    val seriesCode: String,       // 例: "SERIES E233-0"
    val primaryColor: Color,      // 帯・識別メインカラー
    val secondaryColor: Color = Color.Transparent,
    val isBus: Boolean = false
)

object TrainVehicleResolver {

    /**
     * 駅名、路線名リスト、選択中路線名などから走る代表車両を特定
     */
    fun resolve(
        stationName: String,
        railLines: List<StationLine>,
        selectedLine: String?,
        departures: List<Departure>
    ): TrainModelInfo {
        val targetLine = selectedLine
            ?: railLines.firstOrNull()?.name
            ?: departures.firstOrNull()?.lineName
            ?: ""

        val lineLower = targetLine.lowercase()
        val stationLower = stationName.lowercase()

        // 路線名による高精度マッチング
        return when {
            // JR 中央線・青梅線・五日市線
            lineLower.contains("中央") || lineLower.contains("青梅") || lineLower.contains("五日市") -> TrainModelInfo(
                seriesName = "E233系0番台",
                lineTitle = "JR中央線",
                seriesCode = "SERIES E233-0",
                primaryColor = Color(0xFFF15A22) // オレンジバーミリオン
            )

            // JR 山手線
            lineLower.contains("山手") -> TrainModelInfo(
                seriesName = "E235系0番台",
                lineTitle = "JR山手線",
                seriesCode = "SERIES E235-0",
                primaryColor = Color(0xFF80C241) // ウグイス
            )

            // JR 京浜東北線・根岸線
            lineLower.contains("京浜東北") || lineLower.contains("根岸") -> TrainModelInfo(
                seriesName = "E233系1000番台",
                lineTitle = "JR京浜東北線",
                seriesCode = "SERIES E233-1000",
                primaryColor = Color(0xFF00A3E0) // スカイブルー
            )

            // JR 総武線各駅停車
            lineLower.contains("総武") -> TrainModelInfo(
                seriesName = "E231系500番台",
                lineTitle = "JR総武線各駅停車",
                seriesCode = "SERIES E231-500",
                primaryColor = Color(0xFFFFD100) // カナリアイエロー
            )

            // JR 埼京線・川越線
            lineLower.contains("埼京") || lineLower.contains("川越") -> TrainModelInfo(
                seriesName = "E233系7000番台",
                lineTitle = "JR埼京線",
                seriesCode = "SERIES E233-7000",
                primaryColor = Color(0xFF00AC77)
            )

            // JR 横浜線
            lineLower.contains("横浜") -> TrainModelInfo(
                seriesName = "E233系6000番台",
                lineTitle = "JR横浜線",
                seriesCode = "SERIES E233-6000",
                primaryColor = Color(0xFF008000),
                secondaryColor = Color(0xFFA4C639)
            )

            // JR 東海道線・宇都宮線・高崎線・上野東京ライン・湘南新宿ライン
            lineLower.contains("東海道") || lineLower.contains("宇都宮") || lineLower.contains("高崎") || lineLower.contains("上野東京") || lineLower.contains("湘南新宿") -> TrainModelInfo(
                seriesName = "E233系3000番台",
                lineTitle = "JR東日本 湘南色",
                seriesCode = "SERIES E233-3000",
                primaryColor = Color(0xFFFF6600),
                secondaryColor = Color(0xFF006633)
            )

            // JR 京葉線・武蔵野線
            lineLower.contains("京葉") -> TrainModelInfo(
                seriesName = "E233系5000番台",
                lineTitle = "JR京葉線",
                seriesCode = "SERIES E233-5000",
                primaryColor = Color(0xFFC9252D)
            )
            lineLower.contains("武蔵野") -> TrainModelInfo(
                seriesName = "E231系0番台",
                lineTitle = "JR武蔵野線",
                seriesCode = "SERIES E231",
                primaryColor = Color(0xFFFF8000)
            )

            // 京王線・相模原線・高尾線
            lineLower.contains("京王") && !lineLower.contains("井の頭") -> TrainModelInfo(
                seriesName = "京王5000系",
                lineTitle = "京王電鉄",
                seriesCode = "KEIO 5000 SERIES",
                primaryColor = Color(0xFFDD0077), // 京王ピンク
                secondaryColor = Color(0xFF002B49) // インディゴブルー
            )

            // 京王井の頭線
            lineLower.contains("井の頭") -> TrainModelInfo(
                seriesName = "京王1000系",
                lineTitle = "京王井の頭線",
                seriesCode = "KEIO 1000 SERIES",
                primaryColor = Color(0xFF0099FF)
            )

            // 東急東横線・みなとみらい線
            lineLower.contains("東横") || lineLower.contains("みなとみらい") -> TrainModelInfo(
                seriesName = "東急5050系",
                lineTitle = "東急東横線",
                seriesCode = "TOKYU 5050 SERIES",
                primaryColor = Color(0xFFDA291C)
            )

            // 東急田園都市線
            lineLower.contains("田園都市") -> TrainModelInfo(
                seriesName = "東急2020系",
                lineTitle = "東急田園都市線",
                seriesCode = "TOKYU 2020 SERIES",
                primaryColor = Color(0xFF20A352)
            )

            // 東急目黒線・新横浜線
            lineLower.contains("目黒") || lineLower.contains("新横浜") -> TrainModelInfo(
                seriesName = "東急3020系",
                lineTitle = "東急目黒線",
                seriesCode = "TOKYU 3020 SERIES",
                primaryColor = Color(0xFF0077C8)
            )

            // 東急大井町線
            lineLower.contains("大井町") -> TrainModelInfo(
                seriesName = "東急6020系",
                lineTitle = "東急大井町線",
                seriesCode = "TOKYU 6020 SERIES",
                primaryColor = Color(0xFFF68B1F)
            )

            // 東急池上線・多摩川線
            lineLower.contains("池上") || lineLower.contains("多摩川") -> TrainModelInfo(
                seriesName = "東急7000系",
                lineTitle = "東急池上・多摩川線",
                seriesCode = "TOKYU 7000 SERIES",
                primaryColor = Color(0xFF008060)
            )

            // 西武池袋線・新宿線
            lineLower.contains("西武") || lineLower.contains("拝島") || lineLower.contains("国分寺") -> TrainModelInfo(
                seriesName = "西武40000系",
                lineTitle = "西武鉄道",
                seriesCode = "SEIBU 40000 SERIES",
                primaryColor = Color(0xFF0084DE),
                secondaryColor = Color(0xFF88D22C)
            )

            // 東武スカイツリーライン・伊勢崎線・日光線
            lineLower.contains("スカイツリー") || lineLower.contains("伊勢崎") || lineLower.contains("日光") -> TrainModelInfo(
                seriesName = "東武70000系",
                lineTitle = "東武スカイツリーライン",
                seriesCode = "TOBU 70000 SERIES",
                primaryColor = Color(0xFFE35205),
                secondaryColor = Color(0xFF0F2D69)
            )

            // 東武東上線
            lineLower.contains("東上") -> TrainModelInfo(
                seriesName = "東武50000系",
                lineTitle = "東武東上線",
                seriesCode = "TOBU 50000 SERIES",
                primaryColor = Color(0xFFFF6600)
            )

            // 東京メトロ銀座線
            lineLower.contains("銀座") -> TrainModelInfo(
                seriesName = "メトロ1000系",
                lineTitle = "東京メトロ銀座線",
                seriesCode = "METRO 1000 SERIES",
                primaryColor = Color(0xFFF39700)
            )

            // 東京メトロ丸ノ内線
            lineLower.contains("丸ノ内") || lineLower.contains("丸の内") -> TrainModelInfo(
                seriesName = "メトロ2000系",
                lineTitle = "東京メトロ丸ノ内線",
                seriesCode = "METRO 2000 SERIES",
                primaryColor = Color(0xFFE60012)
            )

            // 東京メトロ日比谷線
            lineLower.contains("日比谷") -> TrainModelInfo(
                seriesName = "メトロ13000系",
                lineTitle = "東京メトロ日比谷線",
                seriesCode = "METRO 13000 SERIES",
                primaryColor = Color(0xFF9CAEB7)
            )

            // 東京メトロ東西線
            lineLower.contains("東西") -> TrainModelInfo(
                seriesName = "メトロ15000系",
                lineTitle = "東京メトロ東西線",
                seriesCode = "METRO 15000 SERIES",
                primaryColor = Color(0xFF009BBF)
            )

            // 東京メトロ千代田線
            lineLower.contains("千代田") -> TrainModelInfo(
                seriesName = "メトロ16000系",
                lineTitle = "東京メトロ千代田線",
                seriesCode = "METRO 16000 SERIES",
                primaryColor = Color(0xFF00A88F)
            )

            // 東京メトロ有楽町線・副都心線
            lineLower.contains("有楽町") || lineLower.contains("副都心") -> TrainModelInfo(
                seriesName = "メトロ17000系",
                lineTitle = "東京メトロ副都心・有楽町線",
                seriesCode = "METRO 17000 SERIES",
                primaryColor = Color(0xFF9B7CB6),
                secondaryColor = Color(0xFFD7C447)
            )

            // 東京メトロ半蔵門線
            lineLower.contains("半蔵門") -> TrainModelInfo(
                seriesName = "メトロ18000系",
                lineTitle = "東京メトロ半蔵門線",
                seriesCode = "METRO 18000 SERIES",
                primaryColor = Color(0xFF8F76D6)
            )

            // 東京メトロ南北線
            lineLower.contains("南北") -> TrainModelInfo(
                seriesName = "メトロ9000系",
                lineTitle = "東京メトロ南北線",
                seriesCode = "METRO 9000 SERIES",
                primaryColor = Color(0xFF00AC9B)
            )

            // 都営大江戸線
            lineLower.contains("大江戸") -> TrainModelInfo(
                seriesName = "都営12-600形",
                lineTitle = "都営大江戸線",
                seriesCode = "TOEI 12-600 SERIES",
                primaryColor = Color(0xFFB6007A)
            )

            // 都営浅草線
            lineLower.contains("浅草") -> TrainModelInfo(
                seriesName = "都営5500形",
                lineTitle = "都営浅草線",
                seriesCode = "TOEI 5500 SERIES",
                primaryColor = Color(0xFFE85298)
            )

            // 都営三田線
            lineLower.contains("三田") -> TrainModelInfo(
                seriesName = "都営6500形",
                lineTitle = "都営三田線",
                seriesCode = "TOEI 6500 SERIES",
                primaryColor = Color(0xFF0079C2)
            )

            // 都営新宿線
            lineLower.contains("都営新宿") || lineLower.contains("新宿線") -> TrainModelInfo(
                seriesName = "都営10-300形",
                lineTitle = "都営新宿線",
                seriesCode = "TOEI 10-300 SERIES",
                primaryColor = Color(0xFF6CBB5A)
            )

            // バス停の場合
            targetLine.contains("系統") || targetLine.contains("バス") || railLines.isEmpty() -> TrainModelInfo(
                seriesName = "ノンステップ路線バス",
                lineTitle = "路線バス",
                seriesCode = "NON-STEP CITY BUS",
                primaryColor = Color(0xFF1A73E8),
                isBus = true
            )

            // デフォルト鉄道
            else -> TrainModelInfo(
                seriesName = "E233系通勤型電車",
                lineTitle = targetLine.ifBlank { stationName },
                seriesCode = "COMMUTER TRAIN",
                primaryColor = Color(0xFF0077C8)
            )
        }
    }
}

/**
 * 時刻表シートの背景に描く、電車の精悍な透かしアート
 */
@Composable
fun TrainWatermarkBackground(
    trainInfo: TrainModelInfo,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 右下〜中央寄りに電車前面・サイドの透かしを描画
        val trainW = w * 0.72f
        val trainH = trainW * 1.08f
        val left = w - trainW + w * 0.08f
        val top = h * 0.15f

        val lineColor = trainInfo.primaryColor
        val subColor = if (trainInfo.secondaryColor != Color.Transparent) trainInfo.secondaryColor else lineColor

        if (trainInfo.isBus) {
            drawBusWatermark(left, top, trainW, trainH, lineColor)
        } else {
            drawTrainWatermark(left, top, trainW, trainH, lineColor, subColor)
        }

        // 形式名と路線名のスタイリッシュなタイポグラフィ透かし
        drawTrainTypography(
            w = w,
            h = h,
            seriesName = trainInfo.seriesName,
            seriesCode = trainInfo.seriesCode,
            lineTitle = trainInfo.lineTitle,
            lineColor = lineColor
        )
    }
}

/**
 * 電車の前面・ボディ透かし描画
 */
private fun DrawScope.drawTrainWatermark(
    left: Float,
    top: Float,
    w: Float,
    h: Float,
    lineColor: Color,
    subColor: Color
) {
    val alphaOuter = 0.10f
    val alphaColor = 0.18f
    val strokeColor = Color.Gray.copy(alpha = 0.14f)

    // 1. 車体外郭 (ステンレス車体フォルム)
    val bodyPath = Path().apply {
        // 屋根の丸み
        moveTo(left + w * 0.12f, top + h * 0.08f)
        cubicTo(
            left + w * 0.35f, top,
            left + w * 0.65f, top,
            left + w * 0.88f, top + h * 0.08f
        )
        // 側面
        lineTo(left + w * 0.96f, top + h * 0.78f)
        // スカート・台車まわり
        lineTo(left + w * 0.90f, top + h * 0.90f)
        lineTo(left + w * 0.65f, top + h * 0.94f)
        lineTo(left + w * 0.50f, top + h * 0.92f)
        lineTo(left + w * 0.35f, top + h * 0.94f)
        lineTo(left + w * 0.10f, top + h * 0.90f)
        lineTo(left + w * 0.04f, top + h * 0.78f)
        close()
    }

    drawPath(
        path = bodyPath,
        color = Color.LightGray.copy(alpha = alphaOuter),
        style = Fill
    )
    drawPath(
        path = bodyPath,
        color = strokeColor,
        style = Stroke(width = 2.5f)
    )

    // 2. ブラックフェイス (前面窓・運転台エリア)
    val windowPath = Path().apply {
        moveTo(left + w * 0.16f, top + h * 0.14f)
        cubicTo(
            left + w * 0.35f, top + h * 0.09f,
            left + w * 0.65f, top + h * 0.09f,
            left + w * 0.84f, top + h * 0.14f
        )
        lineTo(left + w * 0.86f, top + h * 0.44f)
        lineTo(left + w * 0.14f, top + h * 0.44f)
        close()
    }
    drawPath(
        path = windowPath,
        color = Color(0xFF20232A).copy(alpha = 0.16f),
        style = Fill
    )
    drawPath(
        path = windowPath,
        color = strokeColor,
        style = Stroke(width = 2.0f)
    )

    // 3. 上部ラインカラー帯 (行先表示器の周囲・屋根肩部)
    drawRoundRect(
        color = lineColor.copy(alpha = alphaColor),
        topLeft = Offset(left + w * 0.20f, top + h * 0.05f),
        size = Size(w * 0.60f, h * 0.04f),
        cornerRadius = CornerRadius(4f, 4f)
    )

    // 4. 前面行先表示LED (ブラックウィンドウ上部)
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.22f),
        topLeft = Offset(left + w * 0.36f, top + h * 0.11f),
        size = Size(w * 0.28f, h * 0.055f),
        cornerRadius = CornerRadius(3f, 3f)
    )

    // 5. 腰部メインラインカラー帯 (電車の象徴的なカラーライン)
    drawRoundRect(
        color = lineColor.copy(alpha = alphaColor + 0.04f),
        topLeft = Offset(left + w * 0.08f, top + h * 0.47f),
        size = Size(w * 0.84f, h * 0.075f),
        cornerRadius = CornerRadius(6f, 6f)
    )
    // サブカラー帯（湘南色や京王、メトロ等で2色ある場合）
    if (subColor != lineColor) {
        drawRoundRect(
            color = subColor.copy(alpha = alphaColor + 0.04f),
            topLeft = Offset(left + w * 0.08f, top + h * 0.555f),
            size = Size(w * 0.84f, h * 0.035f),
            cornerRadius = CornerRadius(4f, 4f)
        )
    }

    // 6. 前面ライト (ヘッドライト・テールライト)
    // 左ライト
    drawRoundRect(
        color = Color.White.copy(alpha = 0.30f),
        topLeft = Offset(left + w * 0.18f, top + h * 0.485f),
        size = Size(w * 0.11f, h * 0.042f),
        cornerRadius = CornerRadius(4f, 4f)
    )
    drawRoundRect(
        color = lineColor.copy(alpha = 0.35f),
        topLeft = Offset(left + w * 0.18f, top + h * 0.485f),
        size = Size(w * 0.11f, h * 0.042f),
        cornerRadius = CornerRadius(4f, 4f),
        style = Stroke(width = 1.5f)
    )

    // 右ライト
    drawRoundRect(
        color = Color.White.copy(alpha = 0.30f),
        topLeft = Offset(left + w * 0.71f, top + h * 0.485f),
        size = Size(w * 0.11f, h * 0.042f),
        cornerRadius = CornerRadius(4f, 4f)
    )
    drawRoundRect(
        color = lineColor.copy(alpha = 0.35f),
        topLeft = Offset(left + w * 0.71f, top + h * 0.485f),
        size = Size(w * 0.11f, h * 0.042f),
        cornerRadius = CornerRadius(4f, 4f),
        style = Stroke(width = 1.5f)
    )

    // 7. スカート・連結器まわり
    val couplerPath = Path().apply {
        moveTo(left + w * 0.45f, top + h * 0.80f)
        lineTo(left + w * 0.55f, top + h * 0.80f)
        lineTo(left + w * 0.53f, top + h * 0.90f)
        lineTo(left + w * 0.47f, top + h * 0.90f)
        close()
    }
    drawPath(
        path = couplerPath,
        color = Color(0xFF333333).copy(alpha = 0.18f),
        style = Fill
    )
}

/**
 * 路線バスの前面透かし描画
 */
private fun DrawScope.drawBusWatermark(
    left: Float,
    top: Float,
    w: Float,
    h: Float,
    lineColor: Color
) {
    val alphaOuter = 0.10f
    val alphaColor = 0.18f
    val strokeColor = Color.Gray.copy(alpha = 0.14f)

    // バス車体フォルム (箱型・角丸)
    val busPath = Path().apply {
        moveTo(left + w * 0.15f, top + h * 0.06f)
        lineTo(left + w * 0.85f, top + h * 0.06f)
        lineTo(left + w * 0.90f, top + h * 0.85f)
        lineTo(left + w * 0.10f, top + h * 0.85f)
        close()
    }
    drawPath(path = busPath, color = Color.LightGray.copy(alpha = alphaOuter), style = Fill)
    drawPath(path = busPath, color = strokeColor, style = Stroke(width = 2.5f))

    // フロントガラス (上下2段)
    drawRoundRect(
        color = Color(0xFF20232A).copy(alpha = 0.18f),
        topLeft = Offset(left + w * 0.18f, top + h * 0.15f),
        size = Size(w * 0.64f, h * 0.32f),
        cornerRadius = CornerRadius(6f, 6f)
    )

    // 行先表示LED
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.25f),
        topLeft = Offset(left + w * 0.28f, top + h * 0.08f),
        size = Size(w * 0.44f, h * 0.055f),
        cornerRadius = CornerRadius(4f, 4f)
    )

    // カラー帯
    drawRoundRect(
        color = lineColor.copy(alpha = alphaColor),
        topLeft = Offset(left + w * 0.12f, top + h * 0.52f),
        size = Size(w * 0.76f, h * 0.08f),
        cornerRadius = CornerRadius(4f, 4f)
    )

    // ヘッドライト (角型2灯)
    drawRoundRect(
        color = Color.White.copy(alpha = 0.30f),
        topLeft = Offset(left + w * 0.16f, top + h * 0.64f),
        size = Size(w * 0.15f, h * 0.06f),
        cornerRadius = CornerRadius(4f, 4f)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.30f),
        topLeft = Offset(left + w * 0.69f, top + h * 0.64f),
        size = Size(w * 0.15f, h * 0.06f),
        cornerRadius = CornerRadius(4f, 4f)
    )
}

/**
 * 形式名・シリーズコードのタイポグラフィ透かし
 */
private fun DrawScope.drawTrainTypography(
    w: Float,
    h: Float,
    seriesName: String,
    seriesCode: String,
    lineTitle: String,
    lineColor: Color
) {
    val native = drawContext.canvas.nativeCanvas

    // 1. 大判シリーズコード (例: "SERIES E233-0") - ダイナミックな斜体英字
    val codePaint = Paint().apply {
        isAntiAlias = true
        color = Color.Gray.copy(alpha = 0.08f).toArgb()
        textSize = w * 0.11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        textAlign = Paint.Align.RIGHT
    }
    native.drawText(seriesCode, w * 0.94f, h * 0.78f, codePaint)

    // 2. 日本語車両名・路線名 (例: "E233系0番台 · JR中央線")
    val titlePaint = Paint().apply {
        isAntiAlias = true
        color = lineColor.copy(alpha = 0.18f).toArgb()
        textSize = w * 0.052f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    native.drawText("$seriesName · $lineTitle", w * 0.94f, h * 0.84f, titlePaint)
}
