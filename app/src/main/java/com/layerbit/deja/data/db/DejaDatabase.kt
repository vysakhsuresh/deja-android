package com.layerbit.deja.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ShotEntity::class, ShotFts::class],
    version = 1,
    exportSchema = true
)
abstract class DejaDatabase : RoomDatabase() {

    abstract fun shotDao(): ShotDao

    companion object {
        @Volatile
        private var instance: DejaDatabase? = null

        fun get(context: Context): DejaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DejaDatabase::class.java,
                    "deja.db"
                )
                    // The index is derived from screenshots that are still on the device, so a
                    // schema change can throw it away and re-read rather than ship migrations.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
