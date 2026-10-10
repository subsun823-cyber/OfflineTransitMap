package com.example.offlinetransitmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.offlinetransitmap.ui.theme.OfflineTransitMapTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val settings = remember {
                AppSettings(applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE))
            }
            val dark = settings.value.theme.isDark(isSystemInDarkTheme())
            OfflineTransitMapTheme(darkTheme = dark) {
                val surface = MaterialTheme.colorScheme.surface.toArgb()
                SideEffect {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = surface
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = surface
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                PreparedOfflineApp { dataNote -> MapScreen(settings, dark, dataNote) }
            }
        }
    }
}

private data class SelectedStation(val id: String, val name: String)

// 端末が最後に取得した現在地(緯度, 経度)。無ければ null
@SuppressLint("MissingPermission")
private fun lastKnownLatLon(context: Context): Pair<Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    var best: Location? = null
    for (p in listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )) {
        val l = try {
            lm.getLastKnownLocation(p)
        } catch (e: Exception) {
            null
        }
        if (l != null && (best == null || l.time > best.time)) best = l
    }
    return best?.let { Pair(it.latitude, it.longitude) }
}

// 現在地アイコン(円と十字の目盛り)。アイコンの追加ライブラリなしで描く
@Composable
private fun MyLocationIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val c = center
        val unit = size.minDimension
        val stroke = unit * 0.09f
        drawCircle(color = color, radius = unit * 0.27f, center = c, style = Stroke(width = stroke))
        drawCircle(color = color, radius = unit * 0.12f, center = c)
        val outer = unit * 0.48f
        val inner = unit * 0.36f
        drawLine(color, Offset(c.x, c.y - outer), Offset(c.x, c.y - inner), strokeWidth = stroke)
        drawLine(color, Offset(c.x, c.y + outer), Offset(c.x, c.y + inner), strokeWidth = stroke)
        drawLine(color, Offset(c.x - outer, c.y), Offset(c.x - inner, c.y), strokeWidth = stroke)
        drawLine(color, Offset(c.x + outer, c.y), Offset(c.x + inner, c.y), strokeWidth = stroke)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(settings: AppSettings, darkTheme: Boolean, dataNote: String) {
    val context = LocalContext.current
    val preferences = settings.value
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // 時刻表DB。無ければ null(従来どおりサンプル表示)
    val timetable = remember { TimetableDb.open(context) }
    val stationsGeoJson by produceState(
        initialValue = if (timetable == null) demoStationsGeoJson() else """{"type":"FeatureCollection","features":[]}""",
        key1 = timetable
    ) {
        if (timetable != null) value = withContext(Dispatchers.IO) { timetable.stationsGeoJson() }
    }
    // 経路検索(DBが新しい形式のときだけ使える)
    val searcher = remember {
        timetable?.let { RouteSearcher(it.db) }?.takeIf { it.isSupported() }
    }
    var selectedStation by remember { mutableStateOf<SelectedStation?>(null) }

    // 経路検索の状態(検索画面を閉じても、入力と結果を残す)
    val searchState = remember { RouteSearchState() }
    var showSearch by remember { mutableStateOf(false) }
    var searchLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // 結果から選ばれた経路(地図に線を出し、詳細パネルを表示する)
    var selectedItinerary by remember { mutableStateOf<Itinerary?>(null) }

    // 施設・場所・駅・バス停の検索
    val poiDb = remember { PoiDatabase.open(context) }
    val placeSearcher = remember(timetable, poiDb) { PlaceSearcher(timetable?.db, poiDb) }
    val roadRouter = remember { RoadRouter.open(context) }
    var showPlaceSearch by remember { mutableStateOf(false) }

    // 目的地の状態（施設名、カテゴリ、アイコン、距離、所要時間など）
    var destinationPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var destinationTitle by remember { mutableStateOf("目的地") }
    var destinationCategoryLabel by remember { mutableStateOf<String?>(null) }
    var destinationIconEmoji by remember { mutableStateOf("📍") }
    var destinationCurrentDistance by remember { mutableStateOf<Int?>(null) }
    var destinationCurrentDuration by remember { mutableStateOf<Int?>(null) }
    var destinationBicycleDuration by remember { mutableStateOf<Int?>(null) }
    var selectedTravelMode by remember { mutableStateOf(TravelMode.WALK) }
    var targetCameraPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var destinationStation by remember { mutableStateOf<StationEntry?>(null) }
    var destinationDistance by remember { mutableStateOf<Int?>(null) }
    var isSearchingForDestination by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ナビ中の経路・案内・地図の向き
    val navItin = if (NavigationState.active) NavigationState.itinerary else null
    val navMode = navItin != null
    val view = LocalView.current
    DisposableEffect(view, navMode, preferences.keepScreenOnDuringNavigation) {
        val previous = view.keepScreenOn
        view.keepScreenOn = navMode && preferences.keepScreenOnDuringNavigation
        onDispose { view.keepScreenOn = previous }
    }
    val overlayItin = navItin ?: selectedItinerary
    val routeOverlay = remember(overlayItin) { overlayItin?.toOverlay() }
    val navLine = if (navMode) navLineGeoJson(NavigationState.location, NavigationState.guidance) else null
    var mapBearing by remember { mutableDoubleStateOf(0.0) }
    var followLost by remember { mutableStateOf(false) }

    // 「現在地に戻る」が押された回数(増えるたびに地図側が現在地へ移動する)
    var recenterRequest by remember { mutableIntStateOf(0) }
    var mapReloadKey by remember { mutableIntStateOf(0) }

    // 経路の詳細パネルで「戻る」操作をしたら、検索結果の画面に戻る
    BackHandler(enabled = selectedItinerary != null && !navMode && !showSettings) {
        selectedItinerary = null
        showSearch = true
    }

    // 施設検索画面の戻る操作
    BackHandler(enabled = showPlaceSearch) { showPlaceSearch = false }

    // 目的地カードが開いているときの戻る操作
    BackHandler(enabled = destinationPoint != null && selectedItinerary == null && !navMode && !showSettings && !showPlaceSearch) {
        destinationPoint = null
        destinationStation = null
        destinationDistance = null
        destinationCurrentDistance = null
        destinationCurrentDuration = null
        destinationBicycleDuration = null
        selectedTravelMode = TravelMode.WALK
        targetCameraPoint = null
    }

    BackHandler(enabled = showSettings) { showSettings = false }

    // 位置情報の許可(正確・おおよそのどちらかが許可されていれば true)
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPermission = result.values.any { it }
    }
    val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    // ナビの開始。Android 13以降は、通知の許可が必要
    var pendingNavItinerary by remember { mutableStateOf<Itinerary?>(null) }
    var pendingNavMode by remember { mutableStateOf(TravelMode.WALK) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val itin = pendingNavItinerary
        val mode = pendingNavMode
        pendingNavItinerary = null
        if (granted && itin != null) {
            NavigationController.start(context, itin, mode)
        } else {
            Toast.makeText(context, "通知が許可されていないため、ナビを開始できません", Toast.LENGTH_LONG).show()
        }
    }
    fun startNavigation(itin: Itinerary, mode: TravelMode = TravelMode.WALK) {
        val effectiveMode = if (itin.routeKey == "bike_direct" || itin.legs.any { it.lineName == "自転車" }) {
            TravelMode.BICYCLE
        } else {
            mode
        }
        if (!hasLocationPermission) {
            permissionLauncher.launch(locationPermissions)
            Toast.makeText(context, "位置情報を許可してから、もう一度「ナビ開始」を押してください", Toast.LENGTH_LONG).show()
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNavItinerary = itin
            pendingNavMode = effectiveMode
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NavigationController.start(context, itin, effectiveMode)
        }
    }

    val handleDestinationSearch: (startNavImmediately: Boolean) -> Unit = { startNavImmediately ->
        val dest = destinationPoint
        if (dest != null) {
            if (searcher == null && roadRouter == null) {
                Toast.makeText(context, "経路検索には、新しいデータが必要です", Toast.LENGTH_LONG).show()
            } else if (!hasLocationPermission) {
                permissionLauncher.launch(locationPermissions)
                Toast.makeText(context, "現在地を取得するため、位置情報を許可してください", Toast.LENGTH_SHORT).show()
            } else {
                val curLoc = lastKnownLatLon(context)
                if (curLoc == null) {
                    Toast.makeText(context, "現在地を取得できませんでした。GPSが有効か確認してください", Toast.LENGTH_LONG).show()
                } else {
                    isSearchingForDestination = true
                    scope.launch {
                        val resolvedName = if (destinationTitle != "目的地を設定しました" && destinationTitle != "目的地") {
                            destinationTitle
                        } else {
                            destinationStation?.name?.let { "$it 付近" } ?: "目的地"
                        }
                        if (startNavImmediately) {
                            // ナビ即時開始の場合：選択された移動モード(徒歩 or 自転車)の道なりダイレクトルートを構築して即座にナビ開始
                            val directItin = withContext(Dispatchers.Default) {
                                val roadRes = roadRouter?.route(curLoc.first, curLoc.second, dest.first, dest.second, selectedTravelMode)
                                val distM = roadRes?.distanceMeters ?: calcDistanceMeters(curLoc.first, curLoc.second, dest.first, dest.second)
                                val durM = roadRes?.durationMinutes ?: travelDurationMinutes(distM, selectedTravelMode)
                                val path = roadRes?.path ?: listOf(curLoc, dest)
                                val isBike = selectedTravelMode == TravelMode.BICYCLE
                                val modeLabel = if (isBike) "自転車" else "徒歩"
                                val leg = RouteLeg(
                                    isWalk = true,
                                    fromName = "現在地",
                                    toName = resolvedName,
                                    walkMinutes = durM,
                                    walkMeters = distM,
                                    lineName = modeLabel,
                                    trainType = modeLabel,
                                    path = path
                                )
                                val now = LocalDateTime.now()
                                Itinerary(
                                    legs = listOf(leg),
                                    departure = now,
                                    arrival = now.plusMinutes(durM.toLong()),
                                    transfers = 0,
                                    fare = 0,
                                    knownFare = 0,
                                    routeKey = if (isBike) "bike_direct" else "walk_direct"
                                )
                            }
                            isSearchingForDestination = false
                            startNavigation(directItin, selectedTravelMode)
                        } else {
                            // ルート確認の場合：電車・バスを含む公共交通候補も検索して提示
                            val candidates = withContext(Dispatchers.Default) {
                                if (searcher != null) {
                                    searcher.findRoutesBetweenCoordinates(
                                        originLat = curLoc.first,
                                        originLon = curLoc.second,
                                        destLat = dest.first,
                                        destLon = dest.second,
                                        now = LocalDateTime.now(),
                                        destName = resolvedName,
                                        travelMode = selectedTravelMode,
                                        roadRouter = roadRouter
                                    )
                                } else {
                                    val roadRes = roadRouter?.route(curLoc.first, curLoc.second, dest.first, dest.second, selectedTravelMode)
                                    val distM = roadRes?.distanceMeters ?: calcDistanceMeters(curLoc.first, curLoc.second, dest.first, dest.second)
                                    val durM = roadRes?.durationMinutes ?: travelDurationMinutes(distM, selectedTravelMode)
                                    val path = roadRes?.path ?: listOf(curLoc, dest)
                                    val isBike = selectedTravelMode == TravelMode.BICYCLE
                                    val modeLabel = if (isBike) "自転車" else "徒歩"
                                    val leg = RouteLeg(
                                        isWalk = true,
                                        fromName = "現在地",
                                        toName = resolvedName,
                                        walkMinutes = durM,
                                        walkMeters = distM,
                                        lineName = modeLabel,
                                        trainType = modeLabel,
                                        path = path
                                    )
                                    listOf(Itinerary(
                                        legs = listOf(leg),
                                        departure = LocalDateTime.now(),
                                        arrival = LocalDateTime.now().plusMinutes(durM.toLong()),
                                        transfers = 0,
                                        fare = 0,
                                        knownFare = 0,
                                        routeKey = if (isBike) "bike_direct" else "walk_direct"
                                    ))
                                }
                            }
                            isSearchingForDestination = false
                            val bestItin = searcher?.rank(candidates, SortMode.FASTEST)?.firstOrNull() ?: candidates.firstOrNull()
                            if (bestItin != null) {
                                selectedItinerary = bestItin
                            } else {
                                Toast.makeText(context, "目的地への経路が見つかりませんでした", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    // 起動時に、まだ許可がなければ確認ダイアログを出す
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(locationPermissions)
        }
    }

    Scaffold(
        topBar = {
            if (showSettings) {
                CenterAlignedTopAppBar(
                    title = { Text("設定") },
                    navigationIcon = { TextButton(onClick = { showSettings = false }) { Text("戻る") } }
                )
            } else if (!navMode && !showPlaceSearch && !showSearch) {
                TopAppBar(
                    title = {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clickable {
                                    searchLocation = if (hasLocationPermission) lastKnownLatLon(context) else null
                                    showPlaceSearch = true
                                },
                            shape = RoundedCornerShape(23.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "検索",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "施設・場所、駅・バス停を検索",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "設定",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    windowInsets = TopAppBarDefaults.windowInsets,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        floatingActionButton = {
            if (!showSettings && !showSearch && !showPlaceSearch && selectedItinerary == null && !navMode && destinationPoint == null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 現在地に戻る
                    SmallFloatingActionButton(
                        onClick = {
                            if (hasLocationPermission) {
                                recenterRequest++
                            } else {
                                permissionLauncher.launch(locationPermissions)
                            }
                        }
                    ) {
                        MyLocationIcon(color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    // 経路検索
                    FloatingActionButton(
                        onClick = {
                            if (searcher == null) {
                                Toast.makeText(
                                    context,
                                    "経路検索には、新しい timetable.db が必要です",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                searchLocation = if (hasLocationPermission) lastKnownLatLon(context) else null
                                showSearch = true
                            }
                        }
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "経路検索")
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            MapLibreMapView(
                modifier = Modifier.fillMaxSize(),
                stationsGeoJson = stationsGeoJson,
                preferences = preferences,
                darkTheme = darkTheme,
                stationMinZoom = if (timetable != null) 12.0 else 0.0,
                locationGranted = hasLocationPermission,
                recenterRequest = recenterRequest,
                routeOverlay = routeOverlay,
                followMode = navMode,
                navLineJson = navLine,
                destinationPoint = destinationPoint,
                destinationName = destinationTitle,
                targetCameraPoint = targetCameraPoint,
                mapReloadKey = mapReloadKey,
                onFollowLostChange = { followLost = it },
                onBearingChange = { mapBearing = it },
                onStationClick = { id, name ->
                    if (!navMode) {
                        destinationPoint = null
                        destinationStation = null
                        destinationDistance = null
                        destinationCurrentDistance = null
                        destinationCurrentDuration = null
                        destinationBicycleDuration = null
                        selectedTravelMode = TravelMode.WALK
                        targetCameraPoint = null
                        selectedStation = SelectedStation(id, name)
                    }
                },
                onMapClick = { _, _ ->
                    if (!navMode && selectedItinerary == null && destinationPoint != null) {
                        destinationPoint = null
                        destinationStation = null
                        destinationDistance = null
                        destinationCurrentDistance = null
                        destinationCurrentDuration = null
                        destinationBicycleDuration = null
                        selectedTravelMode = TravelMode.WALK
                        targetCameraPoint = null
                    }
                },
                onMapLongClick = { lat, lon ->
                    if (!navMode && !showSettings && !showSearch && !showPlaceSearch) {
                        selectedStation = null
                        selectedItinerary = null
                        destinationPoint = Pair(lat, lon)
                        destinationTitle = "目的地を設定しました"
                        destinationCategoryLabel = null
                        destinationIconEmoji = "📍"
                        destinationStation = null
                        destinationDistance = null
                        targetCameraPoint = null
                        selectedTravelMode = TravelMode.WALK
                        val curLoc = if (hasLocationPermission) lastKnownLatLon(context) else null
                        if (curLoc != null) {
                            val directDist = calcDistanceMeters(curLoc.first, curLoc.second, lat, lon)
                            destinationCurrentDistance = directDist
                            destinationCurrentDuration = walkDurationMinutes(directDist)
                            destinationBicycleDuration = bicycleDurationMinutes(directDist)
                            scope.launch(Dispatchers.Default) {
                                val roadWalk = roadRouter?.route(curLoc.first, curLoc.second, lat, lon, TravelMode.WALK)
                                val roadBike = roadRouter?.route(curLoc.first, curLoc.second, lat, lon, TravelMode.BICYCLE)
                                withContext(Dispatchers.Main) {
                                    if (destinationPoint == Pair(lat, lon)) {
                                        if (roadWalk != null) {
                                            destinationCurrentDistance = roadWalk.distanceMeters
                                            destinationCurrentDuration = roadWalk.durationMinutes
                                        }
                                        if (roadBike != null) {
                                            destinationBicycleDuration = roadBike.durationMinutes
                                        }
                                    }
                                }
                            }
                        } else {
                            destinationCurrentDistance = null
                            destinationCurrentDuration = null
                            destinationBicycleDuration = null
                        }
                        scope.launch(Dispatchers.Default) {
                            val nearest = searcher?.nearestStation(lat, lon)
                            val dist = if (nearest != null) calcDistanceMeters(lat, lon, nearest.lat, nearest.lon) else null
                            withContext(Dispatchers.Main) {
                                if (destinationPoint == Pair(lat, lon)) {
                                    destinationStation = nearest
                                    destinationDistance = dist
                                }
                            }
                        }
                    }
                }
            )
            destinationPoint?.let { point ->
                if (!navMode && !showSettings && !showSearch && !showPlaceSearch && selectedItinerary == null) {
                    DestinationCard(
                        point = point,
                        title = destinationTitle,
                        categoryLabel = destinationCategoryLabel,
                        iconEmoji = destinationIconEmoji,
                        currentLocationDistanceMeters = destinationCurrentDistance,
                        currentLocationDurationMinutes = destinationCurrentDuration,
                        currentLocationBicycleDurationMinutes = destinationBicycleDuration,
                        selectedTravelMode = selectedTravelMode,
                        onTravelModeChange = { selectedTravelMode = it },
                        nearestStation = destinationStation,
                        nearestDistanceMeters = destinationDistance,
                        isSearching = isSearchingForDestination,
                        onStartNavigation = { handleDestinationSearch(true) },
                        onViewRoute = { handleDestinationSearch(false) },
                        onClose = {
                            destinationPoint = null
                            destinationStation = null
                            destinationDistance = null
                            destinationCurrentDistance = null
                            destinationCurrentDuration = null
                            destinationBicycleDuration = null
                            selectedTravelMode = TravelMode.WALK
                            targetCameraPoint = null
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
            if (showPlaceSearch && !navMode && !showSettings) {
                PlaceSearchScreen(
                    searcher = placeSearcher,
                    currentLocation = searchLocation,
                    onSelectPlace = { res ->
                        showPlaceSearch = false
                        selectedStation = null
                        selectedItinerary = null
                        val p = res.place
                        val point = Pair(p.lat, p.lon)
                        destinationPoint = point
                        destinationTitle = p.name
                        destinationCategoryLabel = if (p.detail.isNotBlank()) "${p.category.label} · ${p.detail}" else p.category.label
                        destinationIconEmoji = p.category.iconEmoji
                        destinationCurrentDistance = res.distanceMeters
                        destinationCurrentDuration = res.durationMinutes
                        destinationBicycleDuration = res.distanceMeters?.let { bicycleDurationMinutes(it) }
                        selectedTravelMode = TravelMode.WALK
                        targetCameraPoint = point
                        val curLoc = searchLocation
                        if (curLoc != null) {
                            scope.launch(Dispatchers.Default) {
                                val roadWalk = roadRouter?.route(curLoc.first, curLoc.second, p.lat, p.lon, TravelMode.WALK)
                                val roadBike = roadRouter?.route(curLoc.first, curLoc.second, p.lat, p.lon, TravelMode.BICYCLE)
                                withContext(Dispatchers.Main) {
                                    if (destinationPoint == point) {
                                        if (roadWalk != null) {
                                            destinationCurrentDistance = roadWalk.distanceMeters
                                            destinationCurrentDuration = roadWalk.durationMinutes
                                        }
                                        if (roadBike != null) {
                                            destinationBicycleDuration = roadBike.durationMinutes
                                        }
                                    }
                                }
                            }
                        }
                        scope.launch(Dispatchers.Default) {
                            val nearest = searcher?.nearestStation(p.lat, p.lon)
                            val dist = if (nearest != null) calcDistanceMeters(p.lat, p.lon, nearest.lat, nearest.lon) else null
                            withContext(Dispatchers.Main) {
                                if (destinationPoint == point) {
                                    destinationStation = nearest
                                    destinationDistance = dist
                                }
                            }
                        }
                    },
                    onClose = { showPlaceSearch = false }
                )
            }
            if (showSearch && searcher != null && !navMode && !showSettings) {
                RouteSearchScreen(
                    state = searchState,
                    searcher = searcher,
                    currentLocation = searchLocation,
                    onSelect = { itin ->
                        selectedItinerary = itin
                        showSearch = false
                    },
                    onClose = { showSearch = false }
                )
            }
            selectedItinerary?.let { itin ->
                if (!navMode && !showSettings) {
                    RouteDetailPanel(
                        itin = itin,
                        onBackToResults = {
                            selectedItinerary = null
                            showSearch = true
                        },
                        onClose = { selectedItinerary = null },
                        modifier = Modifier.align(Alignment.BottomCenter),
                        navigating = NavigationState.active && NavigationState.itinerary === itin,
                        onStartNavigation = { startNavigation(itin) },
                        onStopNavigation = {
                            NavigationController.stop(context)
                            destinationPoint = null
                            destinationStation = null
                            destinationDistance = null
                            destinationCurrentDistance = null
                            destinationCurrentDuration = null
                            targetCameraPoint = null
                        }
                    )
                }
            }
            if (navMode && !showSettings) {
                NavigationOverlay(
                    guidance = NavigationState.guidance,
                    location = NavigationState.location,
                    mapBearing = mapBearing,
                    followLost = followLost,
                    onRecenter = { recenterRequest++ },
                    onStop = {
                        NavigationController.stop(context)
                        destinationPoint = null
                        destinationStation = null
                        destinationDistance = null
                        destinationCurrentDistance = null
                        destinationCurrentDuration = null
                        targetCameraPoint = null
                    }
                )
            }
            if (showSettings) {
                SettingsScreen(
                    preferences = preferences,
                    onChange = settings::update,
                    dataNote = dataNote,
                    onMapUpdated = { mapReloadKey++ }
                )
            }
        }
    }

    selectedStation?.let { station ->
        val now = remember(station) { LocalDateTime.now() }
        var selectedLine by remember(station) { mutableStateOf<String?>(null) }

        val departures = remember(station, selectedLine) {
            timetable?.departures(
                stationId = station.id,
                now = now,
                lineName = selectedLine,
                stationName = station.name
            ) ?: (if (selectedLine == null) sampleDepartures() else sampleDepartures().filter { it.lineName == selectedLine })
        }
        val pastDepartures = remember(station, selectedLine) {
            timetable?.pastDepartures(
                stationId = station.id,
                now = now,
                windowMinutes = 60,
                lineName = selectedLine,
                stationName = station.name
            ) ?: (if (selectedLine == null) samplePastDepartures() else samplePastDepartures().filter { it.lineName == selectedLine })
        }
        val railLines = remember(station) {
            timetable?.stationRailLines(station.id, station.name) ?: sampleStationLines()
        }
        val note = remember(station) {
            if (timetable != null) {
                listOfNotNull(timetable.validityText()?.let { "時刻表の有効期間: $it" }, timetable.stationDataNote(station.id)).joinToString("\n").ifBlank { null }
            } else {
                "※サンプルデータです"
            }
        }
        DepartureSheet(
            stationName = station.name,
            departures = departures,
            pastDepartures = pastDepartures,
            railLines = railLines,
            selectedLine = selectedLine,
            onSelectLine = { selectedLine = it },
            note = note,
            loadTripStops = { d -> timetable?.tripStops(d) ?: emptyList() },
            onDismiss = { selectedStation = null }
        )
    }
}
