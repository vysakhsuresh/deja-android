package com.layerbit.deja.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ShotEntity::class, ShotFts::class],
    version = 5,
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

        /**
         * Adds the engine's own bookkeeping: line geometry, a perceptual hash, the headline, and
         * the two version stamps that let a later engine bring old rows forward.
         *
         * Both version columns default to 0, which is behind every shipping engine version, so
         * every existing row is marked stale the moment this runs. That is the intent, and it is
         * not a wipe: nothing is deleted, nothing is emptied, and the index stays completely
         * usable - searchable, filterable, with pins intact - while the worker re-reads in the
         * background, newest screenshots first, a few hundred per run. The old text keeps
         * answering searches right up until better text replaces it.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shots ADD COLUMN headline TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE shots ADD COLUMN linesBlob TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE shots ADD COLUMN phash INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shots ADD COLUMN readVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shots ADD COLUMN deriveVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shots ADD COLUMN readAttempts INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Room for what a QR code carries, and for which recogniser read the page.
         *
         * Same shape as the last one: additive columns, nothing dropped, and the engine's
         * version stamps - already a version behind by the time this runs - are what schedule
         * the background re-read that fills them in.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shots ADD COLUMN codesBlob TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE shots ADD COLUMN script TEXT NOT NULL DEFAULT ''")
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
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
