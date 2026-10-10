package com.example.offlinetransitmap

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.LocalDateTime

enum class GuidanceKind { WALK, BICYCLE, WAIT, RIDE, ARRIVED }

// ナビ画面に出す、いまの案内
data class Guidance(
    val kind: GuidanceKind,
    val title: String,
    val subtitle: String,
    val distanceMeters: Int?,   // 目標までの直線距離
    val targetLat: Double?,     // 目標(次に向かう停留所など)の位置
    val targetLon: Double?,
    val next: String,           // 「その後、…」の文
    val remainingMinutes: Long, // 最終の到着までの残り
    val arrival: LocalDateTime  // 最終の到着予定
)

// ナビの状態(画面とサービスで共有する)
object NavigationState {
    var active by mutableStateOf(false)
    var itinerary by mutableStateOf<Itinerary?>(null)
    var travelMode by mutableStateOf(TravelMode.WALK)
    var location by mutableStateOf<Location?>(null)
    var guidance by mutableStateOf<Guidance?>(null)
}

// ナビ開始時などに即座に表示するための初期案内
fun computeInitialGuidance(itin: Itinerary, travelMode: TravelMode, location: Location?): Guidance {
    val legs = itin.legs
    val now = LocalDateTime.now()
    val sec = Duration.between(now, itin.arrival).seconds
    val remaining = if (sec <= 0) 0L else (sec + 59) / 60

    val busIdx = legs.indices.filter { !legs[it].isWalk && legs[it].depTime != null && legs[it].arrTime != null && legs[it].lineName != "自転車" }

    if (busIdx.isEmpty()) {
        val destPoint = legs.lastOrNull()?.path?.lastOrNull()
        val destName = legs.lastOrNull()?.toName ?: "目的地"
        val isBike = travelMode == TravelMode.BICYCLE || itin.routeKey == "bike_direct" || legs.any { it.lineName == "自転車" }
        val totalMin = legs.sumOf { it.walkMinutes }
        val modeKind = if (isBike) GuidanceKind.BICYCLE else GuidanceKind.WALK
        val modeVerb = if (isBike) "自転車で向かう" else "徒歩で向かう"
        val d: Int? = if (location != null && destPoint != null) {
            val r = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, destPoint.first, destPoint.second, r)
            r[0].toInt()
        } else {
            legs.sumOf { it.walkMeters }.takeIf { it > 0 }
        }
        return Guidance(
            kind = modeKind,
            title = "${destName}へ",
            subtitle = "$modeVerb · 約${totalMin}分",
            distanceMeters = d,
            targetLat = destPoint?.first,
            targetLon = destPoint?.second,
            next = "目的地に到着",
            remainingMinutes = remaining,
            arrival = itin.arrival
        )
    }

    val firstLeg = legs.firstOrNull()
    if (firstLeg != null && firstLeg.isWalk) {
        val nextIdx = busIdx.firstOrNull()
        val nextLeg = if (nextIdx != null) legs[nextIdx] else null
        val targetPoint = firstLeg.path.lastOrNull()
        val d: Int? = if (location != null && targetPoint != null) {
            val r = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, targetPoint.first, targetPoint.second, r)
            r[0].toInt()
        } else {
            firstLeg.walkMeters.takeIf { it > 0 }
        }
        val nextText = if (nextLeg != null && nextLeg.depTime != null) {
            "その後、${hm(nextLeg.depTime)}発 ${nextLeg.lineName} に乗車"
        } else {
            "その後、乗換"
        }
        return Guidance(
            kind = GuidanceKind.WALK,
            title = "${firstLeg.toName}へ",
            subtitle = "徒歩で向かう · 約${firstLeg.walkMinutes}分",
            distanceMeters = d,
            targetLat = targetPoint?.first,
            targetLon = targetPoint?.second,
            next = nextText,
            remainingMinutes = remaining,
            arrival = itin.arrival
        )
    }

    val curLeg = legs[busIdx.first()]
    val dep = curLeg.depTime ?: now
    val head = "${curLeg.lineName} ${if (curLeg.trainType.isBlank()) "" else curLeg.trainType + " "}${curLeg.headsign}行き"
    val boardPoint = curLeg.path.firstOrNull()
    val d: Int? = if (location != null && boardPoint != null) {
        val r = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, boardPoint.first, boardPoint.second, r)
        r[0].toInt()
    } else null

    return Guidance(
        kind = GuidanceKind.WAIT,
        title = "${curLeg.fromName}で乗車待ち",
        subtitle = "${hm(dep)}発 $head",
        distanceMeters = d,
        targetLat = boardPoint?.first,
        targetLon = boardPoint?.second,
        next = "その後、${curLeg.toName}で降車",
        remainingMinutes = remaining,
        arrival = itin.arrival
    )
}

