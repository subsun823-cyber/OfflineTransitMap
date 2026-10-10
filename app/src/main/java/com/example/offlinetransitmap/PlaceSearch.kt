package com.example.offlinetransitmap

import android.database.sqlite.SQLiteDatabase
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class PlaceCategory(val label: String, val iconEmoji: String) {
    STATION("駅", "🚉"),
    BUS_STOP("バス停", "🚏"),
    LANDMARK("名所・観光", "🗼"),
    COMMERCIAL("商業施設", "🛍️"),
    LEISURE("レジャー", "🎡"),
    PARK("公園", "🌳"),
    FACILITY("施設・公共", "🏛️"),
    TRANSPORT("交通拠点", "✈️")
}

data class PlaceItem(
    val id: String,
    val name: String,
    val kana: String,
    val lat: Double,
    val lon: Double,
    val category: PlaceCategory,
    val detail: String = ""
)

data class PlaceSearchResult(
    val place: PlaceItem,
    val distanceMeters: Int? = null,
    val durationMinutes: Int? = null,
    val distanceText: String = "",
    val durationText: String = ""
)

fun formatDistance(meters: Int): String {
    return if (meters < 1000) {
        "約${meters}m"
    } else {
        val km = meters / 1000.0
        "約${"%.1f".format(km)}km"
    }
}

fun formatDuration(minutes: Int): String {
    return if (minutes < 60) {
        "徒歩約${minutes}分"
    } else {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0) "徒歩約${hours}時間" else "徒歩約${hours}時間${rest}分"
    }
}

