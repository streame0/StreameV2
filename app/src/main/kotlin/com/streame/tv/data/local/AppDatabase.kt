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
        WatchHistoryEntity::class,
        WatchlistEntity::class,
        DownloadEntity::class,
        SearchHistoryEntity::class,
        ProfileEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun homeRowDao(): HomeRowDao
    abstract fun catalogConfigDao(): CatalogConfigDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun profileDao(): ProfileDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_history ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE watch_history ADD COLUMN source TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE watch_history ADD COLUMN videoId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE watch_history ADD COLUMN progressKey TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_queue (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scope TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        retryCount INTEGER NOT NULL,
                        lastError TEXT DEFAULT NULL,
                        lastAttemptAt INTEGER DEFAULT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create watchlist table with indexes
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS watchlist (
                        rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profileId TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        tmdbId INTEGER NOT NULL,
                        title TEXT NOT NULL DEFAULT '',
                        posterPath TEXT DEFAULT NULL,
                        backdropPath TEXT DEFAULT NULL,
                        addedAt INTEGER NOT NULL,
                        sourceOrder INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_watchlist_profileId_mediaType_tmdbId ON watchlist (profileId, mediaType, tmdbId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watchlist_profileId_addedAt ON watchlist (profileId, addedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watchlist_updatedAt ON watchlist (updatedAt)")

                // Add indexes to watch_history for faster queries
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_history_profileId ON watch_history (profileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_history_tmdbId_mediaType ON watch_history (tmdbId, mediaType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_history_updatedAt ON watch_history (updatedAt)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS downloads (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profileId TEXT NOT NULL,
                        tmdbId INTEGER NOT NULL,
                        mediaType TEXT NOT NULL,
                        title TEXT NOT NULL,
                        posterPath TEXT DEFAULT NULL,
                        backdropPath TEXT DEFAULT NULL,
                        overview TEXT DEFAULT NULL,
                        seasonNumber INTEGER DEFAULT NULL,
                        episodeNumber INTEGER DEFAULT NULL,
                        episodeTitle TEXT DEFAULT NULL,
                        sourceUrl TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        fileSizeBytes INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'queued',
                        progress INTEGER NOT NULL DEFAULT 0,
                        errorMessage TEXT DEFAULT NULL,
                        addedAt INTEGER NOT NULL DEFAULT 0,
                        completedAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_profileId ON downloads (profileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_tmdbId_mediaType ON downloads (tmdbId, mediaType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_status ON downloads (status)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS search_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        profileId TEXT NOT NULL,
                        query TEXT NOT NULL,
                        searchedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_search_history_profileId ON search_history (profileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_search_history_query ON search_history (query)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_search_history_searchedAt ON search_history (searchedAt)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS profiles (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        avatarColor INTEGER NOT NULL,
                        avatarId INTEGER NOT NULL DEFAULT 0,
                        isKidsProfile INTEGER NOT NULL DEFAULT 0,
                        pin TEXT DEFAULT NULL,
                        isLocked INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL,
                        cloudUserId TEXT DEFAULT NULL,
                        cloudEmail TEXT DEFAULT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_profiles_cloudUserId ON profiles (cloudUserId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_profiles_lastUsedAt ON profiles (lastUsedAt)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Fill null progressKey values from existing columns before creating unique index
                db.execSQL("""
                    UPDATE watch_history
                    SET progressKey = CASE
                        WHEN mediaType = 'tv' THEN 'tv:' || tmdbId || ':' || COALESCE(season, 0) || ':' || COALESCE(episode, 0)
                        ELSE 'movie:' || tmdbId
                    END
                    WHERE progressKey IS NULL
                """.trimIndent())
                // Dedupe: keep newest row per (profileId, progressKey), delete older duplicates
                db.execSQL("""
                    DELETE FROM watch_history
                    WHERE rowId NOT IN (
                        SELECT MIN(rowId)
                        FROM watch_history
                        GROUP BY profileId, progressKey
                    )
                """.trimIndent())
                // Now safe to create unique composite index
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_watch_history_profileId_progressKey ON watch_history (profileId, progressKey)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS sync_queue")
                db.execSQL("DROP INDEX IF EXISTS index_profiles_cloudUserId")
                val cursor = db.query("SELECT name FROM pragma_table_info('profiles') WHERE name IN ('cloudUserId','cloudEmail')")
                val hasCloudCols = cursor.count > 0
                cursor.close()
                if (hasCloudCols) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS profiles_new (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, avatarColor INTEGER NOT NULL, avatarId INTEGER NOT NULL DEFAULT 0, isKidsProfile INTEGER NOT NULL DEFAULT 0, pin TEXT DEFAULT NULL, isLocked INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO profiles_new (id,name,avatarColor,avatarId,isKidsProfile,pin,isLocked,createdAt,lastUsedAt) SELECT id,name,avatarColor,avatarId,isKidsProfile,pin,isLocked,createdAt,lastUsedAt FROM profiles")
                    db.execSQL("DROP TABLE profiles")
                    db.execSQL("ALTER TABLE profiles_new RENAME TO profiles")
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS index_profiles_lastUsedAt ON profiles (lastUsedAt)")
            }
        }

        private val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10
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
