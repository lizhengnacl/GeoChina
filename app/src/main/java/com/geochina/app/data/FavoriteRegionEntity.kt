package com.geochina.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_regions")
data class FavoriteRegionEntity(
    @PrimaryKey val code: String,
    val name: String,
    val level: String,
    val parentName: String,
    val addedAt: Long,
)

