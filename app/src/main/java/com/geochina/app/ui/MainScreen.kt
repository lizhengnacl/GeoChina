package com.geochina.app.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.geochina.app.R
import com.geochina.app.data.ChinaAdminDataset
import com.geochina.app.data.FavoriteRegionEntity
import com.geochina.app.model.AdminLevel
import com.geochina.app.model.AdministrativeRegion
import com.geochina.app.model.RegionStats
import com.geochina.app.ui.theme.GeoChinaTheme
import kotlin.math.max
import kotlin.math.min

@Composable
fun GeoChinaRoute(viewModel: GeoChinaViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteCodes by viewModel.favoriteCodes.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (uiState.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    GeoChinaTheme(themeMode = uiState.themeMode) {
        FullScreenSystemBars(darkTheme = darkTheme)
        GeoChinaScreen(
            uiState = uiState,
            favorites = favorites,
            favoriteCodes = favoriteCodes,
            darkTheme = darkTheme,
            onLevelChanged = viewModel::onLevelChanged,
            onSearchQueryChanged = viewModel::onSearchQueryChanged,
            onRegionSelected = { viewModel.selectRegion(it, focusMap = false) },
            onRegionLocated = { viewModel.selectRegion(it, focusMap = true) },
            onBlankTap = viewModel::hideBottomSheet,
            onDismissSheet = viewModel::hideBottomSheet,
            onTabSelected = viewModel::setSelectedTab,
            onToggleFavorite = viewModel::toggleFavorite,
            onFavoritesClicked = viewModel::toggleFavoritesPage,
            onFavoriteSelected = viewModel::selectFavorite,
            onFavoriteRemoved = viewModel::removeFavorite,
            onFavoritesClosed = viewModel::closeFavoritesPage,
            onThemeClicked = viewModel::cycleThemeMode,
        )
    }
}

@Composable
private fun FullScreenSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    if (view.isInEditMode || window == null) return

    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeoChinaScreen(
    uiState: GeoChinaUiState,
    favorites: List<FavoriteRegionEntity>,
    favoriteCodes: Set<String>,
    darkTheme: Boolean,
    onLevelChanged: (AdminLevel) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onRegionSelected: (AdministrativeRegion) -> Unit,
    onRegionLocated: (AdministrativeRegion) -> Unit,
    onBlankTap: () -> Unit,
    onDismissSheet: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onToggleFavorite: (AdministrativeRegion) -> Unit,
    onFavoritesClicked: () -> Unit,
    onFavoriteSelected: (FavoriteRegionEntity) -> Unit,
    onFavoriteRemoved: (String) -> Unit,
    onFavoritesClosed: () -> Unit,
    onThemeClicked: () -> Unit,
) {
    var overlapCandidates by remember { mutableStateOf<List<AdministrativeRegion>>(emptyList()) }
    var zoomCommand by remember { mutableStateOf<ZoomCommand?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            AdminMapCanvas(
                currentLevel = uiState.currentLevel,
                selectedRegion = uiState.selectedRegion,
                focusRequest = uiState.focusRequest,
                zoomCommand = zoomCommand,
                darkTheme = darkTheme,
                onLevelChanged = onLevelChanged,
                onRegionCandidates = { candidates ->
                    if (candidates.size == 1) {
                        onRegionSelected(candidates.first())
                    } else {
                        overlapCandidates = candidates
                    }
                },
                onBlankTap = onBlankTap,
                modifier = Modifier.fillMaxSize(),
            )

            SearchPanel(
                query = uiState.searchQuery,
                results = uiState.searchResults,
                history = uiState.searchHistory,
                themeMode = uiState.themeMode,
                onQueryChanged = onSearchQueryChanged,
                onResultClicked = onRegionLocated,
                onFavoritesClicked = onFavoritesClicked,
                onThemeClicked = onThemeClicked,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(12.dp),
            )

            ZoomControls(
                onZoomIn = { zoomCommand = ZoomCommand(1.7f, System.nanoTime()) },
                onZoomOut = { zoomCommand = ZoomCommand(0.74f, System.nanoTime()) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 76.dp),
            )

            LevelIndicator(
                level = uiState.currentLevel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            )

            FavoritesPage(
                visible = uiState.favoritesVisible,
                favorites = favorites,
                onClose = onFavoritesClosed,
                onSelect = onFavoriteSelected,
                onRemove = onFavoriteRemoved,
            )
        }
    }

    if (uiState.bottomSheetVisible && uiState.selectedRegion != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(
            onDismissRequest = onDismissSheet,
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        ) {
            RegionInfoSheet(
                region = uiState.selectedRegion,
                selectedTabIndex = uiState.selectedTabIndex,
                isFavorite = favoriteCodes.contains(uiState.selectedRegion.code),
                onTabSelected = onTabSelected,
                onToggleFavorite = { onToggleFavorite(uiState.selectedRegion) },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f),
            )
        }
    }

    if (overlapCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { overlapCandidates = emptyList() },
            title = { Text("选择行政区域") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    overlapCandidates.forEach { region ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onRegionSelected(region)
                                    overlapCandidates = emptyList()
                                },
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(region.name, fontWeight = FontWeight.SemiBold)
                                Text("${region.parentName} · ${region.level.title}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { overlapCandidates = emptyList() }) {
                    Text("关闭")
                }
            },
        )
    }
}

