package dev.cued.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, TrackGenreEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, PlayEventEntity::class, DownloadJobEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CuedDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun playlists(): PlaylistDao
    abstract fun playEvents(): PlayEventDao
    abstract fun downloads(): DownloadJobDao

    companion object {
        fun build(context: Context): CuedDatabase =
            Room.databaseBuilder(context, CuedDatabase::class.java, "cued.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
