package dev.cued.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, TrackGenreEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, PlayEventEntity::class, DownloadJobEntity::class, LyricsEntity::class, FeedArtistEntity::class, ReleaseEntity::class, ReleaseTrackEntity::class],
    version = 8,
    exportSchema = true,
)
abstract class CuedDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun playEvents(): PlayEventDao
    abstract fun downloads(): DownloadJobDao
    abstract fun lyrics(): LyricsDao
    abstract fun feed(): FeedDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `lyrics` (`trackId` INTEGER NOT NULL, `plain` TEXT, `synced` TEXT, `source` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`))")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'music'")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `resumeMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `tracks` SET `kind` = 'long' WHERE `durationMs` > ${TrackEntity.LONG_THRESHOLD_MS}")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `genresLocked` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_jobs` ADD COLUMN `artworkUrl` TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `trackNo` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `discNo` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `year` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `albumArtist` TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `feed_artists` (`name` TEXT NOT NULL, `mbid` TEXT, `followed` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `lastRefreshAt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`name`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `releases` (`id` TEXT NOT NULL, `artist` TEXT NOT NULL, `artistMbid` TEXT NOT NULL, `title` TEXT NOT NULL, `type` TEXT NOT NULL, `date` TEXT NOT NULL, `dateMs` INTEGER NOT NULL, `coverUrl` TEXT, `fetchedAt` INTEGER NOT NULL, `tracksFetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_releases_artist` ON `releases` (`artist`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_releases_dateMs` ON `releases` (`dateMs`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `release_tracks` (`releaseId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `recordingId` TEXT, PRIMARY KEY(`releaseId`, `position`), FOREIGN KEY(`releaseId`) REFERENCES `releases`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `feed_artists` ADD COLUMN `spotifyId` TEXT")
                db.execSQL("ALTER TABLE `releases` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'musicbrainz'")
                db.execSQL("ALTER TABLE `releases` ADD COLUMN `spotifyId` TEXT")
                db.execSQL("ALTER TABLE `release_tracks` ADD COLUMN `spotifyId` TEXT")
            }
        }

        fun build(context: Context): CuedDatabase =
            Room.databaseBuilder(context, CuedDatabase::class.java, "cued.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build()
    }
}