private fun toSearchableHiragana(s: String): String {
    val sb = StringBuilder()
    for (ch in s.trim().lowercase()) {
        when (ch) {
            in '\u30A1'..'\u30F6' -> sb.append((ch.code - 0x60).toChar())
            'ー', '・', ' ', '　' -> {} // 記号やスペースは無視して検索しやすくする
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

// 関東の主要スポット・ランドマーク（オフライン時でも主要施設が即座にヒット）
val PRESET_PLACES: List<PlaceItem> = listOf(
    // ランドマーク・観光
    PlaceItem("spot_tokyo_tower", "東京タワー", "とうきょうたわー", 35.658581, 139.745433, PlaceCategory.LANDMARK, "東京都港区芝公園"),
    PlaceItem("spot_skytree", "東京スカイツリー", "とうきょうすかいつりー", 35.710063, 139.810700, PlaceCategory.LANDMARK, "東京都墨田区押上"),
    PlaceItem("spot_sensoji", "浅草寺", "せんそうじ", 35.714765, 139.796655, PlaceCategory.LANDMARK, "東京都台東区浅草"),
    PlaceItem("spot_meijijingu", "明治神宮", "めいじじんぐう", 35.676398, 139.699347, PlaceCategory.LANDMARK, "東京都渋谷区代々木神園町"),
    PlaceItem("spot_koukyo", "皇居", "こうきょ", 35.685175, 139.752800, PlaceCategory.LANDMARK, "東京都千代田区千代田"),
    PlaceItem("spot_tokyodome", "東京ドーム", "とうきょうどーむ", 35.705640, 139.751891, PlaceCategory.LANDMARK, "東京都文京区後楽"),
    PlaceItem("spot_budokan", "日本武道館", "にっぽんぶどうかん", 35.693318, 139.749877, PlaceCategory.LANDMARK, "東京都千代田区北の丸公園"),
    PlaceItem("spot_bigsight", "東京ビッグサイト", "とうきょうびっぐさいと", 35.629829, 139.794180, PlaceCategory.LANDMARK, "東京都江東区有明"),
    PlaceItem("spot_makuharimesse", "幕張メッセ", "まくはりめっせ", 35.648386, 140.034575, PlaceCategory.LANDMARK, "千葉県千葉市美浜区"),
    PlaceItem("spot_pacifico", "パシフィコ横浜", "ぱしふぃこよこはま", 35.458641, 139.636413, PlaceCategory.LANDMARK, "神奈川県横浜市西区みなとみらい"),
    PlaceItem("spot_akarenga", "横浜赤レンガ倉庫", "よこはまあかれんがそうこ", 35.452668, 139.642844, PlaceCategory.LANDMARK, "神奈川県横浜市中区新港"),
    PlaceItem("spot_landmark_tower", "横浜ランドマークタワー", "よこはまらんどまーくたわー", 35.454988, 139.631386, PlaceCategory.LANDMARK, "神奈川県横浜市西区みなとみらい"),
    PlaceItem("spot_chinatown", "横浜中華街", "よこはまちゅうかがい", 35.443141, 139.645719, PlaceCategory.LANDMARK, "神奈川県横浜市中区山下町"),
    PlaceItem("spot_saitama_arena", "さいたまスーパーアリーナ", "さいたますーぱーありーな", 35.894872, 139.630806, PlaceCategory.LANDMARK, "埼玉県さいたま市中央区新都心"),
    PlaceItem("spot_kamakura_daibutsu", "鎌倉大仏 (高徳院)", "かまくらだいぶつ", 35.316827, 139.535728, PlaceCategory.LANDMARK, "神奈川県鎌倉市長谷"),
    PlaceItem("spot_enoshima", "江の島", "えのしま", 35.300624, 139.480747, PlaceCategory.LANDMARK, "神奈川県藤沢市江の島"),
    PlaceItem("spot_kawagoe", "川越一番街 (小江戸蔵造り)", "かわごえいちばんがい", 35.923485, 139.483162, PlaceCategory.LANDMARK, "埼玉県川越市幸町"),

    // 商業施設
    PlaceItem("spot_sunshine", "サンシャインシティ", "さんしゃいんしてぃ", 35.728989, 139.719089, PlaceCategory.COMMERCIAL, "東京都豊島区東池袋"),
    PlaceItem("spot_roppongihills", "六本木ヒルズ", "ろっぽんぎひるず", 35.660464, 139.729249, PlaceCategory.COMMERCIAL, "東京都港区六本木"),
    PlaceItem("spot_tokyomidtown", "東京ミッドタウン", "とうきょうみっどたうん", 35.665722, 139.731003, PlaceCategory.COMMERCIAL, "東京都港区赤坂"),
    PlaceItem("spot_shibuya_scramble", "渋谷スクランブルスクエア", "しぶやすくらんぶるすくえあ", 35.658517, 139.702274, PlaceCategory.COMMERCIAL, "東京都渋谷区渋谷"),
    PlaceItem("spot_shibuya_hikarie", "渋谷ヒカリエ", "しぶやひかりえ", 35.659025, 139.703478, PlaceCategory.COMMERCIAL, "東京都渋谷区渋谷"),
    PlaceItem("spot_ginzasix", "GINZA SIX", "ぎんざしっくす", 35.669614, 139.764042, PlaceCategory.COMMERCIAL, "東京都中央区銀座"),
    PlaceItem("spot_lazona", "ラゾーナ川崎プラザ", "らぞーなかわさきぷらざ", 35.532321, 139.696131, PlaceCategory.COMMERCIAL, "神奈川県川崎市幸区駅前本町"),
    PlaceItem("spot_lalaport_tokyobay", "ららぽーとTOKYO-BAY", "ららぽーととうきょうべい", 35.686001, 139.989722, PlaceCategory.COMMERCIAL, "千葉県船橋市浜町"),
    PlaceItem("spot_cocooncity", "コクーンシティ", "こくーんしてぃ", 35.895318, 139.634628, PlaceCategory.COMMERCIAL, "埼玉県さいたま市大宮区吉敷町"),
    PlaceItem("spot_kisarazu_outlet", "三井アウトレットパーク 木更津", "みついあうとれっとぱーくきさらづ", 35.435944, 139.934889, PlaceCategory.COMMERCIAL, "千葉県木更津市金田東"),

    // レジャー・テーマパーク
    PlaceItem("spot_disneyland", "東京ディズニーランド", "とうきょうでぃずにーらんど", 35.632896, 139.880394, PlaceCategory.LEISURE, "千葉県浦安市舞浜"),
    PlaceItem("spot_disneysea", "東京ディズニーシー", "とうきょうでぃずにーしー", 35.626714, 139.885061, PlaceCategory.LEISURE, "千葉県浦安市舞浜"),
    PlaceItem("spot_ueno_zoo", "上野動物園", "うえのどうぶつえん", 35.716497, 139.771286, PlaceCategory.LEISURE, "東京都台東区上野公園"),
    PlaceItem("spot_kasai_aquarium", "葛西臨海水族館", "かさいりんかいすいぞくかん", 35.640167, 139.862222, PlaceCategory.LEISURE, "東京都江戸川区臨海町"),
    PlaceItem("spot_yomiuriland", "よみうりランド", "よみうりらんど", 35.625472, 139.516778, PlaceCategory.LEISURE, "東京都稲城市矢野口"),
    PlaceItem("spot_puroland", "サンリオピューロランド", "さんりおぴゅーろらんど", 35.624694, 139.429278, PlaceCategory.LEISURE, "東京都多摩市落合"),
    PlaceItem("spot_seibuen", "西武園ゆうえんち", "せいぶえんゆうえんち", 35.766389, 139.444722, PlaceCategory.LEISURE, "埼玉県所沢市山口"),
    PlaceItem("spot_tobu_zoo", "東武動物公園", "とうぶどうぶつこうえん", 36.019444, 139.715278, PlaceCategory.LEISURE, "埼玉県南埼玉郡宮代町須賀"),
    PlaceItem("spot_seaparadise", "横浜・八景島シーパラダイス", "よこはまはっけいじましーぱらだいす", 35.337194, 139.645556, PlaceCategory.LEISURE, "神奈川県横浜市金沢区八景島"),
    PlaceItem("spot_ghibli", "三鷹の森ジブリ美術館", "みたかのもりじぶりびじゅつかん", 35.696222, 139.570444, PlaceCategory.LEISURE, "東京都三鷹市下連雀"),

    // 公園
    PlaceItem("spot_shinjukugyoen", "新宿御苑", "しんじゅくぎょえん", 35.685167, 139.710056, PlaceCategory.PARK, "東京都新宿区内藤町"),
    PlaceItem("spot_uenopark", "上野恩賜公園", "うえのおんしこうえん", 35.714444, 139.773889, PlaceCategory.PARK, "東京都台東区上野公園"),
    PlaceItem("spot_yoyogipark", "代々木公園", "よよぎこうえん", 35.671667, 139.694722, PlaceCategory.PARK, "東京都渋谷区代々木神園町"),
    PlaceItem("spot_inokashira", "井の頭恩賜公園", "いのかしらおんしこうえん", 35.699722, 139.575278, PlaceCategory.PARK, "東京都武蔵野市御殿山"),
    PlaceItem("spot_showakinen", "国営昭和記念公園", "こくえいしょうわきねんこうえん", 35.711944, 139.394167, PlaceCategory.PARK, "東京都立川市緑町"),
    PlaceItem("spot_hibiyapark", "日比谷公園", "ひびやこうえん", 35.673611, 139.755833, PlaceCategory.PARK, "東京都千代田区日比谷公園"),
    PlaceItem("spot_tokorozawa_koku", "所沢航空記念公園", "ところざわこうくうきねんこうえん", 35.798611, 139.471944, PlaceCategory.PARK, "埼玉県所沢市並木"),
    PlaceItem("spot_omiyapark", "大宮公園", "おおみやこうえん", 35.918056, 139.632222, PlaceCategory.PARK, "埼玉県さいたま市大宮区高鼻町"),
    PlaceItem("spot_yamashitapark", "山下公園", "やましたこうえん", 35.445778, 139.650639, PlaceCategory.PARK, "神奈川県横浜市中区山下町"),

    // 交通拠点
    PlaceItem("spot_haneda_t1", "羽田空港 第1ターミナル", "はねだくうこうだいいちたーみなる", 35.549393, 139.779839, PlaceCategory.TRANSPORT, "東京都大田区羽田空港"),
    PlaceItem("spot_haneda_t2", "羽田空港 第2ターミナル", "はねだくうこうだいにつたーみなる", 35.553333, 139.787778, PlaceCategory.TRANSPORT, "東京都大田区羽田空港"),
    PlaceItem("spot_haneda_t3", "羽田空港 第3ターミナル", "はねだくうこうだいさんたーみなる", 35.539722, 139.767500, PlaceCategory.TRANSPORT, "東京都大田区羽田空港"),
    PlaceItem("spot_narita_t1", "成田空港 第1ターミナル", "なりたくうこうだいいちたーみなる", 35.764722, 140.386389, PlaceCategory.TRANSPORT, "千葉県成田市三里塚"),
    PlaceItem("spot_narita_t2", "成田空港 第2ターミナル", "なりたくうこうだいにつたーみなる", 35.773056, 140.388056, PlaceCategory.TRANSPORT, "千葉県成田市古込"),
    PlaceItem("spot_busta_shinjuku", "バスタ新宿", "ばすたしんじゅく", 35.688889, 139.700556, PlaceCategory.TRANSPORT, "東京都渋谷区千駄ヶ谷"),

    // 施設・文化・大学・官公庁
    PlaceItem("spot_tocho", "東京都庁", "とうきょうとちょう", 35.689506, 139.691701, PlaceCategory.FACILITY, "東京都新宿区西新宿"),
    PlaceItem("spot_kokkaigijido", "国会議事堂", "こっかいぎじどう", 35.675888, 139.744858, PlaceCategory.FACILITY, "東京都千代田区永田町"),
    PlaceItem("spot_kokuritsu_shin", "国立新美術館", "こくりつしんびじゅつかん", 35.665278, 139.726389, PlaceCategory.FACILITY, "東京都港区六本木"),
    PlaceItem("spot_tokyo_museum", "東京国立博物館", "とうきょうこくりつはくぶつかん", 35.718889, 139.776389, PlaceCategory.FACILITY, "東京都台東区上野公園"),
    PlaceItem("spot_todai", "東京大学 (本郷キャンパス)", "とうきょうだいがく", 35.712678, 139.761989, PlaceCategory.FACILITY, "東京都文京区本郷"),
    PlaceItem("spot_waseda", "早稲田大学 (早稲田キャンパス)", "わせだだいがく", 35.709028, 139.719444, PlaceCategory.FACILITY, "東京都新宿区西早稲田"),
    PlaceItem("spot_keio_univ", "慶應義塾大学 (三田キャンパス)", "けいおうぎじゅくだいがく", 35.649167, 139.743611, PlaceCategory.FACILITY, "東京都港区三田"),
    PlaceItem("spot_saitama_pref", "埼玉県庁", "さいたまけんちょう", 35.856944, 139.648889, PlaceCategory.FACILITY, "埼玉県さいたま市浦和区高砂"),
    PlaceItem("spot_kanagawa_pref", "神奈川県庁", "かながわけんちょう", 35.447500, 139.642500, PlaceCategory.FACILITY, "神奈川県横浜市中区日本大通"),
    PlaceItem("spot_chiba_pref", "千葉県庁", "ちばけんちょう", 35.605278, 140.123333, PlaceCategory.FACILITY, "千葉県千葉市中央区市場町"),
    PlaceItem("spot_toranomon_hp", "虎の門病院", "とらのもんびょういん", 35.669167, 139.745556, PlaceCategory.FACILITY, "東京都港区虎ノ門"),
    PlaceItem("spot_seiruka_hp", "聖路加国際病院", "せいるかこくさいびょういん", 35.667222, 139.775833, PlaceCategory.FACILITY, "東京都中央区明石町")
)

class PlaceSearcher(
    private val db: SQLiteDatabase? = null,
    private val poiDb: PoiDatabase? = null
) {

    private val allPlaces: List<PlaceItem> by lazy { loadAllPlaces() }

    private fun loadAllPlaces(): List<PlaceItem> {
        val list = ArrayList<PlaceItem>(PRESET_PLACES)

        if (db != null) {
            try {
                // stations テーブルの存在確認
                val hasStations = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='stations'", null
                ).use { it.moveToFirst() }

                if (hasStations) {
                    val hasKind = try {
                        db.rawQuery("PRAGMA table_info(stations)", null).use { c ->
                            var found = false
                            while (c.moveToNext()) {
                                if (c.getString(1) == "kind") found = true
                            }
                            found
                        }
                    } catch (e: Exception) {
                        false
                    }

                    val query = if (hasKind) {
                        "SELECT station_id, name, COALESCE(kana, ''), lat, lon, kind FROM stations"
                    } else {
                        "SELECT station_id, name, COALESCE(kana, ''), lat, lon, 'rail' FROM stations"
                    }

                    db.rawQuery(query, null).use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0)
                            val name = c.getString(1)
                            val kana = c.getString(2)
                            val lat = c.getDouble(3)
                            val lon = c.getDouble(4)
                            val kind = c.getString(5)
                            val category = if (kind == "bus") PlaceCategory.BUS_STOP else PlaceCategory.STATION
                            val detail = if (category == PlaceCategory.BUS_STOP) "バス停留所" else "鉄道駅"
                            list.add(PlaceItem(id, name, kana, lat, lon, category, detail))
                        }
                    }
                }
            } catch (e: Exception) {
                // DB読み込みエラー時はプリセットスポットのみで継続
            }
        }

        return list
    }

    /**
     * 施設・場所・駅・バス停の検索
     *
     * @param query 検索文字列（ひらがな、漢字、カタカナ、英数字）
     * @param currentLat 現在地の緯度（nullの場合は距離計算をスキップ）
     * @param currentLon 現在地の経度
     * @param categoryFilter カテゴリによる絞り込み（nullの場合は全カテゴリ）
     * @param limit 返却する件数上限
     */
    fun search(
        query: String,
        currentLat: Double? = null,
        currentLon: Double? = null,
        categoryFilter: PlaceCategory? = null,
        limit: Int = 40
    ): List<PlaceSearchResult> {
        val q = toSearchableHiragana(query)
        val places = allPlaces

        // 施設DB (pois.db - コンビニ・店舗・病院・学校など25万件) からの検索
        val poiItems = if (query.isNotBlank() || categoryFilter != null) {
            poiDb?.searchPois(query, categoryFilter, limit = limit * 2) ?: emptyList()
        } else {
            emptyList()
        }

        // フィルタリング対象の決定
        val candidatePlaces = if (categoryFilter != null) {
            places.filter { it.category == categoryFilter }
        } else {
            places
        }

        if (q.isBlank() && categoryFilter == null) {
            // クエリが空の場合：現在地があれば近い順、なければプリセット主要スポットと主要駅を返す
            val sorted = if (currentLat != null && currentLon != null) {
                candidatePlaces.sortedBy { p ->
                    calcDistanceMeters(currentLat, currentLon, p.lat, p.lon)
                }
            } else {
                candidatePlaces
            }
            return sorted.take(limit).map { p ->
                buildResult(p, currentLat, currentLon)
            }
        }

        // スコアリングマッチング
        // 1: 完全一致 (name または kana)
        // 2: 前方一致 (name または kana)
        // 3: 部分一致 (name または kana または detail)
        class ScoredPlace(val place: PlaceItem, val matchRank: Int, val distMeters: Int)

        val matches = ArrayList<ScoredPlace>()
        val seenIds = HashSet<String>()

        // 1. プリセットおよび駅・バス停からマッチング
        for (p in candidatePlaces) {
            val nameHira = toSearchableHiragana(p.name)
            val kanaHira = toSearchableHiragana(p.kana)
            val detailHira = toSearchableHiragana(p.detail)

            val rank = when {
                nameHira == q || kanaHira == q -> 1
                nameHira.startsWith(q) || kanaHira.startsWith(q) -> 2
                nameHira.contains(q) || kanaHira.contains(q) || detailHira.contains(q) -> 3
                else -> 0
            }

            if (rank > 0 && seenIds.add(p.id)) {
                val dist = if (currentLat != null && currentLon != null) {
                    calcDistanceMeters(currentLat, currentLon, p.lat, p.lon)
                } else {
                    Int.MAX_VALUE
                }
                matches.add(ScoredPlace(p, rank, dist))
            }
        }

        // 2. 施設DB (pois.db - コンビニ等) からの結果をスコアリングして追加
        for (p in poiItems) {
            val nameHira = toSearchableHiragana(p.name)
            val rank = when {
                nameHira == q -> 1
                nameHira.startsWith(q) -> 2
                else -> 3
            }

            if (seenIds.add(p.id)) {
                val dist = if (currentLat != null && currentLon != null) {
                    calcDistanceMeters(currentLat, currentLon, p.lat, p.lon)
                } else {
                    Int.MAX_VALUE
                }
                matches.add(ScoredPlace(p, rank, dist))
            }
        }

        // マッチ順位（完全一致 > 前方一致 > 部分一致）昇順、次に現在地からの距離昇順
        matches.sortWith(compareBy({ it.matchRank }, { it.distMeters }))

        return matches.take(limit).map { sp ->
            buildResult(sp.place, currentLat, currentLon)
        }
    }

    private fun buildResult(place: PlaceItem, currentLat: Double?, currentLon: Double?): PlaceSearchResult {
        val dist = if (currentLat != null && currentLon != null) {
            calcDistanceMeters(currentLat, currentLon, place.lat, place.lon)
        } else null

        val walkMin = dist?.let { walkDurationMinutes(it) }
        val distText = dist?.let { formatDistance(it) } ?: ""
        val durText = walkMin?.let { formatDuration(it) } ?: ""

        return PlaceSearchResult(
            place = place,
            distanceMeters = dist,
            durationMinutes = walkMin,
            distanceText = distText,
            durationText = durText
        )
    }
}
