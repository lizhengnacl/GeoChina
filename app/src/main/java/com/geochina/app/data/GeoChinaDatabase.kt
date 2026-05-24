package com.geochina.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteRegionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class GeoChinaDatabase : RoomDatabase() {
    abstract fun favoriteRegionDao(): FavoriteRegionDao

    companion object {
        @Volatile
        private var instance: GeoChinaDatabase? = null

        fun get(context: Context): GeoChinaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GeoChinaDatabase::class.java,
                    "geo_china.db",
                ).build().also { instance = it }
            }
    }
}
