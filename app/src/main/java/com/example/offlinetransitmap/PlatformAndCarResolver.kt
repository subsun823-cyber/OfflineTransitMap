package com.example.offlinetransitmap

/**
 * 首都圏を中心とする駅の発着番線および編成両数の推定・解決ユーティリティ
 */
object PlatformAndCarResolver {

    /**
     * 発着番線の解決
     * DB内の platform があればそれを優先し、空の場合は駅名・路線名・方面から高精度に推定
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
        // 1. 生の platform データが存在する場合はそれを使用
        if (!rawPlatform.isNullOrBlank() && rawPlatform != "0") {
            val clean = rawPlatform.trim()
            return if (isBus) {
                if (clean.endsWith("のりば") || clean.endsWith("番線")) clean else "${clean}番のりば"
            } else {
                if (clean.endsWith("番線") || clean.endsWith("のりば")) clean else "${clean}番線"
            }
        }

        // バスで番号がない場合は空
        if (isBus) return ""

        // 2. 電車: 主要駅・ターミナル駅における路線・方面別の番線辞書
        val st = stationName.trim()
        val line = lineName.trim()
        val dest = headsign.trim()

        val resolved = matchStationPlatform(st, line, dest)
        if (resolved != null) {
            return "${resolved}番線"
        }

        // 3. 一般駅: 進行方向（seq または tripNo）から 1番線 / 2番線を推定
        // 典型的な2面2線駅（下り1番線、上り2番線）
        val pNum = if (seq % 2 == 1 && (tripNo % 2L == 1L || dest.contains("行") || dest.contains("下り"))) {
            1
        } else if (seq % 2 == 0) {
            2
        } else {
            ((tripNo.hashCode().let { if (it < 0) -it else it } % 2) + 1)
        }
        return "${pNum}番線"
    }

    /**
     * 主要駅の番線マッピング判定
     */
    private fun matchStationPlatform(station: String, line: String, dest: String): Int? {
        val st = station.removeSuffix("駅")
        when (st) {
            "新宿" -> {
                if (line.contains("山手")) {
                    return if (dest.contains("渋谷") || dest.contains("品川") || dest.contains("内回り")) 14 else 15
                }
                if (line.contains("中央線快速") || line.contains("中央快速")) {
                    return if (dest.contains("東京")) 7 else 11
                }
                if (line.contains("中央・総武") || line.contains("総武")) {
                    return if (dest.contains("中野") || dest.contains("三鷹")) 16 else 13
                }
                if (line.contains("埼京") || line.contains("川越") || line.contains("相鉄")) {
                    return if (dest.contains("大宮") || dest.contains("川越")) 1 else 2
                }
                if (line.contains("湘南新宿")) {
                    return if (dest.contains("大宮") || dest.contains("宇都宮") || dest.contains("高崎")) 4 else 2
                }
                if (line.contains("丸ノ内")) {
                    return if (dest.contains("池袋")) 2 else 1
                }
                if (line.contains("都営新宿") || line.contains("京王新線")) {
                    return if (dest.contains("本八幡") || dest.contains("新宿線")) 5 else 4
                }
                if (line.contains("京王")) return 2
                if (line.contains("小田急")) return 4
                if (line.contains("大江戸")) {
                    return if (dest.contains("六本木") || dest.contains("大門")) 6 else 7
                }
            }
            "東京" -> {
                if (line.contains("中央")) return 1
                if (line.contains("山手")) {
                    return if (dest.contains("上野") || dest.contains("池袋") || dest.contains("内回り")) 4 else 5
                }
                if (line.contains("京浜東北")) {
                    return if (dest.contains("上野") || dest.contains("大宮")) 3 else 6
                }
                if (line.contains("上野東京") || line.contains("宇都宮") || line.contains("高崎") || line.contains("常磐")) return 7
                if (line.contains("東海道")) return 9
                if (line.contains("総武") || line.contains("横須賀")) {
                    return if (dest.contains("千葉") || dest.contains("成田")) 1 else 3
                }
                if (line.contains("京葉") || line.contains("武蔵野")) return 1
                if (line.contains("丸ノ内")) return if (dest.contains("池袋")) 2 else 1
            }
            "渋谷" -> {
                if (line.contains("山手")) {
                    return if (dest.contains("品川") || dest.contains("内回り")) 2 else 1
                }
                if (line.contains("埼京") || line.contains("湘南新宿")) return 3
                if (line.contains("銀座")) return 1
                if (line.contains("半蔵門") || line.contains("田園都市")) {
                    return if (dest.contains("押上") || dest.contains("久喜")) 2 else 1
                }
                if (line.contains("副都心") || line.contains("東横")) {
                    return if (dest.contains("和光市") || dest.contains("小手指") || dest.contains("森林公園")) 5 else 3
                }
                if (line.contains("井の頭")) return 1
            }
            "池袋" -> {
                if (line.contains("埼京")) return 1
                if (line.contains("山手")) {
                    return if (dest.contains("上野") || dest.contains("東京") || dest.contains("外回り")) 7 else 5
                }
                if (line.contains("丸ノ内")) return 1
                if (line.contains("有楽町")) return 3
                if (line.contains("副都心")) return 5
                if (line.contains("西武池袋") || line.contains("西武")) return 2
                if (line.contains("東武東上") || line.contains("東武")) return 1
            }
            "上野" -> {
                if (line.contains("京浜東北")) return if (dest.contains("大宮")) 1 else 4
                if (line.contains("山手")) return if (dest.contains("東京")) 3 else 2
                if (line.contains("宇都宮") || line.contains("高崎")) return 5
                if (line.contains("常磐")) return 9
                if (line.contains("銀座")) return 1
                if (line.contains("日比谷")) return 1
            }
            "品川" -> {
                if (line.contains("山手")) return 1
                if (line.contains("京浜東北")) return 4
                if (line.contains("東海道")) return 11
                if (line.contains("横須賀")) return 13
                if (line.contains("上野東京")) return 9
                if (line.contains("京急")) return 1
            }
            "立川" -> {
                if (line.contains("中央")) return if (dest.contains("東京")) 3 else 2
                if (line.contains("青梅")) return 5
                if (line.contains("南武")) return 7
            }
            "吉祥寺" -> {
                if (line.contains("中央線快速") || line.contains("中央快速")) {
                    return if (dest.contains("東京")) 2 else 1
                }
                if (line.contains("中央・総武") || line.contains("総武")) {
                    return if (dest.contains("千葉")) 4 else 3
                }
                if (line.contains("井の頭")) return 1
            }
            "八王子" -> {
                if (line.contains("中央")) return if (dest.contains("東京")) 2 else 1
                if (line.contains("横浜")) return 5
                if (line.contains("八高")) return 1
            }
            "高尾" -> {
                if (line.contains("中央")) return if (dest.contains("東京")) 2 else 1
                if (line.contains("京王")) return 1
            }
            "横浜" -> {
                if (line.contains("京浜東北") || line.contains("根岸")) return 3
                if (line.contains("東海道")) return 5
                if (line.contains("横須賀") || line.contains("湘南新宿")) return 9
                if (line.contains("東横") || line.contains("みなとみらい")) return 1
                if (line.contains("相鉄")) return 1
                if (line.contains("京急")) return 1
            }
            "大宮" -> {
                if (line.contains("京浜東北")) return 1
                if (line.contains("宇都宮")) return 9
                if (line.contains("高崎")) return 7
                if (line.contains("埼京")) return 19
            }
        }
        return null
    }

    /**
     * 編成両数の解決
     * 路線名および種別からリアルな編成両数を算出
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
     * 例: "定刻 · 2番線 · 10両編成" / "定刻に出発済み · 1番線 · 8両編成" / "定刻 · 1番のりば"
     */
    fun formatDepartureDetail(
        status: String,
        platform: String,
        carCount: Int?,
        isBus: Boolean,
        fallbackDetail: String = ""
    ): String {
        val parts = mutableListOf<String>()
        parts.add(status)

        val plat = if (platform.isNotBlank()) platform else fallbackDetail
        if (plat.isNotBlank()) {
            parts.add(plat)
        }

        if (carCount != null && carCount > 0 && !isBus) {
            parts.add("${carCount}両編成")
        }

        return parts.joinToString(" · ")
    }
}
