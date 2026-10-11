package com.example.offlinetransitmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 踏切撮影ロケハン用：撮影構図ファインダーオーバーレイ
 * - 三分割法（Rule of Thirds）ガイドライン
 * - 3:2（一眼レフ標準アスペクト比）フレーム
 * - 水準器アシストのセンタークロスヘア
 */
@Composable
fun CompositionGridOverlay(
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0x66FFFFFF),
    frameColor: Color = Color(0x88FFD54F)
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val strokeWidth1 = 1.dp.toPx()
        val strokeWidth2 = 1.5.dp.toPx()

        // 1. 三分割ガイドライン（画面全体）
        val x1 = w / 3f
        val x2 = w * 2f / 3f
        val y1 = h / 3f
        val y2 = h * 2f / 3f

        drawLine(lineColor, Offset(x1, 0f), Offset(x1, h), strokeWidth = strokeWidth1)
        drawLine(lineColor, Offset(x2, 0f), Offset(x2, h), strokeWidth = strokeWidth1)
        drawLine(lineColor, Offset(0f, y1), Offset(w, y1), strokeWidth = strokeWidth1)
        drawLine(lineColor, Offset(0f, y2), Offset(w, y2), strokeWidth = strokeWidth1)

        // 2. 一眼レフ標準アスペクト比（3:2）のフレーム
        // 横長・縦長に合わせて中央に3:2の枠を描画
        val targetRatio = 3f / 2f
        val frameW: Float
        val frameH: Float
        if (w / h > targetRatio) {
            frameH = h * 0.85f
            frameW = frameH * targetRatio
        } else {
            frameW = w * 0.90f
            frameH = frameW / targetRatio
        }

        val frameLeft = (w - frameW) / 2f
        val frameTop = (h - frameH) / 2f

        drawRect(
            color = frameColor,
            topLeft = Offset(frameLeft, frameTop),
            size = Size(frameW, frameH),
            style = Stroke(width = strokeWidth2)
        )

        // 枠の四隅にカギ括弧（ファインダーマーク）
        val cornerLen = 16.dp.toPx()
        val cornerStroke = 2.5.dp.toPx()
        // 左上
        drawLine(frameColor, Offset(frameLeft, frameTop), Offset(frameLeft + cornerLen, frameTop), strokeWidth = cornerStroke)
        drawLine(frameColor, Offset(frameLeft, frameTop), Offset(frameLeft, frameTop + cornerLen), strokeWidth = cornerStroke)
        // 右上
        drawLine(frameColor, Offset(frameLeft + frameW, frameTop), Offset(frameLeft + frameW - cornerLen, frameTop), strokeWidth = cornerStroke)
        drawLine(frameColor, Offset(frameLeft + frameW, frameTop), Offset(frameLeft + frameW, frameTop + cornerLen), strokeWidth = cornerStroke)
        // 左下
        drawLine(frameColor, Offset(frameLeft, frameTop + frameH), Offset(frameLeft + cornerLen, frameTop + frameH), strokeWidth = cornerStroke)
        drawLine(frameColor, Offset(frameLeft, frameTop + frameH), Offset(frameLeft, frameTop + frameH - cornerLen), strokeWidth = cornerStroke)
        // 右下
        drawLine(frameColor, Offset(frameLeft + frameW, frameTop + frameH), Offset(frameLeft + frameW - cornerLen, frameTop + frameH), strokeWidth = cornerStroke)
        drawLine(frameColor, Offset(frameLeft + frameW, frameTop + frameH), Offset(frameLeft + frameW, frameTop + frameH - cornerLen), strokeWidth = cornerStroke)

        // 3. センタークロスヘア（中心点）
        val cx = w / 2f
        val cy = h / 2f
        val crossSize = 10.dp.toPx()
        drawLine(lineColor, Offset(cx - crossSize, cy), Offset(cx + crossSize, cy), strokeWidth = strokeWidth1)
        drawLine(lineColor, Offset(cx, cy - crossSize), Offset(cx, cy + crossSize), strokeWidth = strokeWidth1)
    }
}
