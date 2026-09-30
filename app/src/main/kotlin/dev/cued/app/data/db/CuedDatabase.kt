package dev.cued.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, TrackGenreEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, PlayEventEntity::class, DownloadJobEntity::class, LyricsEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class CuedDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun playEvents(): PlayEventDao
    abstract fun downloads(): DownloadJobDao
    abstract fun lyrics(): LyricsDao

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

        fun build(context: Context): CuedDatabase =
            Room.databaseBuilder(context, CuedDatabase::class.java, "cued.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
    }
}
