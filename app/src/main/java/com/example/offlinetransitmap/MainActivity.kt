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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    // 任意の場所を長押ししたときの目的地
    var destinationPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
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

    // 目的地カードが開いているときの戻る操作
    BackHandler(enabled = destinationPoint != null && selectedItinerary == null && !navMode && !showSettings) {
        destinationPoint = null
        destinationStation = null
        destinationDistance = null
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
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val itin = pendingNavItinerary
        pendingNavItinerary = null
        if (granted && itin != null) {
            NavigationController.start(context, itin)
        } else {
            Toast.makeText(context, "通知が許可されていないため、ナビを開始できません", Toast.LENGTH_LONG).show()
        }
    }
    val startNavigation: (Itinerary) -> Unit = { itin ->
        if (!hasLocationPermission) {
            permissionLauncher.launch(locationPermissions)
            Toast.makeText(context, "位置情報を許可してから、もう一度「ナビ開始」を押してください", Toast.LENGTH_LONG).show()
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNavItinerary = itin
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NavigationController.start(context, itin)
        }
    }

    val handleDestinationSearch: (startNavImmediately: Boolean) -> Unit = { startNavImmediately ->
        val dest = destinationPoint
        if (dest != null) {
            if (searcher == null) {
                Toast.makeText(context, "経路検索には、新しい timetable.db が必要です", Toast.LENGTH_LONG).show()
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
                        val candidates = withContext(Dispatchers.Default) {
                            searcher.findRoutesBetweenCoordinates(
                                originLat = curLoc.first,
                                originLon = curLoc.second,
                                destLat = dest.first,
                                destLon = dest.second,
                                now = LocalDateTime.now(),
                                destName = destinationStation?.name?.let { "$it 付近" } ?: "目的地"
                            )
                        }
                        isSearchingForDestination = false
                        val bestItin = searcher.rank(candidates, SortMode.FASTEST).firstOrNull() ?: candidates.firstOrNull()
                        if (bestItin != null) {
                            if (startNavImmediately) {
                                startNavigation(bestItin)
                            } else {
                                selectedItinerary = bestItin
                            }
                        } else {
                            Toast.makeText(context, "目的地への経路が見つかりませんでした", Toast.LENGTH_LONG).show()
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
            } else if (!navMode) {
                CenterAlignedTopAppBar(
                    title = { Text("オフライン乗換マップ") },
                    actions = { TextButton(onClick = { showSettings = true }) { Text("設定") } }
                )
            }
        },
        floatingActionButton = {
            if (!showSettings && !showSearch && selectedItinerary == null && !navMode && destinationPoint == null) {
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
                        Icon(Icons.Default.Search, contentDescription = "検索")
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
                mapReloadKey = mapReloadKey,
                onFollowLostChange = { followLost = it },
                onBearingChange = { mapBearing = it },
                onStationClick = { id, name ->
                    if (!navMode) {
                        destinationPoint = null
                        destinationStation = null
                        destinationDistance = null
                        selectedStation = SelectedStation(id, name)
                    }
                },
                onMapClick = { _, _ ->
                    if (!navMode && selectedItinerary == null && destinationPoint != null) {
                        destinationPoint = null
                        destinationStation = null
                        destinationDistance = null
                    }
                },
                onMapLongClick = { lat, lon ->
                    if (!navMode && !showSettings && !showSearch) {
                        selectedStation = null
                        selectedItinerary = null
                        destinationPoint = Pair(lat, lon)
                        destinationStation = null
                        destinationDistance = null
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
                if (!navMode && !showSettings && !showSearch && selectedItinerary == null) {
                    DestinationCard(
                        point = point,
                        nearestStation = destinationStation,
                        nearestDistanceMeters = destinationDistance,
                        isSearching = isSearchingForDestination,
                        onStartNavigation = { handleDestinationSearch(true) },
                        onViewRoute = { handleDestinationSearch(false) },
                        onClose = {
                            destinationPoint = null
                            destinationStation = null
                            destinationDistance = null
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
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
        val departures = remember(station) {
            timetable?.departures(station.id, LocalDateTime.now()) ?: sampleDepartures()
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
            note = note,
            loadTripStops = { d -> timetable?.tripStops(d) ?: emptyList() },
            onDismiss = { selectedStation = null }
        )
    }
}
