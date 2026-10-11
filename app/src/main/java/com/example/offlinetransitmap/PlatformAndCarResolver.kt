package com.example.offlinetransitmap

/**
 * 駅の発着番線および編成両数の解決ユーティリティ
 */
object PlatformAndCarResolver {

    /**
     * 発着番線の解決
     * データベース内に明示的な番線・のりばデータ（rawPlatform）が存在する場合のみ正確に返し、
     * データがない場合は推測による誤表示を防ぐため空文字（非表示）を返します。
     */
    fun resolvePlatform(
        stationName: String,
        lineName: String,
        headsign: String,
        rawPlatform: String?,
        isBus: Boolean,
        seq: Int = 1,
        tripNo: Long = 0L
    ): String {
        if (!rawPlatform.isNullOrBlank() && rawPlatform != "0") {
            val clean = rawPlatform.trim()
            return if (isBus) {
                if (clean.endsWith("のりば") || clean.endsWith("番線")) clean else "${clean}番のりば"
            } else {
                if (clean.endsWith("番線") || clean.endsWith("のりば")) clean else "${clean}番線"
            }
        }
        // 公式データに番線情報がない場合は不正確な推測を行わず非表示
        return ""
    }

    /**
     * 編成両数の解決
     * 首都圏の路線名および列車種別から編成両数を算出
     */
    fun resolveCarCount(lineName: String, trainType: String = "", isBus: Boolean = false): Int? {
        if (isBus) return null

        val line = lineName.trim()
        val type = trainType.trim()

        // 15両編成の主要幹線
        if (line.contains("東海道") || line.contains("宇都宮") || line.contains("高崎") ||
            line.contains("湘南新宿") || line.contains("上野東京") || line.contains("常磐線快速") ||
            line.contains("横須賀") || line.contains("総武快速")
        ) {
            return 15
        }

        // 11両編成
        if (line.contains("山手")) return 11

        // 10両編成 (首都圏通勤路線の主力)
        if (line.contains("中央線快速") || line.contains("中央快速") || line.contains("京浜東北") ||
            line.contains("根岸") || line.contains("埼京") || line.contains("川越") ||
            line.contains("中央・総武") || line.contains("総武各駅") || line.contains("京葉") ||
            line.contains("常磐線各駅") || line.contains("東西線") || line.contains("千代田") ||
            line.contains("有楽町") || line.contains("半蔵門") || line.contains("副都心") ||
            line.contains("都営新宿") || line.contains("田園都市") || line.contains("東横") ||
            line.contains("相鉄") || line.contains("東武東上") || line.contains("西武池袋") ||
            line.contains("西武新宿")
        ) {
            return 10
        }

        // 8両編成
        if (line.contains("横浜線") || line.contains("武蔵野") || line.contains("日比谷") ||
            line.contains("南北線") || line.contains("都営三田") || line.contains("都営浅草") ||
            line.contains("大江戸") || line.contains("目黒線") || line.contains("京急") ||
            line.contains("京成") || line.contains("北総")
        ) {
            return if (line.contains("日比谷")) 7 else 8
        }

        // 6両編成
        if (line.contains("南武線") || line.contains("銀座線") || line.contains("丸ノ内") ||
            line.contains("つくば") || line.contains("ゆりかもめ")
        ) {
            return 6
        }

        // 5両編成
        if (line.contains("井の頭") || line.contains("日暮里・舎人") || line.contains("大井町")) {
            return if (line.contains("大井町") && type.contains("急行")) 7 else 5
        }

        // 4両編成
        if (line.contains("八高") || line.contains("多摩都市モノレール") || line.contains("多摩川線") ||
            line.contains("国分寺線") || line.contains("青梅") && (line.contains("奥多摩") || type.contains("ワンマン"))
        ) {
            return 4
        }

        // 3両編成
        if (line.contains("池上") || line.contains("東急多摩川") || line.contains("鶴見")) return 3

        // 2両編成
        if (line.contains("世田谷")) return 2

        // 私鉄全般のデフォルト (京王、小田急、西武、東武、東急など)
        if (line.contains("京王") || line.contains("小田急") || line.contains("東急") ||
            line.contains("西武") || line.contains("東武") || line.contains("地下鉄") || line.contains("メトロ")
        ) {
            return 10
        }

        // その他一般鉄道
        return 10
    }

    /**
     * 時刻表用の詳細テキストを生成
     * - 正式な番線がある場合: "定刻 · 2番線 · 10両編成" / "定刻 · 1番のりば"
     * - 番線がない場合: "定刻 · 10両編成" / "定刻に出発済み · 10両編成"
     */
    fun formatDepartureDetail(
        status: String,
        platform: String,
        carCount: Int?,
        isBus: Boolean
    ): String {
        val parts = mutableListOf<String>()
        parts.add(status)

        if (platform.isNotBlank()) {
            parts.add(platform)
        }

        if (carCount != null && carCount > 0 && !isBus) {
            parts.add("${carCount}両編成")
        }

        return parts.joinToString(" · ")
    }
}
