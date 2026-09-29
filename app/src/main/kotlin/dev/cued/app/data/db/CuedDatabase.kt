package dev.cued.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, TrackGenreEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, PlayEventEntity::class, DownloadJobEntity::class, LyricsEntity::class],
    version = 2,
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

        fun build(context: Context): CuedDatabase =
            Room.databaseBuilder(context, CuedDatabase::class.java, "cued.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
