package com.geochina.app.data

import com.geochina.app.model.AdministrativeRegion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AdminRepository(
    private val dao: FavoriteRegionDao,
) {
    val favorites: Flow<List<FavoriteRegionEntity>> = dao.observeFavorites()

    val favoriteCodes: Flow<Set<String>> = dao.observeFavoriteCodes().map { it.toSet() }

    suspend fun toggleFavorite(region: AdministrativeRegion, currentlyFavorite: Boolean) {
        if (currentlyFavorite) {
            dao.delete(region.code)
        } else {
            dao.upsert(
                FavoriteRegionEntity(
                    code = region.code,
                    name = region.name,
                    level = region.level.title,
                    parentName = region.parentName,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun deleteFavorite(code: String) {
        dao.delete(code)
    }
}

