package dev.cued.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.cued.app.analysis.AnalysisQueue
import dev.cued.app.data.LibraryRepository
import dev.cued.app.data.Settings
import dev.cued.app.data.db.CuedDatabase
import dev.cued.app.download.DownloadManager
import dev.cued.app.genre.GenreRepository
import dev.cued.app.lyrics.LyricsRepository
import dev.cued.app.playback.CarModeDetector
import dev.cued.app.playback.PlayerHolder
import dev.cued.app.share.ShareServer
import dev.cued.app.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency graph. No DI framework: everything the app needs
 * is constructed here, lazily, and reachable through [CuedApp.graph]. It is
 * small enough to read in one sitting, which is the point.
 */
class CuedApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        dev.cued.app.util.DebugLog.init(this, BuildConfig.VERSION_NAME)
        graph.settings.debugLog.onEach { dev.cued.app.util.DebugLog.enabled = it }.launchIn(graph.appScope)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.notification_channel_downloads), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
        const val CHANNEL_DOWNLOADS = "downloads"
        fun graph(context: Context): Graph = (context.applicationContext as CuedApp).graph
    }
}

class Graph(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: CuedDatabase by lazy { CuedDatabase.build(app) }
    val settings: Settings by lazy { Settings(app) }
    val library: LibraryRepository by lazy { LibraryRepository(app, db, appScope) }
    val analysis: AnalysisQueue by lazy { AnalysisQueue(app, db, appScope) }
    val player: PlayerHolder by lazy { PlayerHolder(app, this) }
    val downloads: DownloadManager by lazy { DownloadManager(app, this) }
    val shareServer: ShareServer by lazy { ShareServer(app, this) }
    val carDetector: CarModeDetector by lazy { CarModeDetector(app) }
    val lyrics: LyricsRepository by lazy { LyricsRepository(app, db, settings, appScope, BuildConfig.VERSION_NAME) }
    val genres: GenreRepository by lazy { GenreRepository(app, db, settings, appScope, BuildConfig.VERSION_NAME) }
    val updates: UpdateManager by lazy { UpdateManager(app, settings, appScope) }

    init {
        // New or previously unlabelled files get their tag genres read after each scan.
        library.onScanned = { genres.refreshFromTagsAsync(onlyMissing = true) }
    }
}
