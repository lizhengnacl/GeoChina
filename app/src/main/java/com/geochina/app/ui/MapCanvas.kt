package com.geochina.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.geochina.app.data.ChinaAdminDataset
import com.geochina.app.model.AdminLevel
import com.geochina.app.model.AdministrativeRegion
import com.geochina.app.model.FocusRequest
import com.geochina.app.model.GeoPoint
import com.geochina.app.model.GeoRect
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MinMapScale = 0.85f
private const val MaxMapScale = 80f

@Composable
fun AdminMapCanvas(
    currentLevel: AdminLevel,
    selectedRegion: AdministrativeRegion?,
    focusRequest: FocusRequest?,
    zoomCommand: ZoomCommand?,
    darkTheme: Boolean,
    onLevelChanged: (AdminLevel) -> Unit,
    onRegionCandidates: (List<AdministrativeRegion>) -> Unit,
    onBlankTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val palette = remember(darkTheme) { mapPalette(darkTheme) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var renderedLevel by remember { mutableStateOf(currentLevel) }
    var previousLevel by remember { mutableStateOf(currentLevel) }
    var stableProvinceCode by remember { mutableStateOf<String?>(null) }
    var stableCityCode by remember { mutableStateOf<String?>(null) }
    val transition = remember { Animatable(1f) }

    fun baseScaleFor(size: IntSize): Float {
        if (size.width == 0 || size.height == 0) return 1f
        val bounds = ChinaAdminDataset.worldBounds
        return min(size.width / bounds.width, size.height / bounds.height) * 0.88f
    }

    fun screenCenter(size: IntSize): Offset = Offset(size.width / 2f, size.height / 2f)

    fun worldToScreen(point: GeoPoint, size: IntSize = canvasSize, drawScale: Float = scale, drawPan: Offset = pan): Offset {
        val bounds = ChinaAdminDataset.worldBounds
        val baseScale = baseScaleFor(size)
        val center = screenCenter(size)
        return Offset(
            x = center.x + (point.x - bounds.center.x) * baseScale * drawScale + drawPan.x,
            y = center.y + (point.y - bounds.center.y) * baseScale * drawScale + drawPan.y,
        )
    }

    fun screenToWorld(screen: Offset): GeoPoint {
        val bounds = ChinaAdminDataset.worldBounds
        val baseScale = baseScaleFor(canvasSize)
        val center = screenCenter(canvasSize)
        return GeoPoint(
            x = (screen.x - center.x - pan.x) / (baseScale * scale) + bounds.center.x,
            y = (screen.y - center.y - pan.y) / (baseScale * scale) + bounds.center.y,
        )
    }

    fun viewportCenterWorld(): GeoPoint =
        if (canvasSize.width == 0 || canvasSize.height == 0) {
            ChinaAdminDataset.worldBounds.center
        } else {
            screenToWorld(screenCenter(canvasSize))
        }

    fun viewportWorldBounds(): GeoRect {
        if (canvasSize.width == 0 || canvasSize.height == 0) return ChinaAdminDataset.worldBounds
        val topLeft = screenToWorld(Offset.Zero)
        val bottomRight = screenToWorld(Offset(canvasSize.width.toFloat(), canvasSize.height.toFloat()))
        return GeoRect(
            left = min(topLeft.x, bottomRight.x),
            top = min(topLeft.y, bottomRight.y),
            right = max(topLeft.x, bottomRight.x),
            bottom = max(topLeft.y, bottomRight.y),
        )
    }

    fun regionAt(point: GeoPoint, candidates: List<AdministrativeRegion>): AdministrativeRegion? =
        candidates.firstOrNull { region ->
            region.bounds.contains(point) && region.polygons.any { polygon -> polygonContains(polygon, point) }
        }

    fun nearestVisibleRegion(
        point: GeoPoint,
        candidates: List<AdministrativeRegion>,
    ): AdministrativeRegion? {
        val viewportBounds = viewportWorldBounds()
        return candidates
            .filter { it.bounds.intersects(viewportBounds) }
            .minByOrNull { distanceSquared(it.bounds.center, point) }
    }

    fun selectedAncestor(level: AdminLevel): AdministrativeRegion? {
        var region = selectedRegion
        while (region != null) {
            if (region.level == level) return region
            region = region.parentCode?.let { ChinaAdminDataset.region(it) }
        }
        return null
    }

    fun focusedProvince(): AdministrativeRegion? {
        val point = viewportCenterWorld()
        val provinces = ChinaAdminDataset.regionsForLevel(AdminLevel.Province)
        return regionAt(point, provinces)
            ?: stableProvinceCode?.let { ChinaAdminDataset.regionForLevel(it, AdminLevel.Province) }
            ?: selectedAncestor(AdminLevel.Province)
            ?: nearestVisibleRegion(point, provinces)
    }

    fun focusedCity(): AdministrativeRegion? {
        val point = viewportCenterWorld()
        val provinceCode = focusedProvince()?.code
        val cityCandidates = provinceCode
            ?.let { ChinaAdminDataset.regionsForParent(AdminLevel.City, it) }
            .orEmpty()
        return regionAt(point, cityCandidates)
            ?: stableCityCode
                ?.let { ChinaAdminDataset.regionForLevel(it, AdminLevel.City) }
                ?.takeIf { it.parentCode == provinceCode }
            ?: selectedAncestor(AdminLevel.City)?.takeIf { it.parentCode == provinceCode }
            ?: nearestVisibleRegion(point, cityCandidates)
    }

    fun primaryRegions(level: AdminLevel): List<AdministrativeRegion> {
        return when (level) {
            AdminLevel.Province -> ChinaAdminDataset.regionsForLevel(AdminLevel.Province)
            AdminLevel.City -> {
                val parentCode = focusedProvince()?.code
                parentCode
                    ?.let { ChinaAdminDataset.regionsForParent(AdminLevel.City, it) }
                    .orEmpty()
            }
            AdminLevel.County -> {
                val city = focusedCity()
                val cityChildren = city?.let { ChinaAdminDataset.regionsForParent(AdminLevel.County, it.code) }.orEmpty()
                if (cityChildren.isNotEmpty()) {
                    cityChildren
                } else {
                    val provinceCode = city?.parentCode ?: focusedProvince()?.code
                    val directChildren = provinceCode?.let { ChinaAdminDataset.regionsForParent(AdminLevel.County, it) }.orEmpty()
                    directChildren
                }
            }
        }
    }

    fun renderGroups(level: AdminLevel): List<RegionDrawGroup> {
        val provinces = ChinaAdminDataset.regionsForLevel(AdminLevel.Province)
        val primary = primaryRegions(level)
        return when (level) {
            AdminLevel.Province -> listOf(
                RegionDrawGroup(
                    level = AdminLevel.Province,
                    regions = primary,
                    fillAlpha = 0.82f,
                    strokeAlpha = 0.65f,
                    labelAlpha = 1f,
                ),
            )
            AdminLevel.City -> listOf(
                RegionDrawGroup(
                    level = AdminLevel.Province,
                    regions = provinces,
                    fillAlpha = 0.12f,
                    strokeAlpha = 0.28f,
                    labelAlpha = 0.42f,
                ),
                RegionDrawGroup(
                    level = AdminLevel.City,
                    regions = primary,
                    fillAlpha = 0.82f,
                    strokeAlpha = 0.65f,
                    labelAlpha = 1f,
                ),
            )
            AdminLevel.County -> {
                val focusedProvinceCode = focusedProvince()?.code
                val focusedCityCode = focusedCity()?.code
                val primaryCodes = primary.mapTo(mutableSetOf()) { it.code }
                val cityContext = focusedProvinceCode
                    ?.let { ChinaAdminDataset.regionsForParent(AdminLevel.City, it) }
                    .orEmpty()
                    .filterNot { it.code == focusedCityCode || it.code in primaryCodes }

                listOf(
                    RegionDrawGroup(
                        level = AdminLevel.Province,
                        regions = provinces,
                        fillAlpha = 0.08f,
                        strokeAlpha = 0.22f,
                        labelAlpha = 0.28f,
                    ),
                    RegionDrawGroup(
                        level = AdminLevel.City,
                        regions = cityContext,
                        fillAlpha = 0.14f,
                        strokeAlpha = 0.34f,
                        labelAlpha = 0.48f,
                    ),
                    RegionDrawGroup(
                        level = AdminLevel.County,
                        regions = primary,
                        fillAlpha = 0.82f,
                        strokeAlpha = 0.65f,
                        labelAlpha = 1f,
                    ),
                )
            }
        }.filter { it.regions.isNotEmpty() }
    }

    fun targetPanFor(region: AdministrativeRegion, targetScale: Float): Offset {
        val projected = worldToScreen(region.bounds.center, drawScale = targetScale, drawPan = Offset.Zero)
        return screenCenter(canvasSize) - projected
    }

    fun zoomAround(screenPoint: Offset, factor: Float) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return
        val worldPoint = screenToWorld(screenPoint)
        val newScale = (scale * factor).coerceIn(MinMapScale, MaxMapScale)
        val baseScale = baseScaleFor(canvasSize)
        val center = screenCenter(canvasSize)
        val bounds = ChinaAdminDataset.worldBounds
        scale = newScale
        pan = Offset(
            x = screenPoint.x - center.x - (worldPoint.x - bounds.center.x) * baseScale * newScale,
            y = screenPoint.y - center.y - (worldPoint.y - bounds.center.y) * baseScale * newScale,
        )
    }

    fun handleTap(screenPoint: Offset) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return
        val worldPoint = screenToWorld(screenPoint)
        val seenCodes = mutableSetOf<String>()
        val candidates = renderGroups(renderedLevel)
            .asReversed()
            .flatMap { it.regions }
            .filter { region ->
                seenCodes.add(region.code) &&
                    region.bounds.contains(worldPoint) &&
                    region.polygons.any { polygon -> polygonContains(polygon, worldPoint) }
            }
        if (candidates.isEmpty()) {
            onBlankTap()
        } else {
            onRegionCandidates(candidates)
        }
    }

    LaunchedEffect(scale) {
        delay(140)
        onLevelChanged(levelForScale(scale))
    }

    LaunchedEffect(canvasSize, pan, scale, selectedRegion?.code) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
        val center = viewportCenterWorld()
        val provinces = ChinaAdminDataset.regionsForLevel(AdminLevel.Province)
        val centerProvince = regionAt(center, provinces)
        val selectedProvince = selectedAncestor(AdminLevel.Province)
        val retainedProvince = stableProvinceCode?.let { ChinaAdminDataset.regionForLevel(it, AdminLevel.Province) }
        val nextProvince = centerProvince
            ?: retainedProvince
            ?: selectedProvince
            ?: nearestVisibleRegion(center, provinces)

        if (nextProvince?.code != stableProvinceCode) {
            stableProvinceCode = nextProvince?.code
            stableCityCode = null
        }

        val cityCandidates = nextProvince
            ?.let { ChinaAdminDataset.regionsForParent(AdminLevel.City, it.code) }
            .orEmpty()
        val centerCity = regionAt(center, cityCandidates)
        val retainedCity = stableCityCode
            ?.let { ChinaAdminDataset.regionForLevel(it, AdminLevel.City) }
            ?.takeIf { it.parentCode == nextProvince?.code }
        val selectedCity = selectedAncestor(AdminLevel.City)
            ?.takeIf { it.parentCode == nextProvince?.code }
        val nextCity = centerCity
            ?: retainedCity
            ?: selectedCity
            ?: nearestVisibleRegion(center, cityCandidates)

        if (nextCity?.code != stableCityCode) {
            stableCityCode = nextCity?.code
        }
    }

    LaunchedEffect(currentLevel) {
        if (currentLevel != renderedLevel) {
            previousLevel = renderedLevel
            renderedLevel = currentLevel
            transition.snapTo(0f)
            transition.animateTo(1f, tween(durationMillis = 360))
        }
    }

    LaunchedEffect(focusRequest?.nonce, canvasSize) {
        val region = focusRequest?.region ?: return@LaunchedEffect
        if (canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
        val baseScale = baseScaleFor(canvasSize)
        val fitScale = min(
            canvasSize.width * 0.58f / max(1f, region.bounds.width * baseScale),
            canvasSize.height * 0.58f / max(1f, region.bounds.height * baseScale),
        )
        val minimumScale = when (region.level) {
            AdminLevel.Province -> 1.35f
            AdminLevel.City -> 3.1f
            AdminLevel.County -> 14f
        }
        val targetScale = max(fitScale, minimumScale).coerceIn(0.95f, MaxMapScale)
        val targetPan = targetPanFor(region, targetScale)
        val startScale = scale
        val startPan = pan
        animate(0f, 1f, animationSpec = tween(durationMillis = 480)) { value, _ ->
            scale = lerp(startScale, targetScale, value)
            pan = lerp(startPan, targetPan, value)
        }
    }

    LaunchedEffect(zoomCommand?.nonce, canvasSize) {
        val command = zoomCommand ?: return@LaunchedEffect
        if (canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
        zoomAround(screenCenter(canvasSize), command.factor)
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, gesturePan, zoom, _ ->
                    if (abs(zoom - 1f) > 0.002f) {
                        zoomAround(centroid, zoom)
                    } else {
                        pan += gesturePan
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = ::handleTap,
                    onDoubleTap = { zoomAround(it, 1.7f) },
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val first = awaitPointerEvent()
                        val downCount = first.changes.count { it.pressed }
                        if (downCount < 2) continue
                        val startPositions = first.changes.filter { it.pressed }.map { it.position }
                        var moved = false
                        var released = false
                        while (!released) {
                            val event = awaitPointerEvent()
                            moved = moved || event.changes.any { change ->
                                startPositions.any { start -> (change.position - start).getDistance() > 20f }
                            }
                            released = event.changes.none { it.pressed }
                        }
                        if (!moved) {
                            zoomAround(startPositions.first(), 0.68f)
                        }
                    }
                }
            },
    ) {
        drawRect(palette.sea)
        drawAuxiliaryBoundaries(
            palette = palette,
            scale = scale,
            pan = pan,
            worldToScreen = ::worldToScreen,
            density = density.density,
        )
        val selectedCode = selectedRegion?.code
        val progress = transition.value
        if (progress < 1f && previousLevel != renderedLevel) {
            renderGroups(previousLevel).forEach { group ->
                drawLayer(
                    group = group,
                    alpha = 1f - progress,
                    selectedCode = selectedCode,
                    palette = palette,
                    scale = scale,
                    pan = pan,
                    worldToScreen = ::worldToScreen,
                    density = density.density,
                )
            }
        }
        renderGroups(renderedLevel).forEach { group ->
            drawLayer(
                group = group,
                alpha = progress,
                selectedCode = selectedCode,
                palette = palette,
                scale = scale,
                pan = pan,
                worldToScreen = ::worldToScreen,
                density = density.density,
            )
        }
    }
}

