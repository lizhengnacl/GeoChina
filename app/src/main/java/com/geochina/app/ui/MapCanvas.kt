package com.geochina.app.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

private const val MinMapScale = 0.85f
private const val MaxMapScale = 80f
private const val CityLevelMinScale = 4.8f
private const val CountyLevelMinScale = 18f
private const val CityLabelMinScale = 5.2f
private const val CountyLabelMinScale = 21f
private const val CityFocusMinScale = 5.5f
private const val CountyFocusMinScale = 22f
private const val AMapTileSizePx = 256.0

@Composable
fun AdminMapCanvas(
    currentLevel: AdminLevel,
    selectedRegion: AdministrativeRegion?,
    focusRequest: FocusRequest?,
    zoomCommand: ZoomCommand?,
    darkTheme: Boolean,
    useOnlineBasemap: Boolean = true,
    onLevelChanged: (AdminLevel) -> Unit,
    onViewportChanged: (MapViewport) -> Unit = {},
    onMapRegionOverlaysChanged: (MapRegionOverlayState) -> Unit = {},
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
    var emittedOverlaySignature by remember { mutableStateOf<String?>(null) }
    var viewportAnimationJob by remember { mutableStateOf<Job?>(null) }
    val interactionScope = rememberCoroutineScope()
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

    fun screenToWorld(screen: Offset, drawScale: Float = scale, drawPan: Offset = pan): GeoPoint {
        val bounds = ChinaAdminDataset.worldBounds
        val baseScale = baseScaleFor(canvasSize)
        val center = screenCenter(canvasSize)
        return GeoPoint(
            x = (screen.x - center.x - drawPan.x) / (baseScale * drawScale) + bounds.center.x,
            y = (screen.y - center.y - drawPan.y) / (baseScale * drawScale) + bounds.center.y,
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

    fun viewportState(): MapViewport {
        val center = viewportCenterWorld()
        val bounds = viewportWorldBounds()
        val pixelPerProjectedDegree = baseScaleFor(canvasSize) * scale
        return MapViewport(
            latitude = latitudeFromProjectedY(center.y),
            longitude = center.x.toDouble().coerceIn(70.0, 140.0),
            zoom = onlineMapZoomFor(pixelPerProjectedDegree),
            north = latitudeFromProjectedY(bounds.top),
            south = latitudeFromProjectedY(bounds.bottom),
            west = bounds.left.toDouble().coerceIn(-180.0, 180.0),
            east = bounds.right.toDouble().coerceIn(-180.0, 180.0),
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
                    fillAlpha = 0.48f,
                    strokeAlpha = 0.78f,
                    labelAlpha = 1f,
                    emphasized = true,
                ),
            )
            AdminLevel.City -> listOf(
                RegionDrawGroup(
                    level = AdminLevel.Province,
                    regions = provinces,
                    fillAlpha = 0.06f,
                    strokeAlpha = 0.2f,
                    labelAlpha = 0.42f,
                    emphasized = false,
                ),
                RegionDrawGroup(
                    level = AdminLevel.City,
                    regions = primary,
                    fillAlpha = 0.56f,
                    strokeAlpha = 0.76f,
                    labelAlpha = 1f,
                    emphasized = true,
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
                        fillAlpha = 0.04f,
                        strokeAlpha = 0.18f,
                        labelAlpha = 0.28f,
                        emphasized = false,
                    ),
                    RegionDrawGroup(
                        level = AdminLevel.City,
                        regions = cityContext,
                        fillAlpha = 0.08f,
                        strokeAlpha = 0.28f,
                        labelAlpha = 0.48f,
                        emphasized = false,
                    ),
                    RegionDrawGroup(
                        level = AdminLevel.County,
                        regions = primary,
                        fillAlpha = 0.62f,
                        strokeAlpha = 0.82f,
                        labelAlpha = 1f,
                        emphasized = true,
                    ),
                )
            }
        }.filter { it.regions.isNotEmpty() }
    }

    fun mapRegionOverlayState(): MapRegionOverlayState {
        if (!useOnlineBasemap) return MapRegionOverlayState.Empty
        val selectedCode = selectedRegion?.code
        val entries = mutableListOf<MapRegionOverlayEntry>()
        val signature = StringBuilder()
            .append(if (darkTheme) "dark" else "light")
            .append('|')
            .append(renderedLevel.name)
            .append('|')
            .append(selectedCode.orEmpty())

        renderGroups(renderedLevel).forEachIndexed { groupIndex, group ->
            val baseStrokeWidth = when (group.level) {
                AdminLevel.Province -> 1.4f
                AdminLevel.City -> 1.0f
                AdminLevel.County -> 0.7f
            } * density.density
            val focusStrokeWidth = baseStrokeWidth * when (group.level) {
                AdminLevel.Province -> 1.25f
                AdminLevel.City -> 1.45f
                AdminLevel.County -> 1.65f
            }
            group.regions.forEachIndexed { index, region ->
                val selected = region.code == selectedCode
                val fill = if (selected) {
                    palette.selectedFill
                } else {
                    palette.colorFor(group.level, region, index)
                }
                val fillAlpha = when {
                    selected -> 0.92f
                    group.emphasized -> when (group.level) {
                        AdminLevel.Province -> 0.64f
                        AdminLevel.City -> 0.68f
                        AdminLevel.County -> 0.72f
                    }
                    else -> when (group.level) {
                        AdminLevel.Province -> 0.16f
                        AdminLevel.City -> 0.22f
                        AdminLevel.County -> 0.28f
                    }
                }
                val strokeColor = when {
                    selected -> palette.selectedStroke
                    group.emphasized -> palette.focusBoundary.copy(alpha = max(group.strokeAlpha, 0.98f))
                    else -> palette.boundary.copy(alpha = max(group.strokeAlpha, 0.42f))
                }
                val strokeWidth = when {
                    selected -> baseStrokeWidth * 2.7f
                    group.emphasized -> focusStrokeWidth * 1.15f
                    else -> baseStrokeWidth * 1.1f
                }
                val fillColor = fill.copy(alpha = fillAlpha).toArgbInt()
                val strokeColorInt = strokeColor.toArgbInt()
                entries += MapRegionOverlayEntry(
                    region = region,
                    fillColor = fillColor,
                    strokeColor = strokeColorInt,
                    strokeWidthPx = strokeWidth,
                    zIndex = groupIndex * 10f + if (selected) 50f else 0f,
                )
                signature
                    .append('|')
                    .append(region.code)
                    .append(':')
                    .append(fillColor)
                    .append(':')
                    .append(strokeColorInt)
                    .append(':')
                    .append(strokeWidth)
            }
        }
        return MapRegionOverlayState(entries, signature.toString())
    }

    fun targetPanFor(region: AdministrativeRegion, targetScale: Float): Offset {
        val projected = worldToScreen(region.bounds.center, drawScale = targetScale, drawPan = Offset.Zero)
        return screenCenter(canvasSize) - projected
    }

    fun zoomTargetAround(
        screenPoint: Offset,
        factor: Float,
        startScale: Float = scale,
        startPan: Offset = pan,
    ): ViewportTransform? {
        if (canvasSize.width == 0 || canvasSize.height == 0) return null
        val worldPoint = screenToWorld(screenPoint, startScale, startPan)
        val newScale = (startScale * factor).coerceIn(MinMapScale, MaxMapScale)
        val baseScale = baseScaleFor(canvasSize)
        val center = screenCenter(canvasSize)
        val bounds = ChinaAdminDataset.worldBounds
        val newPan = Offset(
            x = screenPoint.x - center.x - (worldPoint.x - bounds.center.x) * baseScale * newScale,
            y = screenPoint.y - center.y - (worldPoint.y - bounds.center.y) * baseScale * newScale,
        )
        return ViewportTransform(newScale, newPan)
    }

    fun zoomAround(screenPoint: Offset, factor: Float) {
        val target = zoomTargetAround(screenPoint, factor) ?: return
        scale = target.scale
        pan = target.pan
    }

    suspend fun animateViewportTo(target: ViewportTransform, durationMillis: Int = 320) {
        val startScale = scale
        val startPan = pan
        if (abs(startScale - target.scale) < 0.001f && (startPan - target.pan).getDistance() < 0.5f) return
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
        ) { value, _ ->
            scale = lerp(startScale, target.scale, value)
            pan = lerp(startPan, target.pan, value)
        }
        scale = target.scale
        pan = target.pan
    }

    suspend fun animateZoomAround(screenPoint: Offset, factor: Float) {
        val target = zoomTargetAround(screenPoint, factor) ?: return
        animateViewportTo(target)
    }

    fun launchZoomAnimation(screenPoint: Offset, factor: Float) {
        viewportAnimationJob?.cancel()
        viewportAnimationJob = interactionScope.launch {
            animateZoomAround(screenPoint, factor)
        }
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

    LaunchedEffect(canvasSize, scale, pan) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
        onViewportChanged(viewportState())
    }

    LaunchedEffect(
        useOnlineBasemap,
        canvasSize,
        pan,
        scale,
        renderedLevel,
        stableProvinceCode,
        stableCityCode,
        selectedRegion?.code,
        darkTheme,
    ) {
        val overlayState = mapRegionOverlayState()
        if (overlayState.signature != emittedOverlaySignature) {
            emittedOverlaySignature = overlayState.signature
            onMapRegionOverlaysChanged(overlayState)
        }
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
            transition.animateTo(1f, tween(durationMillis = 460, easing = FastOutSlowInEasing))
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
            AdminLevel.City -> CityFocusMinScale
            AdminLevel.County -> CountyFocusMinScale
        }
        val targetScale = max(fitScale, minimumScale).coerceIn(0.95f, MaxMapScale)
        val targetPan = targetPanFor(region, targetScale)
        viewportAnimationJob?.cancel()
        viewportAnimationJob = interactionScope.launch {
            animateViewportTo(ViewportTransform(targetScale, targetPan), durationMillis = 620)
        }
    }

    LaunchedEffect(zoomCommand?.nonce, canvasSize) {
        val command = zoomCommand ?: return@LaunchedEffect
        if (canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
        launchZoomAnimation(screenCenter(canvasSize), command.factor)
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, gesturePan, zoom, _ ->
                    viewportAnimationJob?.cancel()
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
                    onDoubleTap = { launchZoomAnimation(it, 1.7f) },
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
                            launchZoomAnimation(startPositions.first(), 0.68f)
                        }
                    }
                }
            },
    ) {
        if (!useOnlineBasemap) {
            drawOfflineBasemap(
                palette = palette,
                scale = scale,
                pan = pan,
                worldToScreen = ::worldToScreen,
                density = density.density,
            )
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
}

data class ZoomCommand(
    val factor: Float,
    val nonce: Long,
)

private data class ViewportTransform(
    val scale: Float,
    val pan: Offset,
)

private data class MapPalette(
    val sea: Color,
    val land: Color,
    val landAlt: Color,
    val coastline: Color,
    val basemapBoundary: Color,
    val graticule: Color,
    val graticuleLabel: Color,
    val boundary: Color,
    val focusBoundary: Color,
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
    val emphasized: Boolean,
)

private fun mapPalette(darkTheme: Boolean): MapPalette =
    if (darkTheme) {
        MapPalette(
            sea = Color(0xFF18384C),
            land = Color(0xFF263F34),
            landAlt = Color(0xFF314737),
            coastline = Color(0xFFC7D5B7),
            basemapBoundary = Color(0xFFB7A98D),
            graticule = Color(0xFF9EB3BE),
            graticuleLabel = Color(0xFFD7E3E7),
            boundary = Color(0xFFD6B29A),
            focusBoundary = Color(0xFFFFE2A8),
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
            land = Color(0xFFF2E6C7),
            landAlt = Color(0xFFE9DDBB),
            coastline = Color(0xFF487A8E),
            basemapBoundary = Color(0xFF8B7A59),
            graticule = Color(0xFF77AFC4),
            graticuleLabel = Color(0xFF4B8294),
            boundary = Color(0xFF9A5645),
            focusBoundary = Color(0xFFD54431),
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
    val focusStrokeWidth = strokeWidth * when (group.level) {
        AdminLevel.Province -> 1.25f
        AdminLevel.City -> 1.45f
        AdminLevel.County -> 1.65f
    }
    group.regions.forEachIndexed { index, region ->
        val path = region.toPath { point -> worldToScreen(point, IntSize(size.width.toInt(), size.height.toInt()), scale, pan) }
        val selected = region.code == selectedCode
        val fill = if (selected) {
            palette.selectedFill
        } else {
            palette.colorFor(group.level, region, index)
        }
        val fillAlpha = if (selected) max(group.fillAlpha, 0.88f) else group.fillAlpha
        drawPath(path, fill.copy(alpha = fillAlpha * alpha))
        val strokeColor = when {
            selected -> palette.selectedStroke.copy(alpha = alpha)
            group.emphasized -> palette.focusBoundary.copy(alpha = max(group.strokeAlpha, 0.96f) * alpha)
            else -> palette.boundary.copy(alpha = group.strokeAlpha * alpha)
        }
        drawPath(
            path = path,
            color = strokeColor,
            style = Stroke(
                width = when {
                    selected -> strokeWidth * 2.4f
                    group.emphasized -> focusStrokeWidth
                    else -> strokeWidth
                },
            ),
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
        ?.hashCode()
        ?: 0
    val codeSeed = region.code.takeIf { it.isNotBlank() }?.hashCode()
        ?: (region.name.hashCode() + index)
    val fallbackSeed = region.level.ordinal * 17
    return palette[Math.floorMod(codeSeed * 31 + parentSeed * 7 + fallbackSeed, palette.size)]
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
        AdminLevel.City -> CityLabelMinScale
        AdminLevel.County -> CountyLabelMinScale
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
    val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
    val minRegionWidth = when (level) {
        AdminLevel.Province -> 28f
        AdminLevel.City -> 58f
        AdminLevel.County -> 72f
    } * density
    val minRegionHeight = when (level) {
        AdminLevel.Province -> 18f
        AdminLevel.City -> 36f
        AdminLevel.County -> 44f
    } * density
    val maxLabels = when (level) {
        AdminLevel.Province -> 40
        AdminLevel.City -> if (scale < 8f) 12 else 24
        AdminLevel.County -> if (scale < 26f) 8 else 18
    }
    val visibleLabels = regions.mapNotNull { region ->
        val bounds = region.projectedScreenBounds(canvasSize, scale, pan, worldToScreen)
        if (bounds.right < -80f || bounds.left > size.width + 80f || bounds.bottom < -60f || bounds.top > size.height + 60f) {
            return@mapNotNull null
        }
        val textWidth = paint.measureText(region.name)
        if (bounds.width() < max(minRegionWidth, textWidth * 1.08f) || bounds.height() < minRegionHeight) {
            return@mapNotNull null
        }
        val point = worldToScreen(region.centroid, canvasSize, scale, pan)
        val labelBounds = RectF(
            point.x - textWidth / 2f - 5f * density,
            point.y - textSize - 4f * density,
            point.x + textWidth / 2f + 5f * density,
            point.y + 5f * density,
        )
        ProjectedLabel(
            text = region.name,
            point = point,
            bounds = labelBounds,
            screenArea = bounds.width() * bounds.height(),
        )
    }.sortedByDescending { it.screenArea }

    drawContext.canvas.nativeCanvas.apply {
        val occupied = mutableListOf<RectF>()
        var drawn = 0
        for (label in visibleLabels) {
            if (drawn >= maxLabels) break
            if (occupied.any { RectF.intersects(it, label.bounds) }) continue
            if (label.point.x in -80f..(size.width + 80f) && label.point.y in -40f..(size.height + 40f)) {
                drawText(label.text, label.point.x, label.point.y, paint)
                occupied += label.bounds
                drawn += 1
            }
        }
    }
}

private fun DrawScope.drawOfflineBasemap(
    palette: MapPalette,
    scale: Float,
    pan: Offset,
    worldToScreen: (GeoPoint, IntSize, Float, Offset) -> Offset,
    density: Float,
) {
    val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
    val provinces = ChinaAdminDataset.regionsForLevel(AdminLevel.Province)
    val mapLineScale = (1f / scale).coerceIn(0.45f, 1f)
    val provinceStroke = 0.65f * density * mapLineScale
    val coastlineStroke = 1.15f * density * mapLineScale

    drawRect(palette.sea)
    provinces.forEachIndexed { index, province ->
        val path = province.toPath { point -> worldToScreen(point, canvasSize, scale, pan) }
        drawPath(
            path = path,
            color = if (index % 2 == 0) palette.land else palette.landAlt,
        )
    }
    drawGraticule(palette, scale, pan, worldToScreen, density)
    provinces.forEach { province ->
        val path = province.toPath { point -> worldToScreen(point, canvasSize, scale, pan) }
        drawPath(
            path = path,
            color = palette.basemapBoundary.copy(alpha = 0.42f),
            style = Stroke(width = provinceStroke),
        )
        drawPath(
            path = path,
            color = palette.coastline.copy(alpha = 0.34f),
            style = Stroke(width = coastlineStroke),
        )
    }
    drawBasemapLabels(palette, scale, pan, worldToScreen, density)
}

private fun DrawScope.drawGraticule(
    palette: MapPalette,
    scale: Float,
    pan: Offset,
    worldToScreen: (GeoPoint, IntSize, Float, Offset) -> Offset,
    density: Float,
) {
    if (scale > 22f) return
    val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
    val strokeWidth = 0.45f * density * (1f / scale).coerceIn(0.55f, 1f)

    fun drawProjectedLine(points: List<GeoPoint>) {
        val path = Path()
        points.forEachIndexed { index, point ->
            val screen = worldToScreen(point, canvasSize, scale, pan)
            if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        drawPath(
            path = path,
            color = palette.graticule.copy(alpha = 0.28f),
            style = Stroke(width = strokeWidth),
        )
    }

    for (longitude in 70..140 step 10) {
        drawProjectedLine((5..60 step 2).map { latitude ->
            projectLongitudeLatitude(longitude.toFloat(), latitude.toFloat())
        })
    }
    for (latitude in 10..60 step 10) {
        drawProjectedLine((70..140 step 2).map { longitude ->
            projectLongitudeLatitude(longitude.toFloat(), latitude.toFloat())
        })
    }
}

private fun DrawScope.drawBasemapLabels(
    palette: MapPalette,
    scale: Float,
    pan: Offset,
    worldToScreen: (GeoPoint, IntSize, Float, Offset) -> Offset,
    density: Float,
) {
    if (scale > 7f) return
    val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
    val labels = listOf(
        BasemapLabel("中国", 104f, 35f, 22f, true),
        BasemapLabel("南海", 114f, 14f, 13f, false),
        BasemapLabel("东海", 124f, 28f, 13f, false),
    )
    drawContext.canvas.nativeCanvas.apply {
        labels.forEach { label ->
            val point = worldToScreen(projectLongitudeLatitude(label.longitude, label.latitude), canvasSize, scale, pan)
            if (point.x !in -120f..(size.width + 120f) || point.y !in -80f..(size.height + 80f)) return@forEach
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.graticuleLabel.copy(alpha = if (label.primary) 0.28f else 0.36f).toArgbInt()
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, if (label.primary) Typeface.BOLD else Typeface.NORMAL)
                textSize = label.textSizeSp * density * (1f / scale).coerceIn(0.62f, 1f)
            }
            drawText(label.text, point.x, point.y, paint)
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

private data class BasemapLabel(
    val text: String,
    val longitude: Float,
    val latitude: Float,
    val textSizeSp: Float,
    val primary: Boolean,
)

private data class ProjectedLabel(
    val text: String,
    val point: Offset,
    val bounds: RectF,
    val screenArea: Float,
)

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

private fun AdministrativeRegion.projectedScreenBounds(
    canvasSize: IntSize,
    scale: Float,
    pan: Offset,
    worldToScreen: (GeoPoint, IntSize, Float, Offset) -> Offset,
): RectF {
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    polygons.forEach { polygon ->
        polygon.forEach { point ->
            val screen = worldToScreen(point, canvasSize, scale, pan)
            left = min(left, screen.x)
            top = min(top, screen.y)
            right = max(right, screen.x)
            bottom = max(bottom, screen.y)
        }
    }
    return RectF(left, top, right, bottom)
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
    scale < CityLevelMinScale -> AdminLevel.Province
    scale < CountyLevelMinScale -> AdminLevel.City
    else -> AdminLevel.County
}

private fun distanceSquared(first: GeoPoint, second: GeoPoint): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return dx * dx + dy * dy
}

private fun projectLongitudeLatitude(longitude: Float, latitude: Float): GeoPoint {
    val clampedLatitude = latitude.coerceIn(-85f, 85f)
    val mercatorY = ln(tan(PI / 4.0 + Math.toRadians(clampedLatitude.toDouble()) / 2.0)) * 180.0 / PI
    return GeoPoint(longitude, -mercatorY.toFloat())
}

internal fun latitudeFromProjectedY(projectedY: Float): Double {
    val mercatorY = -projectedY.toDouble()
    val radians = 2.0 * atan(exp(mercatorY * PI / 180.0)) - PI / 2.0
    return Math.toDegrees(radians).coerceIn(-85.0, 85.0)
}

private fun onlineMapZoomFor(pixelPerProjectedDegree: Float): Double {
    val zoom = ln((pixelPerProjectedDegree * 360.0) / AMapTileSizePx) / ln(2.0)
    return zoom.coerceIn(2.0, 20.0)
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
