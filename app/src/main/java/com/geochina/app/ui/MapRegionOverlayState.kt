package com.geochina.app.ui

import com.geochina.app.model.AdministrativeRegion

data class MapRegionOverlayState(
    val entries: List<MapRegionOverlayEntry>,
    val signature: String,
) {
    companion object {
        val Empty = MapRegionOverlayState(emptyList(), "empty")
    }
}

data class MapRegionOverlayEntry(
    val region: AdministrativeRegion,
    val fillColor: Int,
    val strokeColor: Int,
    val strokeWidthPx: Float,
    val zIndex: Float,
)
