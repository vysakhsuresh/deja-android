package com.layerbit.deja.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ShotEntity::class, ShotFts::class],
    version = 3,
    exportSchema = true
)
abstract class DejaDatabase : RoomDatabase() {

    abstract fun shotDao(): ShotDao

    companion object {

        /**
         * Adds the pinned flag without throwing the index away.
         *
         * Earlier schema changes were handled destructively, on the argument that the index is
         * derived data and can be rebuilt from the screenshots themselves. That argument stops
         * holding the moment a column records something the user typed, chose, or marked: pinning
         * is a decision, not a derivation, and re-reading a thousand screenshots cannot recover
         * it. Every schema change from here on ships a migration.
         *
         * The FTS table needs no attention. Its triggers fire on the shots table and copy
         * searchBlob across; adding a column alongside leaves both the column and the triggers
         * intact, and nothing about what is searchable has changed.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shots ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: DejaDatabase? = null

        fun get(context: Context): DejaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DejaDatabase::class.java,
                    "deja.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    // Only reached from a schema version with no path to this one - an install
                    // from a build that never shipped. Rebuilding the index then is the correct
                    // outcome; for every version Deja has actually released there is a migration
                    // above and this never runs.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