// ナビの開始・終了
object NavigationController {
    fun start(context: Context, itin: Itinerary, travelMode: TravelMode = TravelMode.WALK) {
        if (!itin.arrival.isAfter(LocalDateTime.now())) {
            Toast.makeText(context, "過去の経路ではナビを開始できません", Toast.LENGTH_LONG).show()
            return
        }
        val effectiveMode = if (itin.routeKey == "bike_direct" || itin.legs.any { it.lineName == "自転車" }) {
            TravelMode.BICYCLE
        } else {
            travelMode
        }
        NavigationState.itinerary = itin
        NavigationState.travelMode = effectiveMode
        NavigationState.guidance = computeInitialGuidance(itin, effectiveMode, NavigationState.location)
        NavigationState.active = true
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NavigationService::class.java)
            )
        } catch (e: Exception) {
            NavigationState.active = false
            Toast.makeText(context, "ナビを開始できませんでした", Toast.LENGTH_LONG).show()
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, NavigationService::class.java))
        NavigationState.active = false
        NavigationState.guidance = null
    }
}

// 位置情報を使い続けて、案内の更新と通知を行うサービス
class NavigationService : Service() {

    companion object {
        private const val CHANNEL_STATUS = "nav_status"
        private const val CHANNEL_ALERT = "nav_alert"
        private const val NOTIF_STATUS = 1001
        private const val ACTION_STOP = "com.example.offlinetransitmap.NAV_STOP"
        private const val TICK_MS = 10_000L
        private const val ARRIVE_M = 120      // 降りる停留所に着いたとみなす距離(m)
        private const val NEAR_M = 500        // 「まもなく」を知らせる距離(m)
        private const val APPROACH_M = 40     // これより離れていたら「徒歩で向かう」案内にする距離(m)
        private const val ARRIVE_WALK_M = 50  // 目的地に着いたとみなす距離(m)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var route: Itinerary? = null
    private var lastLocation: Location? = null
    private val firedAlerts = HashSet<String>()
    private val finished = HashSet<Int>()
    private var nextAlertId = 2000

    // 10秒ごとに状況を見直す(位置が更新されたときにも見直す)
    private val ticker = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            NavigationState.location = location
            evaluate()
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val itin = NavigationState.itinerary
        if (itin == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        route = itin
        firedAlerts.clear()
        finished.clear()
        createChannels()
        val isBike = NavigationState.travelMode == TravelMode.BICYCLE || itin.routeKey == "bike_direct" || itin.legs.any { it.lineName == "自転車" }
        val destName = itin.legs.lastOrNull()?.toName ?: "目的地"
        val statusTitle = if (isBike) "自転車で${destName}へ" else "徒歩で${destName}へ"
        startAsForeground(buildStatus(statusTitle, "案内を開始しました"))
        requestLocation()
        evaluate()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
        } catch (e: Exception) {
            // 何もしない
        }
        NavigationState.active = false
        super.onDestroy()
    }