@Composable
private fun SearchPanel(
    query: String,
    results: List<AdministrativeRegion>,
    history: List<String>,
    themeMode: ThemeMode,
    onQueryChanged: (String) -> Unit,
    onResultClicked: (AdministrativeRegion) -> Unit,
    onFavoritesClicked: () -> Unit,
    onThemeClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var focused by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_geochina_logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(40.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.app_slogan),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilledTonalButton(
                        onClick = onFavoritesClicked,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("收藏")
                    }
                    FilledTonalButton(
                        onClick = onThemeClicked,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(themeMode.title)
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        focused = true
                        onQueryChanged(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    placeholder = { Text("搜索省 / 市 / 区县") },
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
            }
        }

        val showResults = query.isNotBlank() || (focused && history.isNotEmpty())
        if (showResults) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
            ) {
                SearchResultList(
                    query = query,
                    results = results,
                    history = history,
                    onResultClicked = {
                        focusManager.clearFocus()
                        focused = false
                        onResultClicked(it)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchResultList(
    query: String,
    results: List<AdministrativeRegion>,
    history: List<String>,
    onResultClicked: (AdministrativeRegion) -> Unit,
) {
    val rows = if (query.isBlank()) {
        history.mapNotNull { name -> ChinaAdminDataset.search(name, limit = 1).firstOrNull() }
    } else {
        results
    }
    if (rows.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(if (query.isBlank()) "暂无搜索历史" else "未找到匹配的行政区域")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.heightIn(max = 300.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(rows, key = { it.code }) { region ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onResultClicked(region) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(region.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${region.parentName} · ${region.level.title} · ${region.code}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ZoomControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SmallFloatingActionButton(onClick = onZoomIn) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
        SmallFloatingActionButton(onClick = onZoomOut) {
            Text("-", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun LevelIndicator(
    level: AdminLevel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Text(
            text = "当前展示：${level.title}",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun FavoritesPage(
    visible: Boolean,
    favorites: List<FavoriteRegionEntity>,
    onClose: () -> Unit,
    onSelect: (FavoriteRegionEntity) -> Unit,
    onRemove: (String) -> Unit,
) {
    AnimatedVisibility(visible = visible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable { onClose() },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.88f)
                    .clickable {},
                shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("收藏列表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onClose) {
                            Text("关闭")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    if (favorites.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("浏览地图时点击收藏按钮添加")
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(favorites, key = { it.code }) { favorite ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    tonalElevation = 1.dp,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelect(favorite) }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(favorite.name, fontWeight = FontWeight.SemiBold)
                                            Text("${favorite.parentName} · ${favorite.level}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = { onRemove(favorite.code) }) {
                                            Text("取消")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionInfoSheet(
    region: AdministrativeRegion,
    selectedTabIndex: Int,
    isFavorite: Boolean,
    onTabSelected: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.navigationBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(region.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${region.parentName} · ${region.level.title}", style = MaterialTheme.typography.bodyMedium)
            }
            FilledTonalButton(
                onClick = onToggleFavorite,
                shape = RoundedCornerShape(8.dp),
                colors = if (isFavorite) {
                    ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
            ) {
                Text(if (isFavorite) "已收藏" else "收藏")
            }
        }

        val tabs = listOf("基本信息", "详细信息", "统计数据")
        TabRow(selectedTabIndex = selectedTabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { onTabSelected(index) },
                    text = { Text(title) },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (selectedTabIndex) {
                0 -> basicInfoRows(region).forEach { row -> item { InfoRow(row.first, row.second) } }
                1 -> detailedInfoRows(region).forEach { row -> item { InfoRow(row.first, row.second) } }
                else -> {
                    statisticsRows(region.stats).forEach { row -> item { InfoRow(row.first, row.second) } }
                    item { PopulationTrendChart(region.stats.populationTrend) }
                }
            }
        }
    }
}

private fun basicInfoRows(region: AdministrativeRegion): List<Pair<String, String>> = listOf(
    "行政区划名称" to region.name,
    "上级行政区划" to region.parentName,
    "行政区划代码" to region.code,
    "面积" to region.stats.areaKm2,
    "人口" to region.stats.population,
)

private fun detailedInfoRows(region: AdministrativeRegion): List<Pair<String, String>> = listOf(
    "政府驻地" to region.governmentSeat,
    "邮政编码" to region.postalCode,
    "电话区号" to region.phoneCode,
    "下辖行政区划" to if (region.childrenCount == 0) "暂无下辖数据" else "${region.childrenCount} 个：${region.subdivisionNames.joinToString("、")}",
    "建制历史简介" to region.history,
)

private fun statisticsRows(stats: RegionStats): List<Pair<String, String>> = listOf(
    "人口密度" to stats.density,
    "GDP" to stats.gdp,
    "人均 GDP" to stats.gdpPerCapita,
    "面积排名" to stats.areaRank,
    "人口排名" to stats.populationRank,
)

@Composable
private fun InfoRow(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value.ifBlank { "暂无数据" }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun PopulationTrendChart(values: List<Float>) {
    if (values.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("近5年常住人口变化趋势", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))
            val lineColor = MaterialTheme.colorScheme.primary
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            ) {
                val minValue = values.minOrNull() ?: 0f
                val maxValue = values.maxOrNull() ?: 0f
                val range = max(1f, maxValue - minValue)
                val points = values.mapIndexed { index, value ->
                    val x = if (values.size == 1) size.width / 2f else size.width * index / (values.size - 1)
                    val y = size.height - ((value - minValue) / range) * size.height
                    Offset(x, y.coerceIn(0f, size.height))
                }
                val path = Path().apply {
                    points.forEachIndexed { index, point ->
                        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                }
                drawPath(path, color = lineColor, style = Stroke(width = 4f))
                points.forEach { point ->
                    drawCircle(lineColor, radius = 5f, center = point)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("5年前", style = MaterialTheme.typography.bodySmall)
                Text("当前", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
