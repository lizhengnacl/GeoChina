package com.geochina.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.geochina.app.data.AdminRepository
import com.geochina.app.data.ChinaAdminDataset
import com.geochina.app.data.FavoriteRegionEntity
import com.geochina.app.data.SearchHistoryStore
import com.geochina.app.model.AdminLevel
import com.geochina.app.model.AdministrativeRegion
import com.geochina.app.model.FocusRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ThemeMode(val title: String) {
    System("跟随"),
    Light("浅色"),
    Dark("深色"),
}

data class GeoChinaUiState(
    val currentLevel: AdminLevel = AdminLevel.Province,
    val selectedRegion: AdministrativeRegion? = null,
    val focusRequest: FocusRequest? = null,
    val searchQuery: String = "",
    val searchResults: List<AdministrativeRegion> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val selectedTabIndex: Int = 0,
    val bottomSheetVisible: Boolean = false,
    val favoritesVisible: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
)

class GeoChinaViewModel(
    private val repository: AdminRepository,
    private val historyStore: SearchHistoryStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GeoChinaUiState(searchHistory = historyStore.read()))

    val favorites: StateFlow<List<FavoriteRegionEntity>> =
        repository.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteCodes: StateFlow<Set<String>> =
        repository.favoriteCodes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val uiState: StateFlow<GeoChinaUiState> = combine(
        mutableState,
        favoriteCodes,
    ) { state, _ -> state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mutableState.value)

    fun onLevelChanged(level: AdminLevel) {
        mutableState.update { it.copy(currentLevel = level) }
    }

    fun onSearchQueryChanged(query: String) {
        mutableState.update {
            it.copy(
                searchQuery = query,
                searchResults = ChinaAdminDataset.search(query),
            )
        }
    }

    fun selectRegion(region: AdministrativeRegion, focusMap: Boolean = false) {
        val history = historyStore.add(region.name)
        mutableState.update {
            it.copy(
                selectedRegion = region,
                focusRequest = if (focusMap) FocusRequest(region, System.nanoTime()) else it.focusRequest,
                searchQuery = "",
                searchResults = emptyList(),
                searchHistory = history,
                selectedTabIndex = 0,
                bottomSheetVisible = true,
                favoritesVisible = false,
            )
        }
    }

    fun selectFavorite(entity: FavoriteRegionEntity) {
        ChinaAdminDataset.region(entity.code)?.let { selectRegion(it, focusMap = true) }
    }

    fun setSelectedTab(index: Int) {
        mutableState.update { it.copy(selectedTabIndex = index) }
    }

    fun hideBottomSheet() {
        mutableState.update { it.copy(bottomSheetVisible = false) }
    }

    fun toggleFavoritesPage() {
        mutableState.update { it.copy(favoritesVisible = !it.favoritesVisible) }
    }

    fun closeFavoritesPage() {
        mutableState.update { it.copy(favoritesVisible = false) }
    }

    fun cycleThemeMode() {
        val next = when (mutableState.value.themeMode) {
            ThemeMode.System -> ThemeMode.Light
            ThemeMode.Light -> ThemeMode.Dark
            ThemeMode.Dark -> ThemeMode.System
        }
        mutableState.update { it.copy(themeMode = next) }
    }

    fun toggleFavorite(region: AdministrativeRegion) {
        viewModelScope.launch {
            repository.toggleFavorite(region, favoriteCodes.value.contains(region.code))
        }
    }

    fun removeFavorite(code: String) {
        viewModelScope.launch {
            repository.deleteFavorite(code)
        }
    }

    class Factory(
        private val repository: AdminRepository,
        private val historyStore: SearchHistoryStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GeoChinaViewModel(repository, historyStore) as T
        }
    }
}