data class ZoomCommand(
    val factor: Float,
    val nonce: Long,
)

private data class MapPalette(
    val sea: Color,
    val boundary: Color,
    val label: Color,
    val selectedFill: Color,
    val selectedStroke: Color,
    val provinceFills: List<Color>,
    val cityFills: List<Color>,
    val countyFills: List<Color>,
    val nineDash: Color,
)

private data class RegionDrawGroup(
    val level: AdminLevel,
    val regions: List<AdministrativeRegion>,
    val fillAlpha: Float,
    val strokeAlpha: Float,
    val labelAlpha: Float,
)

private fun mapPalette(darkTheme: Boolean): MapPalette =
    if (darkTheme) {
        MapPalette(
            sea = Color(0xFF18384C),
            boundary = Color(0xFFD6B29A),
            label = Color(0xFFFFF8EE),
            selectedFill = Color(0xFFCF4A3D),
            selectedStroke = Color(0xFFFFE2C8),
            provinceFills = listOf(
                Color(0xFF6F8F72),
                Color(0xFF8F785C),
                Color(0xFF647F9E),
                Color(0xFF936D78),
                Color(0xFF6B918E),
                Color(0xFF8C875E),
                Color(0xFF7B7399),
                Color(0xFF9A6F58),
            ),
            cityFills = listOf(
                Color(0xFF7EA480),
                Color(0xFFA68D69),
                Color(0xFF7595BA),
                Color(0xFFA87E8B),
                Color(0xFF78A7A2),
                Color(0xFFA49F70),
                Color(0xFF9186B1),
                Color(0xFFB08367),
            ),
            countyFills = listOf(
                Color(0xFF8CB889),
                Color(0xFFBCA06E),
                Color(0xFF86A8C9),
                Color(0xFFBC8D9B),
                Color(0xFF8ABBB5),
                Color(0xFFB8B27B),
                Color(0xFFA297C6),
                Color(0xFFC49372),
            ),
            nineDash = Color(0xFFF0C49D),
        )
    } else {
        MapPalette(
            sea = Color(0xFFD6EBF7),
            boundary = Color(0xFF9A5645),
            label = Color(0xFF39271E),
            selectedFill = Color(0xFFC7352A),
            selectedStroke = Color(0xFF7E1E18),
            provinceFills = listOf(
                Color(0xFFF5D8A6),
                Color(0xFFCFE5B6),
                Color(0xFFBCD6EA),
                Color(0xFFEFC0A8),
                Color(0xFFC8E0DC),
                Color(0xFFE7D2A0),
                Color(0xFFD8C8E9),
                Color(0xFFD8E0A9),
            ),
            cityFills = listOf(
                Color(0xFFF7C987),
                Color(0xFFB8D99B),
                Color(0xFFA9C9E6),
                Color(0xFFE7A98F),
                Color(0xFFAED7D1),
                Color(0xFFE3CC7D),
                Color(0xFFCAB6E1),
                Color(0xFFC9D787),
            ),
            countyFills = listOf(
                Color(0xFFFFD69A),
                Color(0xFFC6E6A5),
                Color(0xFFB7D8F0),
                Color(0xFFF1B89E),
                Color(0xFFBEE4DF),
                Color(0xFFEBD88F),
                Color(0xFFD9C4EE),
                Color(0xFFD8E79A),
            ),
            nineDash = Color(0xFFB14A3D),
        )
    }

