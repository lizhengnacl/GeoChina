package com.geochina.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteRegionDao {
    @Query("SELECT * FROM favorite_regions ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<FavoriteRegionEntity>>

    @Query("SELECT code FROM favorite_regions")
    fun observeFavoriteCodes(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteRegionEntity)

    @Query("DELETE FROM favorite_regions WHERE code = :code")
    suspend fun delete(code: String)
}

