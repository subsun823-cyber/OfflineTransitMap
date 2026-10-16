package com.example.offlinetransitmap

import android.location.Location
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

// 車内LCDモニター（JR東日本 E233系 トレインビジョン / LCD Studio準拠）の走行フェーズ
enum class TrainLcdPhase {
    BEFORE_DEPARTURE, // 「この電車は / This train is bound for / このでんしゃは」
    NEXT_STATION,     // 「次は / Next / つぎは」
    ARRIVING_AT,      // 「まもなく / Arriving at / まもなく」
    STOPPING_AT       // 「ただいま / Now Stopping at / ただいま」
}

// 3言語サイクル表示モード（漢字 → 英語 → ひらがな）
enum class TrainLcdLangMode {
    KANJI,
    ENGLISH,
    HIRAGANA
}

// 路線テーマ・駅ナンバリング情報
data class TrainLineVisualTheme(
    val lineColor: Color,
    val linePrefix: String,
    val isCircleBadge: Boolean = false
)

// LCDモニター上の1駅分の表示データ
data class TrainLcdStopItem(
    val index: Int,
    val kanji: String,
    val hiragana: String,
    val romaji: String,
    val stationNumberCode: String, // 例: "JA-25"
    val stationNumberDigits: String, // 例: "25"
    val minutesFromNow: Int,
    val isAlightStation: Boolean,
    val transferLines: List<TrainTransferBadgeInfo>
)

data class TrainTransferBadgeInfo(
    val prefix: String,
    val nameJp: String,
    val nameEn: String,
    val color: Color
)

// GPSおよび時刻表から特定された現在のLCD状態
data class TrainLcdResolvedState(
    val phase: TrainLcdPhase,
    val prevStopIndex: Int,
    val targetStopIndex: Int,
    val segmentProgress: Float, // 0.0f (前駅) 〜 1.0f (次駅)
    val allStops: List<TrainLcdStopItem>,
    val alightStopIndex: Int
)

/**
 * 電車の車内LCD案内モニター（トレインビジョン / LCD Studio 完全再現コンポーネント）
 */
