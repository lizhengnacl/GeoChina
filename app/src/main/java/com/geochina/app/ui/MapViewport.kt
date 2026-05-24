package com.geochina.app.ui

data class MapViewport(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
) {
    companion object {
        val China = MapViewport(
            latitude = 35.8617,
            longitude = 104.1954,
            zoom = 3.45,
        )
    }
}
