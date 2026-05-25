package com.geochina.app.ui

data class MapViewport(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
) {
    val hasBounds: Boolean
        get() = north > south && east > west

    companion object {
        val China = MapViewport(
            latitude = 35.8617,
            longitude = 104.1954,
            zoom = 4.45,
            north = 55.0,
            south = 3.0,
            west = 72.0,
            east = 136.0,
        )
    }
}
