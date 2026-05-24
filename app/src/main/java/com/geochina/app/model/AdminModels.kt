package com.geochina.app.model

import androidx.compose.ui.geometry.Offset

enum class AdminLevel(val title: String) {
    Province("省级"),
    City("市级"),
    County("区县级"),
}

data class GeoPoint(
    val x: Float,
    val y: Float,
)

data class GeoRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val center: GeoPoint get() = GeoPoint((left + right) / 2f, (top + bottom) / 2f)

    fun contains(point: GeoPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun intersects(other: GeoRect): Boolean =
        left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top
}

data class RegionStats(
    val areaKm2: String,
    val population: String,
    val density: String,
    val gdp: String,
    val gdpPerCapita: String,
    val populationTrend: List<Float>,
    val areaRank: String,
    val populationRank: String,
)

data class AdministrativeRegion(
    val code: String,
    val name: String,
    val parentCode: String?,
    val parentName: String,
    val level: AdminLevel,
    val initials: String,
    val aliases: List<String>,
    val governmentSeat: String,
    val postalCode: String,
    val phoneCode: String,
    val history: String,
    val subdivisionNames: List<String>,
    val childrenCount: Int,
    val stats: RegionStats,
    val polygons: List<List<GeoPoint>>,
) {
    val bounds: GeoRect by lazy {
        val points = polygons.flatten()
        GeoRect(
            left = points.minOf { it.x },
            top = points.minOf { it.y },
            right = points.maxOf { it.x },
            bottom = points.maxOf { it.y },
        )
    }

    val centroid: GeoPoint by lazy {
        val points = polygons.flatten()
        GeoPoint(
            x = points.map { it.x }.average().toFloat(),
            y = points.map { it.y }.average().toFloat(),
        )
    }
}

data class FocusRequest(
    val region: AdministrativeRegion,
    val nonce: Long,
)

fun GeoPoint.toOffset(): Offset = Offset(x, y)