private fun DrawScope.drawLayer(
    group: RegionDrawGroup,
    alpha: Float,
    selectedCode: String?,
    palette: MapPalette,
    scale: Float,
    pan: Offset,
    worldToScreen: (GeoPoint, IntSize, Float, Offset) -> Offset,
    density: Float,
) {
    val strokeWidth = when (group.level) {
        AdminLevel.Province -> 1.4f
        AdminLevel.City -> 1.0f
        AdminLevel.County -> 0.7f
    } * density * (1f / scale).coerceIn(0.35f, 1f)
    group.regions.forEachIndexed { index, region ->
        val path = region.toPath { point -> worldToScreen(point, IntSize(size.width.toInt(), size.height.toInt()), scale, pan) }
        val selected = region.code == selectedCode
        val fill = if (selected) {
            palette.selectedFill
        } else {
            palette.colorFor(group.level, region, index)
        }
        drawPath(path, fill.copy(alpha = group.fillAlpha * alpha))
        drawPath(
            path = path,
            color = if (selected) palette.selectedStroke.copy(alpha = alpha) else palette.boundary.copy(alpha = group.strokeAlpha * alpha),
            style = Stroke(width = if (selected) strokeWidth * 2.4f else strokeWidth),
        )
    }
    if (group.labelAlpha > 0f) {
        drawLabels(group.level, group.regions, alpha * group.labelAlpha, palette, scale, pan, worldToScreen, density)
    }
}

