package com.geochina.app.ui

import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.geochina.app.R
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

@Composable
fun OnlineMapBasemap(
    viewport: MapViewport,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val styleUrl = remember(context) { context.getString(R.string.online_basemap_style_url) }
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
        }
    }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    mapLibreMap = map
                    map.setTileCacheEnabled(true)
                    map.uiSettings.setAllGesturesEnabled(false)
                    map.uiSettings.isCompassEnabled = false
                    map.uiSettings.isLogoEnabled = true
                    map.uiSettings.setLogoGravity(Gravity.BOTTOM or Gravity.START)
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.START)
                    map.setStyle(styleUrl)
                    map.moveTo(viewport)
                }
            }
        },
        update = {
            mapLibreMap?.moveTo(viewport)
        },
    )
}

private fun MapLibreMap.moveTo(viewport: MapViewport) {
    val position = CameraPosition.Builder()
        .target(LatLng(viewport.latitude, viewport.longitude))
        .zoom(viewport.zoom)
        .tilt(0.0)
        .bearing(0.0)
        .build()
    moveCamera(CameraUpdateFactory.newCameraPosition(position))
}