    private fun startAsForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_STATUS, n)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "ナビ(案内中の表示)", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "ナビ(お知らせ)", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 3000L, 3f, listener, Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 10000L, 20f, listener, Looper.getMainLooper()
                )
            }
            // 最後に取得した位置があれば、最初の位置として使う
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                val l = try {
                    lm.getLastKnownLocation(p)
                } catch (e: Exception) {
                    null
                }
                if (l != null && (lastLocation == null || l.time > lastLocation!!.time)) lastLocation = l
            }
            NavigationState.location = lastLocation
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildStatus(title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openAppIntent())
            .addAction(0, "ナビ終了", stop)
            .build()
    }

    private fun showStatus(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_STATUS, buildStatus(title, text))
    }

    // 音や振動つきのお知らせ(同じ key では1回だけ)
    private fun alert(key: String, title: String, text: String) {
        if (!firedAlerts.add(key)) return
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(nextAlertId++, n)
    }

    private fun distanceTo(point: Pair<Double, Double>?): Int? {
        val l = lastLocation ?: return null
        if (point == null) return null
        val r = FloatArray(1)
        Location.distanceBetween(l.latitude, l.longitude, point.first, point.second, r)
        return r[0].toInt()
    }

    private fun distShort(m: Int?): String = when {
        m == null -> ""
        m >= 1000 -> String.format("%.1fkm", m / 1000.0)
        else -> "${m}m"
    }

    private fun minutesCeil(sec: Long): Long = if (sec <= 0) 0 else (sec + 59) / 60

    // 降りたあとの「その後、…」の文
    private fun afterRideText(pos: Int, busIdx: List<Int>, r: Itinerary): String {
        val i = busIdx[pos]
        if (pos < busIdx.lastIndex) {
            val nextIdx = busIdx[pos + 1]
            val next = r.legs[nextIdx]
            val walk = if (nextIdx > i + 1) r.legs[i + 1] else null
            val w = if (walk != null) "徒歩約${walk.walkMinutes}分で${next.fromName}へ、" else ""
            val d = next.depTime?.let { hm(it) } ?: ""
            return "その後、$w${d}発 ${next.lineName} に乗換"
        }
        val tail = r.legs.getOrNull(i + 1)
        return if (tail != null && tail.isWalk) {
            "その後、徒歩約${tail.walkMinutes}分で${tail.toName}へ"
        } else {
            "その後、目的地に到着"
        }
    }

    // バスの区間 pos(busIdx の何番目か)が終わったときの案内
    private fun onLegFinished(pos: Int, busIdx: List<Int>, r: Itinerary) {
        val i = busIdx[pos]
        val leg = r.legs[i]
        if (pos == busIdx.lastIndex) {
            val tail = r.legs.getOrNull(i + 1)
            if (tail != null && tail.isWalk) {
                alert(
                    "alight_last",
                    "${leg.toName}に着きました",
                    "${tail.toName}まで徒歩約${tail.walkMinutes}分です"
                )
            } else {
                alert("arrive", "到着しました", "${leg.toName}(目的地)に到着しました")
            }
        } else {
            val nextIdx = busIdx[pos + 1]
            val next = r.legs[nextIdx]
            val walk = if (nextIdx > i + 1) r.legs[i + 1] else null
            val walkText = if (walk != null) "徒歩約${walk.walkMinutes}分で${next.fromName}へ。" else ""
            val platform = if (next.platform.isBlank()) "" else "(${next.platform})"
            val depText = next.depTime?.let { hm(it) } ?: ""
            alert(
                "transfer_$pos",
                "乗換: ${next.fromName}",
                "$walkText${depText}発 ${next.lineName} ${next.headsign}行き$platform に乗ります"
            )
        }
    }

    // 現在の状況を見直して、画面の案内と通知を更新する
    private fun evaluate() {
        val r = route ?: return
        val legs = r.legs
        val now = LocalDateTime.now()
        val busIdx = legs.indices.filter { !legs[it].isWalk && legs[it].depTime != null && legs[it].arrTime != null && legs[it].lineName != "自転車" }
        val remaining = minutesCeil(Duration.between(now, r.arrival).seconds)

        if (busIdx.isEmpty()) {
            val destPoint = legs.lastOrNull()?.path?.lastOrNull()
            val destName = legs.lastOrNull()?.toName ?: "目的地"
            if (destPoint == null) {
                stopSelf()
                return
            }
            val d = distanceTo(destPoint)
            if ((d != null && d <= ARRIVE_WALK_M) || now.isAfter(r.arrival.plusMinutes(10))) {
                alert("arrive", "到着しました", "${destName}に到着しました")
                stopSelf()
                return
            }
            val isBike = NavigationState.travelMode == TravelMode.BICYCLE || r.routeKey == "bike_direct" || legs.any { it.lineName == "自転車" }
            val totalMin = legs.sumOf { it.walkMinutes }
            val modeKind = if (isBike) GuidanceKind.BICYCLE else GuidanceKind.WALK
            val modeVerb = if (isBike) "自転車で向かう" else "徒歩で向かう"
            val statusTitle = if (isBike) "自転車で${destName}へ" else "徒歩で${destName}へ"
            NavigationState.guidance = Guidance(
                modeKind, "${destName}へ", "$modeVerb · 約${totalMin}分",
                d, destPoint.first, destPoint.second, "目的地に到着", remaining, r.arrival
            )
            showStatus(statusTitle, "目的地まで${distShort(d)}")
            return
        }

        // 終わったバス区間を判定し、いま乗る(乗っている)区間を決める
        var cur = -1
        for (pos in busIdx.indices) {
            val i = busIdx[pos]
            if (finished.contains(i)) continue
            val leg = legs[i]
            val dep = leg.depTime
            val arr = leg.arrTime
            val near = dep != null && now.isAfter(dep) &&
                    (distanceTo(leg.path.lastOrNull()) ?: Int.MAX_VALUE) <= ARRIVE_M
            val timeOver = arr != null && now.isAfter(arr.plusMinutes(1))
            if (near || timeOver) {
                finished.add(i)
                onLegFinished(pos, busIdx, r)
                continue
            }
            cur = pos
            break
        }

        // すべてのバスを降りたあと
        if (cur == -1) {
            val tail = legs.getOrNull(busIdx.last() + 1)
            val dest = tail?.path?.lastOrNull()
            if (tail == null || dest == null) {
                stopSelf()
                return
            }
            val d = distanceTo(dest)
            if ((d != null && d <= ARRIVE_WALK_M) || now.isAfter(r.arrival.plusMinutes(10))) {
                alert("arrive", "到着しました", "${tail.toName}(目的地)に到着しました")
                stopSelf()
                return
            }
            NavigationState.guidance = Guidance(
                GuidanceKind.WALK, "${tail.toName}へ", "徒歩で向かう · 約${tail.walkMinutes}分",
                d, dest.first, dest.second, "その後、目的地に到着", remaining, r.arrival
            )
            showStatus("徒歩で${tail.toName}へ", "目的地まで${distShort(d)}")
            return
        }

        val leg = legs[busIdx[cur]]
        val dep = leg.depTime ?: return
        val arr = leg.arrTime ?: return
        val head = "${leg.lineName} ${if (leg.trainType.isBlank()) "" else leg.trainType + " "}${leg.headsign}行き"
        val boardPoint = leg.path.firstOrNull()
        val alightPoint = leg.path.lastOrNull()

        if (now.isBefore(dep)) {
            // 乗る前
            val sec = Duration.between(now, dep).seconds
            val platform = if (leg.platform.isBlank()) "" else "(${leg.platform})"
            val boardDist = distanceTo(boardPoint)
            if (sec <= 60) {
                firedAlerts.add("board5_$cur")
                alert("board1_$cur", "まもなく出発(あと1分以内)", "${hm(dep)}発 $head / ${leg.fromName}$platform")
            } else if (sec <= 300) {
                alert("board5_$cur", "乗車まであと${minutesCeil(sec)}分", "${hm(dep)}発 $head / ${leg.fromName}$platform")
            }
            if (boardPoint != null && boardDist != null && boardDist > APPROACH_M) {
                NavigationState.guidance = Guidance(
                    GuidanceKind.WALK, "${leg.fromName}へ", "徒歩で向かう · ${hm(dep)}発 $head$platform",
                    boardDist, boardPoint.first, boardPoint.second,
                    "その後、${hm(dep)}発 ${leg.lineName} に乗車", remaining, r.arrival
                )
            } else {
                NavigationState.guidance = Guidance(
                    GuidanceKind.WAIT, "${leg.fromName}で乗車待ち",
                    "${hm(dep)}発 $head$platform · あと${minutesCeil(sec)}分",
                    null, null, null, "その後、${leg.toName}で降車(${hm(arr)}着)", remaining, r.arrival
                )
            }
            showStatus(
                "あと${minutesCeil(sec)}分で乗車: ${leg.fromName}",
                "${hm(dep)}発 $head$platform" + (if (boardDist != null) " · 約${distShort(boardDist)}" else "")
            )
        } else {
            // 乗っている間
            val sec = Duration.between(now, arr).seconds
            val toDist = distanceTo(alightPoint)
            if ((toDist != null && toDist <= NEAR_M) || sec <= 120) {
                alert("near_$cur", "まもなく${leg.toName}", "降りる準備をしてください(${hm(arr)}着予定)")
            }
            NavigationState.guidance = Guidance(
                GuidanceKind.RIDE, "${leg.toName}で降車",
                "${hm(arr)}着予定 · あと${minutesCeil(sec)}分 · $head",
                toDist, alightPoint?.first, alightPoint?.second,
                afterRideText(cur, busIdx, r), remaining, r.arrival
            )
            showStatus(
                "乗車中: ${leg.toName}まで",
                "${hm(arr)}着予定(あと${minutesCeil(sec)}分)" +
                        (if (toDist != null) " · 約${distShort(toDist)}" else "") + " · $head"
            )
        }
    }
}