@Composable
fun TrainLcdMonitorCard(
    guidance: Guidance?,
    itinerary: Itinerary?,
    location: Location?,
    modifier: Modifier = Modifier
) {
    val legs = itinerary?.legs ?: emptyList()

    // 1秒周期で現在時刻・コロン点滅・多言語サイクルを更新
    var tickMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500L)
            tickMillis = System.currentTimeMillis()
        }
    }

    val now = LocalDateTime.now()
    val colonVisible = ((tickMillis / 1000L) % 2L) == 0L

    // 3秒ごとに 漢字 -> 英語 -> ひらがな を切り替え
    val langCycleIndex = ((tickMillis / 3200L) % 3L).toInt()
    val langMode = when (langCycleIndex) {
        0 -> TrainLcdLangMode.KANJI
        1 -> TrainLcdLangMode.ENGLISH
        else -> TrainLcdLangMode.HIRAGANA
    }

    val trainLeg = legs.firstOrNull { leg ->
        !leg.isWalk && leg.lineName != "自転車" &&
            (leg.arrTime == null || now.isBefore(leg.arrTime.plusMinutes(2)))
    } ?: legs.firstOrNull { !it.isWalk && it.lineName != "自転車" }

    if (trainLeg == null) {
        TopManeuverCard(guidance = guidance, modifier = modifier)
        return
    }

    // 1. 路線カラーと路線記号(JA, JC, JY, KO, OH 등)を解決
    val lineTheme = remember(trainLeg.lineName, trainLeg.operatorLabel, trainLeg.lineColor) {
        resolveTrainLineTheme(trainLeg.lineName, trainLeg.operatorLabel, trainLeg.lineColor)
    }
    val lineColor = lineTheme.lineColor

    // 2. 種別・行先・号車番号の解決
    val rawTrainType = trainLeg.trainType.ifBlank { "各駅停車" }
    val (typeJp, typeEn, typeBadgeColor) = remember(rawTrainType, lineColor) {
        resolveTrainTypeDisplay(rawTrainType, lineColor)
    }

    val rawHeadsign = trainLeg.headsign
        .removeSuffix("行")
        .removeSuffix("ゆき")
        .removeSuffix("方面")
        .trim()
        .ifBlank { trainLeg.toName.removeSuffix("駅").trim() }
    val headsignKanji = rawHeadsign.removeSuffix("駅").trim()
    val headsignHiragana = remember(headsignKanji, trainLeg.headsignKana) {
        resolveStationHiragana(headsignKanji, trainLeg.headsignKana)
    }
    val headsignRomaji = remember(headsignKanji, headsignHiragana) {
        resolveStationRomaji(headsignKanji, headsignHiragana)
    }

    val carCount = remember(trainLeg.lineName, rawTrainType) {
        PlatformAndCarResolver.resolveCarCount(trainLeg.lineName, rawTrainType, false) ?: 10
    }
    val displayCarNo = carCount

    // 3. GPS位置と時刻表から現在の走行フェーズ（この電車は / 次は / まもなく / ただいま）と各駅の所要分数を特定
    val lcdState = resolveTrainLcdState(
        trainLeg = trainLeg,
        guidance = guidance,
        location = location,
        now = now,
        linePrefix = lineTheme.linePrefix
    )

    val phase = lcdState.phase
    val targetStop = lcdState.allStops.getOrElse(lcdState.targetStopIndex) {
        lcdState.allStops.lastOrNull() ?: TrainLcdStopItem(
            index = 0,
            kanji = trainLeg.toName,
            hiragana = resolveStationHiragana(trainLeg.toName, ""),
            romaji = resolveStationRomaji(trainLeg.toName, ""),
            stationNumberCode = "${lineTheme.linePrefix}-01",
            stationNumberDigits = "01",
            minutesFromNow = 2,
            isAlightStation = true,
            transferLines = emptyList()
        )
    }

    // 始発停車中（BEFORE_DEPARTURE）のヘッダーバッジは行先駅の駅番号（例: 新木場 JA-01）を表示
    val headerStationDigits = if (phase == TrainLcdPhase.BEFORE_DEPARTURE) {
        resolveStationNumberDigits(lineTheme.linePrefix, headsignKanji, fallbackNum = 1)
    } else {
        targetStop.stationNumberDigits
    }

    // 下部エリアの2段全駅一覧切替（BEFORE_DEPARTURE かつ 駅数が8駅超のとき、9.6秒周期で1段バー ⇔ 2段バー切替）
    val showTwoRowAllStations = phase == TrainLcdPhase.BEFORE_DEPARTURE &&
        lcdState.allStops.size > 8 &&
        ((tickMillis / 9600L) % 2L == 1L)

    // 外枠ベゼル（車内LCDディスプレイ筐体フレーム）
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14181F)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.5.dp, Color(0xFF2E3644)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        // 液晶パネル本体（白背景）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFFF9FBFD))
        ) {
            // =========================================================================
            // 1. 最上段: 路線カラーアクセントライン ＋ 種別・行先・号車バー
            // =========================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                lineColor,
                                lineColor,
                                Color(0xFF1E242B)
                            )
                        )
                    )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF1F4F8),
                                Color(0xFFDCE2EA)
                            )
                        )
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左: [種別バッジ] + 行先テキスト
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // 種別バッジ (例: 各駅停車 / Local)
                    Box(
                        modifier = Modifier
                            .height(21.dp)
                            .width(74.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        lightenColor(typeBadgeColor, 0.12f),
                                        typeBadgeColor,
                                        darkenColor(typeBadgeColor, 0.15f)
                                    )
                                ),
                                shape = RoundedCornerShape(3.5.dp)
                            )
                            .border(0.8.dp, darkenColor(typeBadgeColor, 0.25f), RoundedCornerShape(3.5.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val badgeText = if (langMode == TrainLcdLangMode.ENGLISH) typeEn else typeJp
                        Text(
                            text = badgeText,
                            color = Color.White,
                            fontSize = if (badgeText.length >= 6) 10.sp else 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }

                    // 行先 (例: 新木場 行 / for Shin-Kiba)
                    if (langMode == TrainLcdLangMode.ENGLISH) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "for ",
                                color = Color(0xFF22262C),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                            Text(
                                text = headsignRomaji,
                                color = Color(0xFF111418),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = headsignKanji,
                                color = Color(0xFF111418),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "行",
                                color = Color(0xFF22262C),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }

                // 右: 号車バッジ (10号車 / Car No. 10)
                Box(
                    modifier = Modifier
                        .height(22.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(Color(0xFF323842), Color(0xFF16191E))
                            ),
                            shape = RoundedCornerShape(3.5.dp)
                        )
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (langMode == TrainLcdLangMode.ENGLISH) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "Car No. ",
                                color = Color.White,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                            Text(
                                text = "$displayCarNo",
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Black,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "$displayCarNo",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                            Spacer(Modifier.width(1.5.dp))
                            Text(
                                text = "号車",
                                color = Color.White,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 1.dp),
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }
            }

            // 上段と中段の細い境界線
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFFC5CCD6))
            )

            // =========================================================================
            // 2. 中段メインヘッダー: 状態ラベル ＋ 駅ナンバリング ＋ 特大駅名 ＋ 現在時刻
            // =========================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Color.White)
                    .padding(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左: 状態ラベル (この電車は / 次は / まもなく / ただいま)
                    Box(
                        modifier = Modifier.width(74.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        val statusText = when (phase) {
                            TrainLcdPhase.BEFORE_DEPARTURE -> when (langMode) {
                                TrainLcdLangMode.KANJI -> "この電車は"
                                TrainLcdLangMode.ENGLISH -> "This train\nis bound for"
                                TrainLcdLangMode.HIRAGANA -> "このでんしゃは"
                            }
                            TrainLcdPhase.NEXT_STATION -> when (langMode) {
                                TrainLcdLangMode.KANJI -> "次は"
                                TrainLcdLangMode.ENGLISH -> "Next"
                                TrainLcdLangMode.HIRAGANA -> "つぎは"
                            }
                            TrainLcdPhase.ARRIVING_AT -> when (langMode) {
                                TrainLcdLangMode.KANJI -> "まもなく"
                                TrainLcdLangMode.ENGLISH -> "Arriving at"
                                TrainLcdLangMode.HIRAGANA -> "まもなく"
                            }
                            TrainLcdPhase.STOPPING_AT -> when (langMode) {
                                TrainLcdLangMode.KANJI -> "ただいま"
                                TrainLcdLangMode.ENGLISH -> "Now\nStopping at"
                                TrainLcdLangMode.HIRAGANA -> "ただいま"
                            }
                        }
                        val statusFontSize = when {
                            statusText.contains("\n") -> 10.5.sp
                            statusText.length >= 6 -> 10.5.sp
                            statusText.length >= 4 -> 13.5.sp
                            else -> 15.5.sp
                        }
                        Text(
                            text = statusText,
                            color = Color(0xFF181B20),
                            fontSize = statusFontSize,
                            lineHeight = 12.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }

                    // 駅ナンバリング正方形バッジ (例: [JA / 25])
                    TrainStationNumberBadge(
                        linePrefix = lineTheme.linePrefix,
                        stationDigits = headerStationDigits,
                        lineColor = lineColor,
                        isCircle = lineTheme.isCircleBadge
                    )

                    // 中央: 特大駅名 / 行先名（3言語縦フリップ＆クロスフェードアニメーション）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = Triple(phase, langMode, targetStop.kanji),
                            transitionSpec = {
                                (slideInVertically(animationSpec = tween(260)) { h -> h / 2 } +
                                    fadeIn(animationSpec = tween(260))) togetherWith
                                    (slideOutVertically(animationSpec = tween(220)) { h -> -h / 2 } +
                                        fadeOut(animationSpec = tween(200))) using
                                    SizeTransform(clip = true)
                            },
                            label = "mainStationFlip"
                        ) { (curPhase, curLang, _) ->
                            if (curPhase == TrainLcdPhase.BEFORE_DEPARTURE) {
                                when (curLang) {
                                    TrainLcdLangMode.KANJI -> {
                                        Row(verticalAlignment = Alignment.Bottom) {
                                            val mainSp = when {
                                                headsignKanji.length >= 6 -> 21.sp
                                                headsignKanji.length == 5 -> 24.sp
                                                else -> 28.sp
                                            }
                                            Text(
                                                text = headsignKanji,
                                                color = Color(0xFF111317),
                                                fontSize = mainSp,
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = if (headsignKanji.length <= 3) 3.sp else 1.sp,
                                                maxLines = 1,
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "行",
                                                color = Color(0xFF111317),
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.padding(bottom = 2.dp),
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                            )
                                        }
                                    }
                                    TrainLcdLangMode.ENGLISH -> {
                                        val engSp = when {
                                            headsignRomaji.length >= 14 -> 20.sp
                                            headsignRomaji.length >= 10 -> 23.sp
                                            else -> 27.sp
                                        }
                                        Text(
                                            text = headsignRomaji,
                                            color = Color(0xFF111317),
                                            fontSize = engSp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1,
                                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                        )
                                    }
                                    TrainLcdLangMode.HIRAGANA -> {
                                        Row(verticalAlignment = Alignment.Bottom) {
                                            val hiraSp = when {
                                                headsignHiragana.length >= 7 -> 20.sp
                                                headsignHiragana.length >= 5 -> 23.sp
                                                else -> 26.sp
                                            }
                                            Text(
                                                text = headsignHiragana,
                                                color = Color(0xFF111317),
                                                fontSize = hiraSp,
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 1.sp,
                                                maxLines = 1,
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "ゆき",
                                                color = Color(0xFF111317),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                modifier = Modifier.padding(bottom = 2.dp),
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 次は / まもなく / ただいま 駅名表示
                                when (curLang) {
                                    TrainLcdLangMode.KANJI -> {
                                        val stKanji = targetStop.kanji
                                        val mainSp = when {
                                            stKanji.length >= 6 -> 22.sp
                                            stKanji.length == 5 -> 25.sp
                                            else -> 30.sp
                                        }
                                        val spacing = when {
                                            stKanji.length == 2 -> 8.sp
                                            stKanji.length == 3 -> 4.sp
                                            else -> 1.sp
                                        }
                                        Text(
                                            text = stKanji,
                                            color = Color(0xFF111317),
                                            fontSize = mainSp,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = spacing,
                                            maxLines = 1,
                                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                        )
                                    }
                                    TrainLcdLangMode.ENGLISH -> {
                                        val stRomaji = targetStop.romaji
                                        val engSp = when {
                                            stRomaji.length >= 14 -> 20.sp
                                            stRomaji.length >= 10 -> 24.sp
                                            else -> 27.sp
                                        }
                                        Text(
                                            text = stRomaji,
                                            color = Color(0xFF111317),
                                            fontSize = engSp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1,
                                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                        )
                                    }
                                    TrainLcdLangMode.HIRAGANA -> {
                                        val stHira = targetStop.hiragana
                                        val hiraSp = when {
                                            stHira.length >= 8 -> 20.sp
                                            stHira.length >= 6 -> 23.sp
                                            else -> 27.sp
                                        }
                                        Text(
                                            text = stHira,
                                            color = Color(0xFF111317),
                                            fontSize = hiraSp,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = if (stHira.length <= 4) 3.sp else 1.sp,
                                            maxLines = 1,
                                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 右下: 現在時刻 (HH:mm コロン点滅)
                    Box(
                        modifier = Modifier
                            .width(54.dp)
                            .fillMaxHeight()
                            .padding(bottom = 4.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = String.format("%02d", now.hour),
                                color = Color(0xFF181B20),
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                            Text(
                                text = ":",
                                color = if (colonVisible) Color(0xFF181B20) else Color.Transparent,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 1.dp),
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                            Text(
                                text = String.format("%02d", now.minute),
                                color = Color(0xFF181B20),
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }
            }

            // 中段下端の路線カラー太ライン（3.2dp）＋ 微細シャドウ
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.2.dp)
                    .background(lineColor)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFFB8C2CC))
            )

            // =========================================================================
            // 3. 下段メインコンテンツエリア（走行状態・サイクルに応じて3種類のビューを切替）
            //    - STOPPING_AT: 立体ホーム＆車両編成スライドイン図 (動画4 `2118 (1).mp4`)
            //    - BEFORE_DEPARTURE (2段モード): 全停車駅2段バー一覧 (動画1 `2116.mp4` 後半)
            //    - 標準: 直近7〜8駅進行バー + 所要分数 + 赤白シェブロン現在地 (動画1前半・動画2・動画3)
            // =========================================================================
            val bottomViewMode = when {
                phase == TrainLcdPhase.STOPPING_AT -> 2
                showTwoRowAllStations -> 1
                else -> 0
            }

            Crossfade(
                targetState = bottomViewMode,
                animationSpec = tween(durationMillis = 320),
                label = "bottomLcdContentCrossfade"
            ) { viewMode ->
                when (viewMode) {
                    2 -> {
                        TrainLcdPlatformCarView(
                            currentStop = targetStop,
                            trainLeg = trainLeg,
                            lineColor = lineColor,
                            carCount = carCount,
                            activeCarNo = displayCarNo,
                            langMode = langMode
                        )
                    }
                    1 -> {
                        TrainLcdTwoRowAllStationsView(
                            lcdState = lcdState,
                            lineColor = lineColor,
                            langMode = langMode
                        )
                    }
                    else -> {
                        TrainLcdSingleRowProgressView(
                            lcdState = lcdState,
                            trainLeg = trainLeg,
                            lineColor = lineColor,
                            langMode = langMode
                        )
                    }
                }
            }
        }
    }
}

/**
 * 駅ナンバリングバッジ（路線カラー太枠の角丸正方形または丸バッジ）
 */
@Composable
private fun TrainStationNumberBadge(
    linePrefix: String,
    stationDigits: String,
    lineColor: Color,
    isCircle: Boolean
) {
    val shape = if (isCircle) RoundedCornerShape(50) else RoundedCornerShape(5.dp)
    Box(
        modifier = Modifier
            .size(37.dp)
            .background(Color.White, shape)
            .border(3.3.dp, lineColor, shape),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = linePrefix,
                color = Color(0xFF111317),
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Text(
                text = stationDigits,
                color = Color(0xFF111317),
                fontSize = 13.5.sp,
                lineHeight = 13.5.sp,
                fontWeight = FontWeight.Black,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

/**
 * ビュー1: 直近7〜8駅の路線進行バー（右から左へ進行、縦書き漢字 ⇔ 斜め英語駅名、所要分数、赤白シェブロン現在位置）
 * 動画 `2116.mp4`(前半), `2117.mp4`, `2118.mp4` に完全準拠
 */
@Composable
private fun TrainLcdSingleRowProgressView(
    lcdState: TrainLcdResolvedState,
    trainLeg: RouteLeg,
    lineColor: Color,
    langMode: TrainLcdLangMode
) {
    val startIdx = lcdState.prevStopIndex.coerceIn(0, (lcdState.allStops.size - 1).coerceAtLeast(0))
    val windowStops = lcdState.allStops.drop(startIdx).take(8)
    val isEnglish = langMode == TrainLcdLangMode.ENGLISH

    // 走行中の赤白シェブロン点滅・微動アニメーション
    val infiniteTransition = rememberInfiniteTransition(label = "chevronPulse")
    val chevronPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "chevronPulseAnim"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(178.dp)
            .background(Color.White)
            .padding(top = 4.dp, bottom = 4.dp)
    ) {
        // 1. 駅名＆駅ナンバリング列（右端が index 0 = 前の駅/出発駅、左に向かって次の駅が並ぶ）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(94.dp)
                .padding(start = 34.dp, end = 10.dp)
                .clipToBounds()
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                // 画面左から右へ配置するため、windowStops(0..7) を逆順(7 downTo 0)で並べる
                val displaySlots = (7 downTo 0).map { slotIdx -> windowStops.getOrNull(slotIdx) }
                for ((colPos, stop) in displaySlots.withIndex()) {
                    val slotIdxFromRight = 7 - colPos
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (stop != null) {
                            val isDepartedOrigin = (slotIdxFromRight == 0) &&
                                (lcdState.phase == TrainLcdPhase.NEXT_STATION || lcdState.phase == TrainLcdPhase.ARRIVING_AT)
                            val textColor = when {
                                isDepartedOrigin -> Color(0xFF98A2B3)
                                stop.isAlightStation -> Color(0xFF0F172A)
                                else -> Color(0xFF111827)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                // 駅名エリア（日本語: 縦書き、英語: 斜め-58度回転）
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    if (isEnglish) {
                                        // 英語モード: 斜め回転テキスト (動画の斜め英語表記を忠実に再現)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(bottom = 4.dp),
                                            contentAlignment = Alignment.BottomCenter
                                        ) {
                                            Text(
                                                text = stop.romaji,
                                                color = textColor,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier
                                                    .offset(x = 8.dp, y = (-14).dp)
                                                    .graphicsLayer {
                                                        rotationZ = -58f
                                                    },
                                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                            )
                                        }
                                    } else {
                                        // 日本語モード: 縦書き漢字駅名
                                        VerticalJapaneseStationName(
                                            name = stop.kanji,
                                            color = textColor,
                                            isHighlighted = stop.isAlightStation,
                                            lineColor = lineColor,
                                            modifier = Modifier.padding(bottom = 2.dp)
                                        )
                                    }
                                }

                                // 駅ナンバリング (例: JA-25)
                                Text(
                                    text = stop.stationNumberCode,
                                    color = if (isDepartedOrigin) Color(0xFFA0AAB8) else Color(0xFF1E293B),
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        // 2. メタリック路線カラー進行バー（左先端 `< 分` / `< min` 矢印シェブロン ＋ 所要分数ボックス ＋ 赤白シェブロン現在地）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(25.dp)
                .padding(horizontal = 6.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val arrowTipW = 26.dp.toPx()

                // 左向き矢印先端を持つバーのPath
                val barPath = Path().apply {
                    moveTo(0f, h / 2f)
                    lineTo(arrowTipW, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h)
                    lineTo(arrowTipW, h)
                    close()
                }

                // 3D円筒メタリックグラデーション（上下が濃く、中央がハイライト）
                val metallicBrush = Brush.verticalGradient(
                    0.0f to darkenColor(lineColor, 0.28f),
                    0.25f to lineColor,
                    0.48f to lightenColor(lineColor, 0.35f),
                    0.72f to lineColor,
                    1.0f to darkenColor(lineColor, 0.32f)
                )
                drawPath(barPath, brush = metallicBrush)
                drawPath(
                    barPath,
                    color = darkenColor(lineColor, 0.4f),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // 左先端の「分 / min」ラベル
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .fillMaxHeight()
                    .padding(start = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isEnglish) "min" else "分",
                    color = Color.White,
                    fontSize = if (isEnglish) 9.5.sp else 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }

            // 各駅の位置に合わせた所要分数ボックスと、駅間の赤白シェブロン矢印
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 28.dp, end = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displaySlots = (7 downTo 0).map { slotIdx -> windowStops.getOrNull(slotIdx) }
                    for ((colPos, stop) in displaySlots.withIndex()) {
                        val slotIdxFromRight = 7 - colPos
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (stop != null) {
                                if (slotIdxFromRight == 0) {
                                    // 右端（出発駅・直前駅）: 白い小丸または空欄
                                    if (lcdState.phase == TrainLcdPhase.BEFORE_DEPARTURE) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(Color.White, RoundedCornerShape(50))
                                        )
                                    }
                                } else {
                                    // 次の駅以降: 白い角丸ボックス内に黒太字で所要分数 (例: 3, 5, 7...)
                                    Box(
                                        modifier = Modifier
                                            .width(24.dp)
                                            .height(18.dp)
                                            .background(
                                                color = if (stop.isAlightStation) Color(0xFFFFFBEB) else Color.White,
                                                shape = RoundedCornerShape(3.dp)
                                            )
                                            .border(
                                                width = if (stop.isAlightStation) 1.4.dp else 0.8.dp,
                                                color = if (stop.isAlightStation) Color(0xFFD97706) else Color(0xFFCBD5E1),
                                                shape = RoundedCornerShape(3.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${stop.minutesFromNow}",
                                            color = Color(0xFF111317),
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Black,
                                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 右端(slot 0)と次の駅(slot 1)の間を走る現在位置シェブロンインジケーター
                // - BEFORE_DEPARTURE: 白い三角 `<`
                // - NEXT_STATION: 赤白の2連シェブロン `<<` が GPS進行度に応じて右から左へ移動
                // - ARRIVING_AT: 赤白の2連シェブロン `<<` が次の駅(slot 1)の直前に到達
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val slotW = size.width / 8f
                    val slot0CenterX = size.width - slotW * 0.5f
                    val slot1CenterX = size.width - slotW * 1.5f
                    val cy = size.height / 2f

                    if (lcdState.phase == TrainLcdPhase.BEFORE_DEPARTURE) {
                        val midX = (slot0CenterX + slot1CenterX) / 2f
                        val triH = size.height * 0.58f
                        val triW = 9.dp.toPx()
                        val path = Path().apply {
                            moveTo(midX - triW / 2f, cy)
                            lineTo(midX + triW / 2f, cy - triH / 2f)
                            lineTo(midX + triW / 2f, cy + triH / 2f)
                            close()
                        }
                        drawPath(path, color = Color.White)
                    } else {
                        val frac = when (lcdState.phase) {
                            TrainLcdPhase.ARRIVING_AT -> 0.72f
                            else -> lcdState.segmentProgress.coerceIn(0.22f, 0.65f)
                        }
                        val pulseShift = (chevronPulse - 0.5f) * 2.5.dp.toPx()
                        val indicatorX = slot0CenterX - (slot0CenterX - slot1CenterX) * frac + pulseShift
                        drawRedWhiteDoubleChevron(
                            centerX = indicatorX,
                            centerY = cy,
                            barHeight = size.height
                        )
                    }
                }
            }
        }

        // 3. 下部: 乗換案内・降車駅案内 ＋ 右下注記テキスト
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左側: 次駅または表示区間内の乗換案内＆降車駅ガイド
            val nextStop = windowStops.getOrNull(1) ?: windowStops.firstOrNull()
            val transferTarget = windowStops.drop(1).firstOrNull { it.transferLines.isNotEmpty() } ?: nextStop
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Bottom
            ) {
                if (transferTarget != null && transferTarget.transferLines.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (tr in transferTarget.transferLines.take(2)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(Color.White, RoundedCornerShape(2.dp))
                                        .border(2.dp, tr.color, RoundedCornerShape(2.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tr.prefix,
                                        color = Color(0xFF111317),
                                        fontSize = 6.5.sp,
                                        fontWeight = FontWeight.Black,
                                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                    )
                                }
                                Text(
                                    text = if (isEnglish) tr.nameEn else tr.nameJp,
                                    color = Color(0xFF1E293B),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }

                // 降車駅・到着予定時刻バッジ
                val alightName = if (isEnglish) {
                    lcdState.allStops.getOrNull(lcdState.alightStopIndex)?.romaji ?: trainLeg.toName
                } else {
                    trainLeg.toName
                }
                val arrHmText = trainLeg.arrTime?.let { String.format("%02d:%02d", it.hour, it.minute) } ?: ""
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(lineColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                            .border(0.8.dp, lineColor, RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (isEnglish) "Alight: $alightName $arrHmText" else "降車駅: $alightName ${if (arrHmText.isNotEmpty()) "($arrHmText 着)" else ""}",
                            color = Color(0xFF0F172A),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }

            // 右下: 動画と完全一致する注記テキスト
            Text(
                text = if (isEnglish) {
                    "Time shown does not include transfer or waiting time."
                } else {
                    "のりかえ、待ち合わせ時間は含まれません。\n電車により多少時間は異なります。"
                },
                color = Color(0xFF334155),
                fontSize = 7.5.sp,
                lineHeight = 9.5.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

/**
 * ビュー2: 全停車駅2段バー一覧ビュー（動画1 `2116.mp4` 後半 00:20〜00:40 の2段路線バーを忠実に再現）
 */
@Composable
private fun TrainLcdTwoRowAllStationsView(
    lcdState: TrainLcdResolvedState,
    lineColor: Color,
    langMode: TrainLcdLangMode
) {
    val isEnglish = langMode == TrainLcdLangMode.ENGLISH
    val stops = lcdState.allStops
    val upperRowStops = stops.take(8)
    val lowerRowStops = stops.drop(8).take(8)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(178.dp)
            .background(Color.White)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 上段バー (1〜8駅目)
        CompactStationProgressRow(
            rowStops = upperRowStops,
            lineColor = lineColor,
            isEnglish = isEnglish,
            isFirstRow = true,
            modifier = Modifier.weight(1f)
        )

        // 下段バー (9〜16駅目)
        CompactStationProgressRow(
            rowStops = lowerRowStops,
            lineColor = lineColor,
            isEnglish = isEnglish,
            isFirstRow = false,
            modifier = Modifier.weight(1f)
        )

        // 最下段右端の注記
        Text(
            text = if (isEnglish) {
                "Time shown does not include transfer or waiting time."
            } else {
                "のりかえ、待ち合わせ時間は含まれません。電車により多少時間は異なります。"
            },
            color = Color(0xFF334155),
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun CompactStationProgressRow(
    rowStops: List<TrainLcdStopItem>,
    lineColor: Color,
    isEnglish: Boolean,
    isFirstRow: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 駅名列
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 26.dp, end = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            val slots = (7 downTo 0).map { idx -> rowStops.getOrNull(idx) }
            for (stop in slots) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (stop != null) {
                        if (isEnglish) {
                            Text(
                                text = stop.romaji,
                                color = Color(0xFF111827),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .offset(x = 6.dp, y = (-8).dp)
                                    .graphicsLayer { rotationZ = -55f },
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        } else {
                            VerticalJapaneseStationName(
                                name = stop.kanji,
                                color = Color(0xFF111827),
                                isHighlighted = stop.isAlightStation,
                                lineColor = lineColor,
                                compact = true
                            )
                        }
                    }
                }
            }
        }

        // コンパクトメタリックバー
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(17.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val arrowW = 18.dp.toPx()
                val path = Path().apply {
                    moveTo(0f, h / 2f)
                    lineTo(arrowW, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h)
                    lineTo(arrowW, h)
                    close()
                }
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        listOf(
                            darkenColor(lineColor, 0.25f),
                            lightenColor(lineColor, 0.3f),
                            lineColor,
                            darkenColor(lineColor, 0.3f)
                        )
                    )
                )
            }

            Box(
                modifier = Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                    .padding(start = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isEnglish) "min" else "分",
                    color = Color.White,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val slots = (7 downTo 0).map { idx -> rowStops.getOrNull(idx) }
                for ((colPos, stop) in slots.withIndex()) {
                    val idxFromRight = 7 - colPos
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (stop != null && !(isFirstRow && idxFromRight == 0)) {
                            Box(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height(13.dp)
                                    .background(Color.White, RoundedCornerShape(2.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${stop.minutesFromNow}",
                                    color = Color(0xFF111317),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * ビュー3: 「ただいま ○○」停車中・立体ホーム＆車両編成スライドイン図（動画4 `2118 (1).mp4` 完全再現）
 */
@Composable
private fun TrainLcdPlatformCarView(
    currentStop: TrainLcdStopItem,
    trainLeg: RouteLeg,
    lineColor: Color,
    carCount: Int,
    activeCarNo: Int,
    langMode: TrainLcdLangMode
) {
    val isEnglish = langMode == TrainLcdLangMode.ENGLISH
    val totalCars = carCount.coerceIn(4, 15)

    // 表示開始時に右から左へ電車が滑り込むスライドインアニメーション
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(currentStop.kanji) {
        entered = false
        delay(40L)
        entered = true
    }
    val slideOffsetFraction by animateFloatAsState(
        targetValue = if (entered) 0f else 0.65f,
        animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
        label = "trainSlideIn"
    )

    // 乗車号車のハイライト点滅
    val infiniteTransition = rememberInfiniteTransition(label = "carHighlightBlink")
    val highlightPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "carHighlightAnim"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(178.dp)
            .background(Color.White)
            .clipToBounds()
    ) {
        // 1. 3Dパースペクティブのホーム屋根・柱・ホーム床・黄色点字ブロック・線路をCanvas描画
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 上部: 立体的な台形ホーム屋根 (動画4の上部グレー構造物)
            val roofTopY = h * 0.08f
            val roofBottomY = h * 0.25f
            val roofPath = Path().apply {
                moveTo(w * 0.14f, roofTopY)
                lineTo(w * 0.86f, roofTopY)
                lineTo(w * 0.90f, roofBottomY)
                lineTo(w * 0.10f, roofBottomY)
                close()
            }
            drawPath(
                path = roofPath,
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFD0D6DF), Color(0xFFAEB7C4))
                )
            )
            // 屋根の下面シャドウ帯
            drawRect(
                color = Color(0xFF7B8492),
                topLeft = Offset(w * 0.10f, roofBottomY),
                size = Size(w * 0.80f, 5.dp.toPx())
            )

            // 中段: 3D台形ホーム床面
            val platTopY = h * 0.36f
            val platBottomY = h * 0.67f
            val platPath = Path().apply {
                moveTo(w * 0.08f, platTopY)
                lineTo(w * 0.92f, platTopY)
                lineTo(w * 0.96f, platBottomY)
                lineTo(w * 0.04f, platBottomY)
                close()
            }
            drawPath(
                path = platPath,
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFCFD6DF), Color(0xFFE6EAF0))
                )
            )

            // 屋根を支える柱（左右2本＋中央階段アイコン枠）
            val pillarW = 6.dp.toPx()
            drawRect(
                color = Color(0xFF8E97A4),
                topLeft = Offset(w * 0.22f, roofBottomY),
                size = Size(pillarW, platTopY - roofBottomY + 12.dp.toPx())
            )
            drawRect(
                color = Color(0xFF8E97A4),
                topLeft = Offset(w * 0.78f, roofBottomY),
                size = Size(pillarW, platTopY - roofBottomY + 12.dp.toPx())
            )

            // ホーム側面（厚み）
            val edgeH = 7.dp.toPx()
            drawRect(
                color = Color(0xFF8B94A3),
                topLeft = Offset(w * 0.04f, platBottomY),
                size = Size(w * 0.92f, edgeH)
            )

            // ホーム手前側の黄色い点字ブロック破線 (動画4と完全一致)
            val tactileY = platBottomY - 5.dp.toPx()
            drawLine(
                color = Color(0xFFFACC15),
                start = Offset(w * 0.06f, tactileY),
                end = Offset(w * 0.94f, tactileY),
                strokeWidth = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 6.dp.toPx()), 0f)
            )

            // 下段: 線路道床（ダークグレー）＋ レール＆枕木
            val trackTopY = platBottomY + edgeH
            val trackBottomY = h * 0.96f
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF2B3038), Color(0xFF424954), Color(0xFF22262C))
                ),
                topLeft = Offset(0f, trackTopY),
                size = Size(w, trackBottomY - trackTopY)
            )

            // 線路の2本のレールライン
            val rail1Y = trackTopY + (trackBottomY - trackTopY) * 0.32f
            val rail2Y = trackTopY + (trackBottomY - trackTopY) * 0.74f
            drawLine(
                color = Color(0xFF94A3B8),
                start = Offset(0f, rail1Y),
                end = Offset(w, rail1Y),
                strokeWidth = 1.5.dp.toPx()
            )
            drawLine(
                color = Color(0xFF94A3B8),
                start = Offset(0f, rail2Y),
                end = Offset(w, rail2Y),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        // ホーム上の階段・エスカレーター・降車案内ピクトグラム＆ラベル
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 62.dp, start = 48.dp, end = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 階段・エスカレーターピクトグラム
            Surface(
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color.White)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("🪜", fontSize = 10.sp)
                    Text(
                        text = if (isEnglish) "Stairs / Exit" else "階段・出口",
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }

            // 降車駅またはのりば案内バッジ
            if (currentStop.isAlightStation) {
                Surface(
                    color = Color(0xFFDC2626),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color.White)
                ) {
                    Text(
                        text = if (isEnglish) "Alight Here (${currentStop.romaji})" else "降車駅です（${currentStop.kanji}）",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            } else if (trainLeg.platform.isNotBlank()) {
                Surface(
                    color = lineColor,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color.White)
                ) {
                    Text(
                        text = trainLeg.platform,
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }

            // エレベーターピクトグラム
            Surface(
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color.White)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("🛗", fontSize = 10.sp)
                    Text(
                        text = if (isEnglish) "Elevator" else "エレベーター",
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }

        // 2. 下段線路上の車両編成ボックス (1号車〜N号車 + 左端の赤色進行方向矢印 ⬅)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 11.dp, start = 14.dp, end = 14.dp)
                .offset(x = (slideOffsetFraction * 260f).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 左端の赤い進行方向矢印 ⬅ (動画4の1号車左の赤矢印)
            Canvas(
                modifier = Modifier
                    .width(22.dp)
                    .height(22.dp)
                    .padding(end = 4.dp)
            ) {
                val w = size.width
                val h = size.height
                val arrowPath = Path().apply {
                    moveTo(0f, h * 0.5f)
                    lineTo(w * 0.55f, h * 0.12f)
                    lineTo(w * 0.55f, h * 0.34f)
                    lineTo(w, h * 0.34f)
                    lineTo(w, h * 0.66f)
                    lineTo(w * 0.55f, h * 0.66f)
                    lineTo(w * 0.55f, h * 0.88f)
                    close()
                }
                drawPath(arrowPath, color = Color.White, style = Stroke(width = 2.5.dp.toPx()))
                drawPath(arrowPath, color = Color(0xFFE11D48))
            }

            // 1号車 〜 totalCars号車
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (carNum in 1..totalCars) {
                    val isCurrentCar = (carNum == activeCarNo)
                    val isHighlighted = isCurrentCar && (highlightPhase > 0.35f)
                    val boxBg = if (isHighlighted) {
                        Brush.verticalGradient(listOf(lightenColor(lineColor, 0.15f), lineColor))
                    } else {
                        Brush.verticalGradient(listOf(Color(0xFFF8FAFC), Color(0xFFD8E0EA)))
                    }
                    val numColor = if (isHighlighted) Color.White else Color(0xFF111317)

                    Column(
                        modifier = Modifier
                            .width(if (totalCars > 10) 21.dp else 26.dp)
                            .height(28.dp)
                            .clip(
                                RoundedCornerShape(
                                    topStart = if (carNum == 1) 6.dp else 2.dp,
                                    topEnd = if (carNum == totalCars) 6.dp else 2.dp,
                                    bottomStart = 2.dp,
                                    bottomEnd = 2.dp
                                )
                            )
                            .background(boxBg)
                            .border(
                                width = if (isCurrentCar) 1.5.dp else 1.dp,
                                color = if (isCurrentCar) lineColor else Color(0xFF475569),
                                shape = RoundedCornerShape(
                                    topStart = if (carNum == 1) 6.dp else 2.dp,
                                    topEnd = if (carNum == totalCars) 6.dp else 2.dp,
                                    bottomStart = 2.dp,
                                    bottomEnd = 2.dp
                                )
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 車両上部の路線カラー帯
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(lineColor)
                        )
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$carNum",
                                color = numColor,
                                fontSize = if (totalCars > 10) 11.sp else 13.sp,
                                fontWeight = FontWeight.Black,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日本語縦書き駅名コンポーネント（長音「ー」の縦棒変換対応）
 */
@Composable
private fun VerticalJapaneseStationName(
    name: String,
    color: Color,
    isHighlighted: Boolean,
    lineColor: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val clean = name.removeSuffix("駅").trim()
    val chars = clean.toCharArray()
    val fontSize = when {
        compact && chars.size >= 5 -> 8.5.sp
        compact -> 10.sp
        chars.size >= 6 -> 10.5.sp
        chars.size == 5 -> 11.5.sp
        else -> 13.5.sp
    }
    val lineHeight = when {
        compact && chars.size >= 5 -> 9.sp
        compact -> 10.5.sp
        chars.size >= 6 -> 11.sp
        chars.size == 5 -> 12.5.sp
        else -> 14.5.sp
    }

    Column(
        modifier = modifier
            .then(
                if (isHighlighted && !compact) {
                    Modifier
                        .background(lineColor.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        for (ch in chars) {
            if (ch == 'ー' || ch == '－' || ch == '-') {
                Text(
                    text = "｜",
                    color = color,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.ExtraBold,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            } else {
                Text(
                    text = ch.toString(),
                    color = color,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.ExtraBold,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}

/**
 * 進行バー内の赤白2連シェブロン矢印 `<<` をCanvasに描画（動画2・動画3の赤白インジケーター完全再現）
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRedWhiteDoubleChevron(
    centerX: Float,
    centerY: Float,
    barHeight: Float
) {
    val h = barHeight * 0.76f
    val halfH = h / 2f
    val chevW = 8.dp.toPx()
    val thickness = 4.5.dp.toPx()

    // 外側白枠付きの赤い左向きシェブロン1つ目（左側）
    val x1 = centerX - 3.dp.toPx()
    val chev1 = Path().apply {
        moveTo(x1 - chevW / 2f, centerY)
        lineTo(x1 + chevW / 2f, centerY - halfH)
        lineTo(x1 + chevW / 2f + thickness, centerY - halfH)
        lineTo(x1 - chevW / 2f + thickness, centerY)
        lineTo(x1 + chevW / 2f + thickness, centerY + halfH)
        lineTo(x1 + chevW / 2f, centerY + halfH)
        close()
    }

    // 2つ目（右側、白シェブロン＋赤シェブロン）
    val x2 = centerX + 4.dp.toPx()
    val chev2 = Path().apply {
        moveTo(x2 - chevW / 2f, centerY)
        lineTo(x2 + chevW / 2f, centerY - halfH)
        lineTo(x2 + chevW / 2f + thickness, centerY - halfH)
        lineTo(x2 - chevW / 2f + thickness, centerY)
        lineTo(x2 + chevW / 2f + thickness, centerY + halfH)
        lineTo(x2 + chevW / 2f, centerY + halfH)
        close()
    }

    drawPath(chev2, color = Color.White)
    drawPath(chev1, color = Color.White, style = Stroke(width = 1.8.dp.toPx()))
    drawPath(chev1, color = Color(0xFFE11D48))
}

// ============================================================================
// GPS位置・時刻表に基づく鉄道走行状態リゾルバー
// ============================================================================

fun resolveTrainLcdState(
    trainLeg: RouteLeg,
    guidance: Guidance?,
    location: Location?,
    now: LocalDateTime,
    linePrefix: String
): TrainLcdResolvedState {
    val legStops = if (trainLeg.intermediateStops.size >= 2) {
        trainLeg.intermediateStops
    } else {
        listOf(trainLeg.fromName, trainLeg.toName)
    }

    val fullStops = if (trainLeg.tripRemainingStops.size > legStops.size) {
        trainLeg.tripRemainingStops
    } else {
        legStops
    }

    val fullKanas = if (trainLeg.tripRemainingKanas.size == fullStops.size) {
        trainLeg.tripRemainingKanas
    } else {
        trainLeg.stopKanas
    }

    val fullBaseMins = if (trainLeg.tripRemainingMinutes.size == fullStops.size) {
        trainLeg.tripRemainingMinutes
    } else if (trainLeg.stopArrMinutes.size == fullStops.size) {
        trainLeg.stopArrMinutes
    } else {
        val totalMin = if (trainLeg.depTime != null && trainLeg.arrTime != null) {
            Duration.between(trainLeg.depTime, trainLeg.arrTime).toMinutes().toInt().coerceAtLeast(legStops.size)
        } else {
            (fullStops.size - 1) * 2
        }
        fullStops.indices.map { idx ->
            if (idx == 0) 0 else ((idx.toDouble() / (legStops.size - 1).coerceAtLeast(1)) * totalMin).roundToInt().coerceAtLeast(idx * 2)
        }
    }

    val coords = trainLeg.stopCoords
    val alightIdx = (legStops.size - 1).coerceAtLeast(1)

    // GPS位置から現在の区間 [prevIdx, nextIdx] と進行度・フェーズを判定
    var resolvedPhase = TrainLcdPhase.NEXT_STATION
    var prevIdx = 0
    var targetIdx = 1.coerceAtMost(fullStops.lastIndex)
    var segProgress = 0.35f
    var gpsMatched = false

    if (location != null && coords.size >= 2) {
        val lat = location.latitude
        val lon = location.longitude
        val stationDists = coords.map { (sLat, sLon) -> calcDistanceMeters(lat, lon, sLat, sLon) }

        // 各駅間セグメントへの垂線距離と射影パラメータ t in [0, 1] を計算
        var bestSeg = 0
        var bestSegDist = Double.MAX_VALUE
        var bestT = 0.0

        for (i in 0 until coords.size - 1) {
            val (lat1, lon1) = coords[i]
            val (lat2, lon2) = coords[i + 1]
            val cosLat = cos(Math.toRadians(lat1))
            val ax = 0.0
            val ay = 0.0
            val bx = (lon2 - lon1) * cosLat * 111320.0
            val by = (lat2 - lat1) * 110540.0
            val px = (lon - lon1) * cosLat * 111320.0
            val py = (lat - lat1) * 110540.0

            val abLenSq = bx * bx + by * by
            val t = if (abLenSq > 1.0) {
                ((px * bx + py * by) / abLenSq).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val projX = ax + t * bx
            val projY = ay + t * by
            val perpDist = hypot(px - projX, py - projY)

            if (perpDist < bestSegDist) {
                bestSegDist = perpDist
                bestSeg = i
                bestT = t
            }
        }

        if (bestSegDist <= 2500.0) {
            gpsMatched = true
            val dPrev = stationDists[bestSeg]
            val dNext = stationDists[bestSeg + 1]
            val isWaitingGuidance = guidance?.kind == GuidanceKind.WAIT

            if (bestSeg == 0 && dPrev <= 150) {
                val beforeDep = isWaitingGuidance ||
                    (trainLeg.depTime != null && now.isBefore(trainLeg.depTime.plusSeconds(15))) ||
                    bestT < 0.08
                if (beforeDep) {
                    resolvedPhase = TrainLcdPhase.BEFORE_DEPARTURE
                    prevIdx = 0
                    targetIdx = 1.coerceAtMost(fullStops.lastIndex)
                    segProgress = 0f
                } else {
                    resolvedPhase = TrainLcdPhase.NEXT_STATION
                    prevIdx = 0
                    targetIdx = 1.coerceAtMost(fullStops.lastIndex)
                    segProgress = bestT.toFloat().coerceIn(0.15f, 0.7f)
                }
            } else if (dNext <= 135) {
                // 次の駅に到着・停車中 ("ただいま")
                resolvedPhase = TrainLcdPhase.STOPPING_AT
                prevIdx = (bestSeg + 1).coerceAtMost(fullStops.lastIndex)
                targetIdx = prevIdx
                segProgress = 0f
            } else if (bestSeg >= 1 && dPrev <= 135) {
                // 前の駅に停車中 ("ただいま")
                resolvedPhase = TrainLcdPhase.STOPPING_AT
                prevIdx = bestSeg
                targetIdx = bestSeg
                segProgress = 0f
            } else if (dNext <= 480 || (bestT >= 0.74 && dNext <= 850)) {
                // 次の駅に接近中 ("まもなく")
                resolvedPhase = TrainLcdPhase.ARRIVING_AT
                prevIdx = bestSeg
                targetIdx = (bestSeg + 1).coerceAtMost(fullStops.lastIndex)
                segProgress = bestT.toFloat().coerceIn(0.75f, 0.95f)
            } else {
                // 駅間を走行中 ("次は")
                resolvedPhase = TrainLcdPhase.NEXT_STATION
                prevIdx = bestSeg
                targetIdx = (bestSeg + 1).coerceAtMost(fullStops.lastIndex)
                segProgress = bestT.toFloat().coerceIn(0.18f, 0.72f)
            }
        }
    }

    // GPSがルート外または未取得の場合は時刻表と現在時刻から精密に特定
    if (!gpsMatched) {
        val depTime = trainLeg.depTime
        if (guidance?.kind == GuidanceKind.WAIT || (depTime != null && now.isBefore(depTime))) {
            resolvedPhase = TrainLcdPhase.BEFORE_DEPARTURE
            prevIdx = 0
            targetIdx = 1.coerceAtMost(fullStops.lastIndex)
            segProgress = 0f
        } else if (depTime != null && fullStops.size >= 2) {
            val elapsedSec = Duration.between(depTime, now).seconds.coerceAtLeast(0L)
            val stopSecs = fullBaseMins.map { it * 60L }
            var foundSeg = 0
            for (i in 0 until (legStops.size - 1).coerceAtLeast(1)) {
                val s0 = stopSecs.getOrElse(i) { i * 120L }
                val s1 = stopSecs.getOrElse(i + 1) { (i + 1) * 120L }.coerceAtLeast(s0 + 60L)
                if (elapsedSec <= s1 || i == legStops.size - 2) {
                    foundSeg = i
                    val segDur = (s1 - s0).coerceAtLeast(60L)
                    val inSeg = (elapsedSec - s0).coerceIn(0L, segDur)
                    val ratio = (inSeg.toFloat() / segDur.toFloat()).coerceIn(0f, 1f)

                    when {
                        foundSeg == 0 && inSeg <= 15L -> {
                            resolvedPhase = TrainLcdPhase.BEFORE_DEPARTURE
                            prevIdx = 0
                            targetIdx = 1
                            segProgress = 0f
                        }
                        inSeg <= 20L && foundSeg >= 1 -> {
                            resolvedPhase = TrainLcdPhase.STOPPING_AT
                            prevIdx = foundSeg
                            targetIdx = foundSeg
                            segProgress = 0f
                        }
                        segDur - inSeg <= 20L -> {
                            resolvedPhase = TrainLcdPhase.STOPPING_AT
                            prevIdx = foundSeg + 1
                            targetIdx = foundSeg + 1
                            segProgress = 0f
                        }
                        segDur - inSeg <= 50L || ratio >= 0.76f -> {
                            resolvedPhase = TrainLcdPhase.ARRIVING_AT
                            prevIdx = foundSeg
                            targetIdx = foundSeg + 1
                            segProgress = ratio
                        }
                        else -> {
                            resolvedPhase = TrainLcdPhase.NEXT_STATION
                            prevIdx = foundSeg
                            targetIdx = foundSeg + 1
                            segProgress = ratio
                        }
                    }
                    break
                }
            }
        }
    }

    // 現在位置からの経過分数を差し引き、各駅までの残り所要分数を算出
    val prevBaseMin = fullBaseMins.getOrElse(prevIdx) { prevIdx * 2 }
    val nextBaseMin = fullBaseMins.getOrElse(targetIdx) { prevBaseMin + 2 }
    val currentElapsedMin = prevBaseMin + ((nextBaseMin - prevBaseMin) * segProgress).roundToInt()

    // 駅ナンバリングの連番基準を解決
    val firstStDigits = resolveStationNumberDigits(linePrefix, fullStops.first(), fallbackNum = 26).toIntOrNull() ?: 26
    val secondStDigits = if (fullStops.size >= 2) {
        resolveStationNumberDigits(linePrefix, fullStops[1], fallbackNum = (firstStDigits - 1).coerceAtLeast(1)).toIntOrNull()
            ?: (firstStDigits - 1)
    } else firstStDigits - 1
    val stepDir = if (secondStDigits < firstStDigits) -1 else 1

    val stopItems = fullStops.mapIndexed { idx, rawName ->
        val kanji = rawName.removeSuffix("駅").trim()
        val rawKana = fullKanas.getOrElse(idx) { "" }
        val hiragana = resolveStationHiragana(kanji, rawKana)
        val romaji = resolveStationRomaji(kanji, hiragana)
        val fallbackNum = (firstStDigits + idx * stepDir).coerceIn(1, 99)
        val digits = resolveStationNumberDigits(linePrefix, kanji, fallbackNum = fallbackNum)
        val rawMin = fullBaseMins.getOrElse(idx) { idx * 2 } - currentElapsedMin
        val minFromNow = if (idx <= prevIdx) 0 else rawMin.coerceAtLeast(idx - prevIdx)

        TrainLcdStopItem(
            index = idx,
            kanji = kanji,
            hiragana = hiragana,
            romaji = romaji,
            stationNumberCode = "$linePrefix-$digits",
            stationNumberDigits = digits,
            minutesFromNow = minFromNow,
            isAlightStation = (idx == alightIdx),
            transferLines = resolveStationTransferBadges(kanji, linePrefix)
        )
    }

    return TrainLcdResolvedState(
        phase = resolvedPhase,
        prevStopIndex = prevIdx,
        targetStopIndex = targetIdx,
        segmentProgress = segProgress,
        allStops = stopItems,
        alightStopIndex = alightIdx
    )
}

// ============================================================================
// 路線カラー・路線記号・種別・多言語（漢字/ひらがな/英語）・駅ナンバリング辞書
// ============================================================================

fun resolveTrainLineTheme(
    lineName: String,
    operatorLabel: String,
    rawColorLong: Long
): TrainLineVisualTheme {
    val cleanLine = lineName.trim()
    val hasSpecificDbColor = rawColorLong != 0L && rawColorLong != 0xFF2563EBL && rawColorLong != 0xFF0284C7L

    val (officialColor, prefix, isCircle) = when {
        cleanLine.contains("埼京") || cleanLine.contains("川越") -> Triple(Color(0xFF00AC9A), "JA", false)
        cleanLine.contains("中央線") || cleanLine.contains("中央特快") || cleanLine.contains("青梅") || cleanLine.contains("五日市") ->
            Triple(Color(0xFFF15A22), "JC", false)
        cleanLine.contains("総武") && !cleanLine.contains("快速") -> Triple(Color(0xFFFFD400), "JB", false)
        cleanLine.contains("総武快速") || cleanLine.contains("横須賀") -> Triple(Color(0xFF0068B7), "JO", false)
        cleanLine.contains("山手") -> Triple(Color(0xFF9ACD32), "JY", false)
        cleanLine.contains("京浜東北") || cleanLine.contains("根岸") -> Triple(Color(0xFF00B2E5), "JK", false)
        cleanLine.contains("湘南新宿") -> Triple(Color(0xFFE21F26), "JS", false)
        cleanLine.contains("東海道") || cleanLine.contains("上野東京") || cleanLine.contains("宇都宮") || cleanLine.contains("高崎") ->
            Triple(Color(0xFFF68B1E), "JT", false)
        cleanLine.contains("京葉") -> Triple(Color(0xFFC9252F), "JE", false)
        cleanLine.contains("武蔵野") -> Triple(Color(0xFFF15A22), "JM", false)
        cleanLine.contains("横浜線") || cleanLine.contains("八高") -> Triple(Color(0xFF80C241), "JH", false)
        cleanLine.contains("南武") -> Triple(Color(0xFFFFD400), "JN", false)
        cleanLine.contains("常磐") && cleanLine.contains("各") -> Triple(Color(0xFF00B261), "JL", false)
        cleanLine.contains("常磐") -> Triple(Color(0xFF00B261), "JJ", false)
        cleanLine.contains("鶴見") -> Triple(Color(0xFFFFD400), "JI", false)
        cleanLine.contains("京王井の頭") || cleanLine.contains("井の頭") -> Triple(Color(0xFF1D2088), "IN", true)
        cleanLine.contains("京王") -> Triple(Color(0xFFDD0077), "KO", true)
        cleanLine.contains("小田急") && cleanLine.contains("江ノ島") -> Triple(Color(0xFF007ED5), "OE", true)
        cleanLine.contains("小田急") && cleanLine.contains("多摩") -> Triple(Color(0xFF007ED5), "OT", true)
        cleanLine.contains("小田急") -> Triple(Color(0xFF007ED5), "OH", true)
        cleanLine.contains("西武池袋") || cleanLine.contains("西武秩父") || cleanLine.contains("西武有楽町") || cleanLine.contains("豊島") ->
            Triple(Color(0xFFEF7A00), "SI", false)
        cleanLine.contains("西武新宿") || cleanLine.contains("拝島") || cleanLine.contains("西武園") || cleanLine.contains("国分寺") || cleanLine.contains("西武") ->
            Triple(Color(0xFF00A0C6), "SS", false)
        cleanLine.contains("東武東上") -> Triple(Color(0xFF004098), "TJ", false)
        cleanLine.contains("東武") -> Triple(Color(0xFF0F6CC3), "TS", false)
        cleanLine.contains("東急東横") -> Triple(Color(0xFFDA0442), "TY", false)
        cleanLine.contains("東急田園都市") -> Triple(Color(0xFF20A288), "DT", false)
        cleanLine.contains("東急目黒") -> Triple(Color(0xFF009CD2), "MG", false)
        cleanLine.contains("東急大井町") -> Triple(Color(0xFFF18C43), "OM", false)
        cleanLine.contains("東急池上") -> Triple(Color(0xFFEE86A7), "IK", false)
        cleanLine.contains("京急") -> Triple(Color(0xFFE5002D), "KK", true)
        cleanLine.contains("京成") -> Triple(Color(0xFF005AAA), "KS", true)
        cleanLine.contains("銀座") -> Triple(Color(0xFFFF9500), "G", true)
        cleanLine.contains("丸ノ内") -> Triple(Color(0xFFF62E36), "M", true)
        cleanLine.contains("日比谷") -> Triple(Color(0xFFB5B5AC), "H", true)
        cleanLine.contains("東西") -> Triple(Color(0xFF009BBF), "T", true)
        cleanLine.contains("千代田") -> Triple(Color(0xFF00BB85), "C", true)
        cleanLine.contains("有楽町") -> Triple(Color(0xFFC1A470), "Y", true)
        cleanLine.contains("半蔵門") -> Triple(Color(0xFF8F76D6), "Z", true)
        cleanLine.contains("南北") -> Triple(Color(0xFF00AC9B), "N", true)
        cleanLine.contains("副都心") -> Triple(Color(0xFF9C5E31), "F", true)
        cleanLine.contains("浅草") -> Triple(Color(0xFFE85298), "A", true)
        cleanLine.contains("三田") -> Triple(Color(0xFF0079C2), "I", true)
        cleanLine.contains("都営新宿") || (cleanLine.contains("新宿線") && operatorLabel.contains("都営")) ->
            Triple(Color(0xFF6CBB5A), "S", true)
        cleanLine.contains("大江戸") -> Triple(Color(0xFFB6007A), "E", true)
        cleanLine.contains("多摩モノレール") || cleanLine.contains("モノレール") -> Triple(Color(0xFFEC6800), "TT", false)
        cleanLine.contains("りんかい") -> Triple(Color(0xFF004098), "R", false)
        cleanLine.contains("つくばエクスプレス") -> Triple(Color(0xFF003B82), "TX", false)
        else -> Triple(Color(0xFF00AC9A), "JC", false)
    }

    val finalColor = if (hasSpecificDbColor) Color(rawColorLong) else officialColor
    return TrainLineVisualTheme(
        lineColor = finalColor,
        linePrefix = prefix,
        isCircleBadge = isCircle
    )
}

fun resolveTrainTypeDisplay(
    rawType: String,
    lineColor: Color
): Triple<String, String, Color> {
    val t = rawType.trim()
    return when {
        t.isEmpty() || t == "普通" || t == "各停" || t == "各駅停車" ->
            Triple("各駅停車", "Local", lineColor)
        t.contains("中央特快") ->
            Triple("中央特快", "Chūō Spec. Rapid", Color(0xFF2563EB))
        t.contains("青梅特快") ->
            Triple("青梅特快", "Ōme Spec. Rapid", Color(0xFF16A34A))
        t.contains("通勤特快") ->
            Triple("通勤特快", "Comm. Spec. Rapid", Color(0xFFDB2777))
        t.contains("特別快速") || t.contains("特快") ->
            Triple("特別快速", "Special Rapid", Color(0xFFEA580C))
        t.contains("通勤快速") ->
            Triple("通勤快速", "Commuter Rapid", Color(0xFF7C3AED))
        t.contains("快速急行") || t.contains("快急") ->
            Triple("快速急行", "Rapid Express", Color(0xFFEA580C))
        t.contains("快速") ->
            Triple("快速", "Rapid", Color(0xFFDC2626))
        t.contains("準特急") ->
            Triple("準特急", "Semi-Ltd. Exp.", Color(0xFFEA580C))
        t.contains("特急") ->
            Triple("特急", "Limited Exp.", Color(0xFFDC2626))
        t.contains("急行") ->
            Triple("急行", "Express", Color(0xFFE11D48))
        t.contains("区間急行") ->
            Triple("区間急行", "Section Exp.", Color(0xFF16A34A))
        t.contains("準急") ->
            Triple("準急", "Semi-Express", Color(0xFF2563EB))
        else -> Triple(t, "Local", lineColor)
    }
}

/**
 * 駅名とDBのかな（または辞書）から正確なひらがな表記を取得
 */
fun resolveStationHiragana(kanjiName: String, dbKana: String): String {
    val cleanKanji = kanjiName.removeSuffix("駅").trim()
    if (dbKana.isNotBlank()) {
        return katakanaToHiragana(dbKana.trim())
    }
    stationHiraganaMap[cleanKanji]?.let { return it }
    // 前方一致・部分一致の合成フォールバック
    for ((k, v) in stationHiraganaMap) {
        if (cleanKanji.startsWith(k) && cleanKanji.length > k.length) {
            val rest = cleanKanji.removePrefix(k)
            val restHira = stationHiraganaMap[rest] ?: rest
            return "$v$restHira"
        }
    }
    return katakanaToHiragana(cleanKanji)
}

/**
 * 駅名とひらがな読みから、JR東日本・首都圏鉄道準拠のヘボン式ローマ字表記を取得
 */
fun resolveStationRomaji(kanjiName: String, hiragana: String = ""): String {
    val cleanKanji = kanjiName.removeSuffix("駅").trim()
    stationExactRomajiMap[cleanKanji]?.let { return it }

    val hira = if (hiragana.isNotBlank()) katakanaToHiragana(hiragana) else resolveStationHiragana(cleanKanji, "")
    if (hira.isNotBlank() && hira.any { it in 'ぁ'..'ゖ' }) {
        return hiraganaToHepburnRomaji(hira, cleanKanji)
    }
    return cleanKanji
}

private fun katakanaToHiragana(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
        if (ch in 'ァ'..'ヶ') {
            sb.append((ch.code - 0x60).toChar())
        } else {
            sb.append(ch)
        }
    }
    return sb.toString()
}

/**
 * 任意のひらがな駅名をヘボン式ローマ字（JR東日本のハイフン・大文字規則対応）に自動変換
 */
private fun hiraganaToHepburnRomaji(hira: String, originalKanji: String): String {
    // 冠称（新・北・南・東・西・武蔵・相模・高麗・多摩・京王・小田急・西武・東武など）のハイフン分割
    val prefixes = listOf(
        "むさし" to "Musashi-",
        "さがみ" to "Sagami-",
        "けいおう" to "Keiō-",
        "おだきゅう" to "Odakyū-",
        "せいぶ" to "Seibu-",
        "とうぶ" to "Tōbu-",
        "ひがし" to "Higashi-",
        "にし" to "Nishi-",
        "みなみ" to "Minami-",
        "きた" to "Kita-",
        "しん" to "Shin-",
        "なか" to "Naka-"
    )
    val kanjiPrefixes = setOf("武蔵", "相模", "京王", "小田急", "西武", "東武", "東", "西", "南", "北", "新", "中")
    for ((hPref, rPref) in prefixes) {
        if (hira.startsWith(hPref) && hira.length > hPref.length + 1 && kanjiPrefixes.any { originalKanji.startsWith(it) && originalKanji.length > it.length }) {
            val remainder = hira.removePrefix(hPref)
            return rPref + convertPureHiraganaToRomaji(remainder).replaceFirstChar { it.uppercase() }
        }
    }
    return convertPureHiraganaToRomaji(hira).replaceFirstChar { it.uppercase() }
}

private fun convertPureHiraganaToRomaji(hira: String): String {
    val digraphs = mapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo"
    )
    val monographs = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'ゐ' to "i", 'ゑ' to "e", 'を' to "o", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o"
    )

    val tokens = ArrayList<String>()
    var i = 0
    while (i < hira.length) {
        if (hira[i] == 'っ') {
            tokens.add("SOKUON")
            i++
            continue
        }
        if (i + 1 < hira.length) {
            val pair = hira.substring(i, i + 2)
            val dg = digraphs[pair]
            if (dg != null) {
                tokens.add(dg)
                i += 2
                continue
            }
        }
        val mg = monographs[hira[i]] ?: hira[i].toString()
        tokens.add(mg)
        i++
    }

    val sb = StringBuilder()
    for (k in tokens.indices) {
        val cur = tokens[k]
        val next = tokens.getOrNull(k + 1)
        if (cur == "SOKUON") {
            if (next != null && next.isNotEmpty()) {
                if (next.startsWith("ch")) {
                    sb.append('t')
                } else {
                    sb.append(next[0])
                }
            }
        } else if (cur == "n" && next != null && next.isNotEmpty() && (next[0] == 'b' || next[0] == 'm' || next[0] == 'p')) {
            sb.append('m')
        } else {
            sb.append(cur)
        }
    }

    // 長音（ou, oo -> o / uu -> u）をヘボン式で簡潔に整形
    return sb.toString()
        .replace("ou", "o")
        .replace("oo", "o")
        .replace("uu", "u")
}

// 首都圏主要駅の正確な駅番号マップ（路線記号ごと）
private val stationNumberExactMap: Map<String, Map<String, Int>> = mapOf(
    "JA" to mapOf(
        "大崎" to 8, "恵比寿" to 9, "渋谷" to 10, "新宿" to 11, "池袋" to 12,
        "板橋" to 13, "十条" to 14, "赤羽" to 15, "北赤羽" to 16, "浮間舟渡" to 17,
        "戸田公園" to 18, "戸田" to 19, "北戸田" to 20, "武蔵浦和" to 21, "中浦和" to 22,
        "南与野" to 23, "与野本町" to 24, "北与野" to 25, "大宮" to 26, "新木場" to 1,
        "東雲" to 2, "国際展示場" to 3, "東京テレポート" to 4, "天王洲アイル" to 5, "品川シーサイド" to 6, "大井町" to 7
    ),
    "JC" to mapOf(
        "東京" to 1, "神田" to 2, "御茶ノ水" to 3, "四ツ谷" to 4, "新宿" to 5,
        "中野" to 6, "高円寺" to 7, "阿佐ケ谷" to 8, "荻窪" to 9, "西荻窪" to 10,
        "吉祥寺" to 11, "三鷹" to 12, "武蔵境" to 13, "東小金井" to 14, "武蔵小金井" to 15,
        "国分寺" to 16, "西国分寺" to 17, "国立" to 18, "立川" to 19, "日野" to 20,
        "豊田" to 21, "八王子" to 22, "西八王子" to 23, "高尾" to 24,
        "西立川" to 51, "東中神" to 52, "中神" to 53, "昭島" to 54, "拝島" to 55,
        "牛浜" to 56, "福生" to 57, "羽村" to 58, "小作" to 59, "河辺" to 60,
        "東青梅" to 61, "青梅" to 62, "奥多摩" to 74,
        "熊川" to 81, "東秋留" to 82, "秋川" to 83, "武蔵引田" to 84, "武蔵増戸" to 85, "武蔵五日市" to 86
    ),
    "JB" to mapOf(
        "三鷹" to 1, "吉祥寺" to 2, "西荻窪" to 3, "荻窪" to 4, "阿佐ケ谷" to 5,
        "高円寺" to 6, "中野" to 7, "東中野" to 8, "大久保" to 9, "新宿" to 10,
        "代々木" to 11, "千駄ケ谷" to 12, "信濃町" to 13, "四ツ谷" to 14, "市ケ谷" to 15,
        "飯田橋" to 16, "水道橋" to 17, "御茶ノ水" to 18, "秋葉原" to 19, "浅草橋" to 20,
        "両国" to 21, "錦糸町" to 22, "亀戸" to 23, "平井" to 24, "新小岩" to 25,
        "小岩" to 26, "市川" to 27, "本八幡" to 28, "下総中山" to 29, "西船橋" to 30,
        "船橋" to 31, "東船橋" to 32, "津田沼" to 33, "幕張本郷" to 34, "幕張" to 35,
        "新検見川" to 36, "稲毛" to 37, "西千葉" to 38, "千葉" to 39
    ),
    "JY" to mapOf(
        "東京" to 1, "神田" to 2, "秋葉原" to 3, "御徒町" to 4, "上野" to 5,
        "鶯谷" to 6, "日暮里" to 7, "西日暮里" to 8, "田端" to 9, "駒込" to 10,
        "巣鴨" to 11, "大塚" to 12, "池袋" to 13, "目白" to 14, "高田馬場" to 15,
        "新大久保" to 16, "新宿" to 17, "代々木" to 18, "原宿" to 19, "渋谷" to 20,
        "恵比寿" to 21, "目黒" to 22, "五反田" to 23, "大崎" to 24, "品川" to 25,
        "高輪ゲートウェイ" to 26, "田町" to 27, "浜松町" to 28, "新橋" to 29, "有楽町" to 30
    ),
    "JH" to mapOf(
        "東神奈川" to 13, "大口" to 14, "菊名" to 15, "新横浜" to 16, "小机" to 17,
        "鴨居" to 18, "中山" to 19, "十日市場" to 20, "長津田" to 21, "成瀬" to 22,
        "町田" to 23, "古淵" to 24, "淵野辺" to 25, "矢部" to 26, "相模原" to 27,
        "橋本" to 28, "相原" to 29, "八王子みなみ野" to 30, "片倉" to 31, "八王子" to 32
    ),
    "JN" to mapOf(
        "川崎" to 1, "武蔵小杉" to 7, "武蔵溝ノ口" to 10, "登戸" to 14, "稲田堤" to 16,
        "府中本町" to 20, "分倍河原" to 21, "谷保" to 23, "立川" to 26
    ),
    "JM" to mapOf(
        "府中本町" to 35, "北府中" to 34, "西国分寺" to 33, "新小平" to 32, "新秋津" to 31,
        "東所沢" to 30, "新座" to 29, "北朝霞" to 28, "西浦和" to 27, "武蔵浦和" to 26,
        "南浦和" to 25, "東浦和" to 24, "東川口" to 23, "南越谷" to 22, "西船橋" to 10
    ),
    "KO" to mapOf(
        "新宿" to 1, "笹塚" to 4, "明大前" to 6, "下高井戸" to 7, "桜上水" to 8,
        "千歳烏山" to 12, "仙川" to 13, "つつじヶ丘" to 14, "調布" to 18, "東府中" to 23,
        "府中" to 24, "分倍河原" to 25, "中河原" to 26, "聖蹟桜ヶ丘" to 27, "百草園" to 28,
        "高幡不動" to 29, "南平" to 30, "平山城址公園" to 31, "長沼" to 32, "北野" to 33,
        "京王八王子" to 34, "京王片倉" to 48, "山田" to 49, "めじろ台" to 50, "狭間" to 51,
        "高尾" to 52, "高尾山口" to 53, "京王多摩センター" to 41, "南大沢" to 43, "橋本" to 45
    ),
    "OH" to mapOf(
        "新宿" to 1, "代々木上原" to 5, "下北沢" to 7, "経堂" to 11, "成城学園前" to 14,
        "登戸" to 18, "新百合ヶ丘" to 23, "町田" to 27, "相模大野" to 28, "海老名" to 32,
        "本厚木" to 34, "小田原" to 47
    )
)

fun resolveStationNumberDigits(linePrefix: String, stationKanji: String, fallbackNum: Int): String {
    val clean = stationKanji.removeSuffix("駅").trim()
    val num = stationNumberExactMap[linePrefix]?.get(clean)
        ?: stationNumberExactMap.values.firstNotNullOfOrNull { it[clean] }
        ?: fallbackNum.coerceIn(1, 99)
    return String.format("%02d", num)
}

// 主要駅の乗換路線バッジ情報
fun resolveStationTransferBadges(stationKanji: String, currentPrefix: String): List<TrainTransferBadgeInfo> {
    val clean = stationKanji.removeSuffix("駅").trim()
    val rawList = when (clean) {
        "武蔵浦和" -> listOf(
            TrainTransferBadgeInfo("JM", "武蔵野線", "Musashino Line", Color(0xFFF15A22))
        )
        "赤羽" -> listOf(
            TrainTransferBadgeInfo("JK", "京浜東北線", "Keihin-Tōhoku Line", Color(0xFF00B2E5)),
            TrainTransferBadgeInfo("JU", "宇都宮・高崎線", "Utsunomiya / Takasaki Line", Color(0xFFF68B1E))
        )
        "大宮" -> listOf(
            TrainTransferBadgeInfo("JK", "京浜東北線", "Keihin-Tōhoku Line", Color(0xFF00B2E5)),
            TrainTransferBadgeInfo("JU", "宇都宮・高崎線", "Utsunomiya / Takasaki Line", Color(0xFFF68B1E))
        )
        "池袋" -> listOf(
            TrainTransferBadgeInfo("JY", "山手線", "Yamanote Line", Color(0xFF9ACD32)),
            TrainTransferBadgeInfo("M", "丸ノ内線", "Marunouchi Line", Color(0xFFF62E36))
        )
        "新宿" -> listOf(
            TrainTransferBadgeInfo("JC", "中央線", "Chūō Line", Color(0xFFF15A22)),
            TrainTransferBadgeInfo("JY", "山手線", "Yamanote Line", Color(0xFF9ACD32)),
            TrainTransferBadgeInfo("KO", "京王線", "Keiō Line", Color(0xFFDD0077))
        )
        "渋谷" -> listOf(
            TrainTransferBadgeInfo("JY", "山手線", "Yamanote Line", Color(0xFF9ACD32)),
            TrainTransferBadgeInfo("G", "銀座線", "Ginza Line", Color(0xFFFF9500))
        )
        "立川" -> listOf(
            TrainTransferBadgeInfo("JC", "中央線", "Chūō Line", Color(0xFFF15A22)),
            TrainTransferBadgeInfo("JN", "南武線", "Nambu Line", Color(0xFFFFD400)),
            TrainTransferBadgeInfo("TT", "多摩モノレール", "Tama Monorail", Color(0xFFEC6800))
        )
        "八王子" -> listOf(
            TrainTransferBadgeInfo("JC", "中央線", "Chūō Line", Color(0xFFF15A22)),
            TrainTransferBadgeInfo("JH", "横浜線・八高線", "Yokohama / Hachikō Line", Color(0xFF80C241))
        )
        "高尾" -> listOf(
            TrainTransferBadgeInfo("JC", "中央線", "Chūō Line", Color(0xFFF15A22)),
            TrainTransferBadgeInfo("KO", "京王高尾線", "Keiō Takao Line", Color(0xFFDD0077))
        )
        "拝島" -> listOf(
            TrainTransferBadgeInfo("JC", "青梅・五日市線", "Ōme / Itsukaichi Line", Color(0xFFF15A22)),
            TrainTransferBadgeInfo("SS", "西武拝島線", "Seibu Haijima Line", Color(0xFF00A0C6))
        )
        "西国分寺" -> listOf(
            TrainTransferBadgeInfo("JC", "中央線", "Chūō Line", Color(0xFFF15A22)),
            TrainTransferBadgeInfo("JM", "武蔵野線", "Musashino Line", Color(0xFFF15A22))
        )
        "国分寺" -> listOf(
            TrainTransferBadgeInfo("JC", "中央線", "Chūō Line", Color(0xFFF15A22)),
            TrainTransferBadgeInfo("SS", "西武国分寺線", "Seibu Line", Color(0xFF00A0C6))
        )
        "吉祥寺" -> listOf(
            TrainTransferBadgeInfo("JC", "中央線", "Chūō Line", Color(0xFFF15A22)),
            TrainTransferBadgeInfo("IN", "京王井の頭線", "Keiō Inokashira Line", Color(0xFF1D2088))
        )
        "中野" -> listOf(
            TrainTransferBadgeInfo("JC", "中央線", "Chūō Line", Color(0xFFF15A22)),
            TrainTransferBadgeInfo("T", "東西線", "Tōzai Line", Color(0xFF009BBF))
        )
        "四ツ谷" -> listOf(
            TrainTransferBadgeInfo("M", "丸ノ内線", "Marunouchi Line", Color(0xFFF62E36)),
            TrainTransferBadgeInfo("N", "南北線", "Namboku Line", Color(0xFF00AC9B))
        )
        "御茶ノ水" -> listOf(
            TrainTransferBadgeInfo("JB", "中央・総武線", "Chūō-Sōbu Line", Color(0xFFFFD400)),
            TrainTransferBadgeInfo("M", "丸ノ内線", "Marunouchi Line", Color(0xFFF62E36))
        )
        "東京" -> listOf(
            TrainTransferBadgeInfo("JY", "山手線", "Yamanote Line", Color(0xFF9ACD32)),
            TrainTransferBadgeInfo("JK", "京浜東北線", "Keihin-Tōhoku Line", Color(0xFF00B2E5))
        )
        "分倍河原" -> listOf(
            TrainTransferBadgeInfo("JN", "南武線", "Nambu Line", Color(0xFFFFD400)),
            TrainTransferBadgeInfo("KO", "京王線", "Keiō Line", Color(0xFFDD0077))
        )
        "橋本" -> listOf(
            TrainTransferBadgeInfo("JH", "横浜線", "Yokohama Line", Color(0xFF80C241)),
            TrainTransferBadgeInfo("KO", "京王相模原線", "Keiō Sagamihara Line", Color(0xFFDD0077))
        )
        "町田" -> listOf(
            TrainTransferBadgeInfo("JH", "横浜線", "Yokohama Line", Color(0xFF80C241)),
            TrainTransferBadgeInfo("OH", "小田急線", "Odakyū Line", Color(0xFF007ED5))
        )
        else -> emptyList()
    }
    return rawList.filter { it.prefix != currentPrefix }
}

private val stationHiraganaMap = mapOf(
    "大宮" to "おおみや", "北与野" to "きたよの", "与野本町" to "よのほんまち", "南与野" to "みなみよの",
    "中浦和" to "なかうらわ", "武蔵浦和" to "むさしうらわ", "北戸田" to "きたとだ", "戸田" to "とだ",
    "戸田公園" to "とだこうえん", "浮間舟渡" to "うきまふなど", "北赤羽" to "きたあかばね", "赤羽" to "あかばね",
    "十条" to "じゅうじょう", "板橋" to "いたばし", "池袋" to "いけぶくろ", "新宿" to "しんじゅく",
    "渋谷" to "しぶや", "恵比寿" to "えびす", "大崎" to "おおさき", "大井町" to "おおいまち",
    "品川シーサイド" to "しながわしーさいど", "天王洲アイル" to "てんのうずあいる",
    "東京テレポート" to "とうきょうてれぽーと", "国際展示場" to "こくさいてんじじょう",
    "東雲" to "しののめ", "新木場" to "しんきば", "川越" to "かわごえ",
    "東京" to "とうきょう", "神田" to "かんだ", "御茶ノ水" to "おちゃのみず", "水道橋" to "すいどうばし",
    "飯田橋" to "いいだばし", "市ケ谷" to "いちがや", "四ツ谷" to "よつや", "信濃町" to "しなのまち",
    "千駄ケ谷" to "せんだがや", "代々木" to "よよぎ", "大久保" to "おおくぼ", "東中野" to "ひがしなかの",
    "中野" to "なかの", "高円寺" to "こうえんじ", "阿佐ケ谷" to "あさがや", "荻窪" to "おぎくぼ",
    "西荻窪" to "にしおぎくぼ", "吉祥寺" to "きちじょうじ", "三鷹" to "みたか", "武蔵境" to "むさしさかい",
    "東小金井" to "ひがしこがねい", "武蔵小金井" to "むさしこがねい", "国分寺" to "こくぶんじ",
    "西国分寺" to "にしこくぶんじ", "国立" to "くにたち", "立川" to "たちかわ", "日野" to "ひの",
    "豊田" to "とよだ", "八王子" to "はちおうじ", "西八王子" to "にしはちおうじ", "高尾" to "たかお",
    "高尾山口" to "たかおさんぐち", "相模湖" to "さがみこ", "大月" to "おおつき",
    "西立川" to "にしたちかわ", "東中神" to "ひがしなかがみ", "中神" to "なかがみ", "昭島" to "あきしま",
    "拝島" to "はいじま", "牛浜" to "うしはま", "福生" to "ふっさ", "羽村" to "はむら",
    "小作" to "おざく", "河辺" to "かべ", "東青梅" to "ひがしおうめ", "青梅" to "おうめ",
    "御嶽" to "みたけ", "奥多摩" to "おくたま",
    "熊川" to "くまがわ", "東秋留" to "ひがしあきる", "秋川" to "あきがわ", "武蔵引田" to "むさしひきだ",
    "武蔵増戸" to "むさしますこ", "武蔵五日市" to "むさしいつかいち",
    "京王八王子" to "けいおうはちおうじ", "北野" to "きたの", "長沼" to "ながぬま",
    "平山城址公園" to "ひらやまじょうしこうえん", "南平" to "みなみだいら", "高幡不動" to "たかはたふどう",
    "百草園" to "もぐさえん", "聖蹟桜ヶ丘" to "せいせきさくらがおか", "中河原" to "なかがわら",
    "分倍河原" to "ぶばいがわら", "府中" to "ふちゅう", "東府中" to "ひがしふちゅう", "調布" to "ちょうふ",
    "つつじヶ丘" to "つつじがおか", "仙川" to "せんがわ", "千歳烏山" to "ちとせからすやま",
    "桜上水" to "さくらじょうすい", "下高井戸" to "しもたかいど", "明大前" to "めいだいまえ", "笹塚" to "ささづか",
    "京王片倉" to "けいおうかたくら", "山田" to "やまだ", "めじろ台" to "めじろだい", "狭間" to "はざま",
    "橋本" to "はしもと", "南大沢" to "みなみおおさわ", "京王多摩センター" to "けいおうたまセンター",
    "相原" to "あいはら", "八王子みなみ野" to "はちおうじみなみの", "片倉" to "かたくら",
    "相模原" to "さがみはら", "町田" to "まちだ", "新横浜" to "しんよこはま", "東神奈川" to "ひがしかながわ",
    "横浜" to "よこはま", "川崎" to "かわさき", "品川" to "しながわ", "上野" to "うえの", "秋葉原" to "あきはばら",
    "千葉" to "ちば", "津田沼" to "つだぬま", "船橋" to "ふなばし", "西船橋" to "にしふなばし", "錦糸町" to "きんしちょう"
)

private val stationExactRomajiMap = mapOf(
    "大宮" to "Ōmiya", "北与野" to "Kita-Yono", "与野本町" to "Yonohommachi", "南与野" to "Minami-Yono",
    "中浦和" to "Naka-Urawa", "武蔵浦和" to "Musashi-Urawa", "北戸田" to "Kita-Toda", "戸田" to "Toda",
    "戸田公園" to "Toda-Kōen", "浮間舟渡" to "Ukima-Funado", "北赤羽" to "Kita-Akabane", "赤羽" to "Akabane",
    "十条" to "Jūjō", "板橋" to "Itabashi", "池袋" to "Ikebukuro", "新宿" to "Shinjuku",
    "渋谷" to "Shibuya", "恵比寿" to "Ebisu", "大崎" to "Ōsaki", "大井町" to "Ōimachi",
    "品川シーサイド" to "Shinagawa Seaside", "天王洲アイル" to "Tennōzu Isle",
    "東京テレポート" to "Tokyo Teleport", "国際展示場" to "Kokusai-Tenjijō",
    "東雲" to "Shinonome", "新木場" to "Shin-Kiba", "川越" to "Kawagoe",
    "東京" to "Tōkyō", "神田" to "Kanda", "御茶ノ水" to "Ochanomizu", "水道橋" to "Suidōbashi",
    "飯田橋" to "Iidabashi", "市ケ谷" to "Ichigaya", "四ツ谷" to "Yotsuya", "信濃町" to "Shinanomachi",
    "千駄ケ谷" to "Sendagaya", "代々木" to "Yoyogi", "大久保" to "Ōkubo", "東中野" to "Higashi-Nakano",
    "中野" to "Nakano", "高円寺" to "Kōenji", "阿佐ケ谷" to "Asagaya", "荻窪" to "Ogikubo",
    "西荻窪" to "Nishi-Ogikubo", "吉祥寺" to "Kichijōji", "三鷹" to "Mitaka", "武蔵境" to "Musashi-Sakai",
    "東小金井" to "Higashi-Koganei", "武蔵小金井" to "Musashi-Koganei", "国分寺" to "Kokubunji",
    "西国分寺" to "Nishi-Kokubunji", "国立" to "Kunitachi", "立川" to "Tachikawa", "日野" to "Hino",
    "豊田" to "Toyoda", "八王子" to "Hachiōji", "西八王子" to "Nishi-Hachiōji", "高尾" to "Takao",
    "高尾山口" to "Takaosanguchi", "相模湖" to "Sagamiko", "大月" to "Ōtsuki",
    "西立川" to "Nishi-Tachikawa", "東中神" to "Higashi-Nakagami", "中神" to "Nakagami", "昭島" to "Akishima",
    "拝島" to "Haijima", "牛浜" to "Ushihama", "福生" to "Fussa", "羽村" to "Hamura",
    "小作" to "Ozaku", "河辺" to "Kabe", "東青梅" to "Higashi-Ōme", "青梅" to "Ōme",
    "御嶽" to "Mitake", "奥多摩" to "Okutama",
    "熊川" to "Kumagawa", "東秋留" to "Higashi-Akiru", "秋川" to "Akigawa", "武蔵引田" to "Musashi-Hikida",
    "武蔵増戸" to "Musashi-Masuko", "武蔵五日市" to "Musashi-Itsukaichi",
    "京王八王子" to "Keiō-Hachiōji", "北野" to "Kitano", "長沼" to "Naganuma",
    "平山城址公園" to "Hirayamajōshi-kōen", "南平" to "Minamidaira", "高幡不動" to "Takahatafudō",
    "百草園" to "Mogusaen", "聖蹟桜ヶ丘" to "Seiseki-Sakuragaoka", "中河原" to "Nakagawara",
    "分倍河原" to "Bubaigawara", "府中" to "Fuchū", "東府中" to "Higashi-Fuchū", "調布" to "Chōfu",
    "つつじヶ丘" to "Tsutsujigaoka", "仙川" to "Sengawa", "千歳烏山" to "Chitose-Karasuyama",
    "桜上水" to "Sakurajōsui", "下高井戸" to "Shimo-Takaido", "明大前" to "Meidaimae", "笹塚" to "Sasazuka",
    "京王片倉" to "Keiō-Katakura", "山田" to "Yamada", "めじろ台" to "Mejirodai", "狭間" to "Hazama",
    "橋本" to "Hashimoto", "南大沢" to "Minami-Ōsawa", "京王多摩センター" to "Keiō-Tama-Center",
    "相原" to "Aihara", "八王子みなみ野" to "Hachiōji-Minamino", "片倉" to "Katakura",
    "相模原" to "Sagamihara", "町田" to "Machida", "新横浜" to "Shin-Yokohama", "東神奈川" to "Higashi-Kanagawa",
    "横浜" to "Yokohama", "川崎" to "Kawasaki", "品川" to "Shinagawa", "上野" to "Ueno", "秋葉原" to "Akihabara",
    "千葉" to "Chiba", "津田沼" to "Tsudanuma", "船橋" to "Funabashi", "西船橋" to "Nishi-Funabashi", "錦糸町" to "Kinshichō"
)

private fun lightenColor(color: Color, fraction: Float): Color {
    val r = (color.red + (1f - color.red) * fraction).coerceIn(0f, 1f)
    val g = (color.green + (1f - color.green) * fraction).coerceIn(0f, 1f)
    val b = (color.blue + (1f - color.blue) * fraction).coerceIn(0f, 1f)
    return Color(r, g, b, color.alpha)
}

private fun darkenColor(color: Color, fraction: Float): Color {
    val r = (color.red * (1f - fraction)).coerceIn(0f, 1f)
    val g = (color.green * (1f - fraction)).coerceIn(0f, 1f)
    val b = (color.blue * (1f - fraction)).coerceIn(0f, 1f)
    return Color(r, g, b, color.alpha)
}
