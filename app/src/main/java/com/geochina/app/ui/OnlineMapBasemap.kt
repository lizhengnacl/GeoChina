package com.geochina.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds

@Composable
fun OnlineMapBasemap(
    viewport: MapViewport,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        TextureMapView(context).apply {
            onCreate(null)
        }
    }
    val aMap = remember(mapView) {
        mapView.map.apply {
            mapType = AMap.MAP_TYPE_NORMAL
            showMapText(true)
            uiSettings.isCompassEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isScaleControlsEnabled = false
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.setAllGesturesEnabled(false)
            moveTo(viewport)
        }
    }

    DisposableEffect(lifecycle, mapView) {
        var destroyed = false
        fun destroyMapView() {
            if (!destroyed) {
                destroyed = true
                mapView.onDestroy()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> destroyMapView()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            destroyMapView()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = {
            aMap.moveTo(viewport)
        },
    )
}

private fun AMap.moveTo(viewport: MapViewport) {
    if (viewport.hasBounds) {
        val bounds = LatLngBounds.Builder()
            .include(LatLng(viewport.south, viewport.west))
            .include(LatLng(viewport.north, viewport.east))
            .build()
        val boundsUpdate = CameraUpdateFactory.newLatLngBoundsRect(bounds, 0, 0, 0, 0)
        if (runCatching { moveCamera(boundsUpdate) }.isSuccess) {
            return
        }
    }

    val position = CameraPosition.Builder()
        .target(LatLng(viewport.latitude, viewport.longitude))
        .zoom(viewport.zoom.toFloat().coerceIn(3f, 20f))
        .tilt(0f)
        .bearing(0f)
        .build()
    moveCamera(CameraUpdateFactory.newCameraPosition(position))
}