private fun MapPalette.colorFor(
    level: AdminLevel,
    region: AdministrativeRegion,
    index: Int,
): Color {
    val palette = when (level) {
        AdminLevel.Province -> provinceFills
        AdminLevel.City -> cityFills
        AdminLevel.County -> countyFills
    }
    val parentSeed = region.parentCode
        ?.filter { it.isDigit() }
        ?.takeLast(4)
        ?.toIntOrNull()
        ?: 0
    val codeSeed = region.code
        .filter { it.isDigit() }
        .takeLast(2)
        .toIntOrNull()
        ?: index
    val stride = 3
    return palette[(index * stride + parentSeed + codeSeed) % palette.size]
}

private fun DrawScope.drawLabels(
    level: AdminLevel,
    regions: List<AdministrativeRegion>,
    alpha: Float,
    palette: MapPalette,
    scale: Float,
    pan: Offset,
    worldToScreen: (GeoPoint, IntSize, Float, Offset) -> Offset,
    density: Float,
) {
    val minScale = when (level) {
        AdminLevel.Province -> 0f
        AdminLevel.City -> 1.25f
        AdminLevel.County -> 3.7f
    }
    if (scale < minScale) return
    val textSize = when (level) {
        AdminLevel.Province -> 13f
        AdminLevel.City -> 10.5f
        AdminLevel.County -> 8.5f
    } * density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.label.copy(alpha = alpha).toArgbInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        this.textSize = textSize
    }
    drawContext.canvas.nativeCanvas.apply {
        regions.forEach { region ->
            val point = worldToScreen(region.centroid, IntSize(size.width.toInt(), size.height.toInt()), scale, pan)
            if (point.x in -80f..(size.width + 80f) && point.y in -40f..(size.height + 40f)) {
                drawText(region.name, point.x, point.y, paint)
            }
        }
    }
}

