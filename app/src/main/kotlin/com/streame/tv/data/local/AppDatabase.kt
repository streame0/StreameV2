package com.streame.tv.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        HomeRowEntity::class,
        CatalogConfigEntity::class,
        WatchHistoryEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun homeRowDao(): HomeRowDao
    abstract fun catalogConfigDao(): CatalogConfigDao
    abstract fun watchHistoryDao(): WatchHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migrations map: add entries here when bumping the database version.
         * Key = (fromVersion, toVersion). Example:
         *   val MIGRATION_1_2 = Migration(1, 2) { db ->
         *       db.execSQL("ALTER TABLE watch_history ADD COLUMN newColumn TEXT DEFAULT NULL")
         *   }
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_history ADD COLUMN lastAddonId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE watch_history ADD COLUMN lastSourceName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE watch_history ADD COLUMN lastBingeGroup TEXT DEFAULT NULL")
            }
        }

        private val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "streame_db"
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

/** Type converters for Room — handles primitives that Room can't auto-convert. */
class Converters {
    // No custom converters needed yet — all complex types are stored as JSON strings.
    // Add converters here if we later add Room columns for List<Int>, etc.
}