private fun DrawScope.drawAuxiliaryBoundaries(
    palette: MapPalette,
    scale: Float,
    pan: Offset,
    worldToScreen: (GeoPoint, IntSize, Float, Offset) -> Offset,
    density: Float,
) {
    val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
    ChinaAdminDataset.auxiliaryPolygons.forEach { polygon ->
        val path = Path()
        polygon.forEachIndexed { index, point ->
            val screen = worldToScreen(point, canvasSize, scale, pan)
            if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        path.close()
        drawPath(path, palette.nineDash.copy(alpha = 0.55f))
        drawPath(
            path = path,
            color = palette.nineDash,
            style = Stroke(
                width = 0.8f * density,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f), 0f),
            ),
        )
    }
}

private fun AdministrativeRegion.toPath(project: (GeoPoint) -> Offset): Path {
    val path = Path()
    polygons.forEach { polygon ->
        polygon.forEachIndexed { index, point ->
            val screen = project(point)
            if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        path.close()
    }
    return path
}

private fun polygonContains(polygon: List<GeoPoint>, point: GeoPoint): Boolean {
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val xi = polygon[i].x
        val yi = polygon[i].y
        val xj = polygon[j].x
        val yj = polygon[j].y
        val intersects = ((yi > point.y) != (yj > point.y)) &&
            (point.x < (xj - xi) * (point.y - yi) / ((yj - yi).takeIf { abs(it) > 0.0001f } ?: 0.0001f) + xi)
        if (intersects) inside = !inside
        j = i
    }
    return inside
}

private fun levelForScale(scale: Float): AdminLevel = when {
    scale < 2.15f -> AdminLevel.Province
    scale < 3.8f -> AdminLevel.City
    else -> AdminLevel.County
}

private fun distanceSquared(first: GeoPoint, second: GeoPoint): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return dx * dx + dy * dy
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

private fun lerp(start: Offset, stop: Offset, fraction: Float): Offset =
    Offset(lerp(start.x, stop.x, fraction), lerp(start.y, stop.y, fraction))

private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255).roundToIntCompat(),
        (red * 255).roundToIntCompat(),
        (green * 255).roundToIntCompat(),
        (blue * 255).roundToIntCompat(),
    )

private fun Float.roundToIntCompat(): Int = coerceIn(0f, 255f).toInt()
