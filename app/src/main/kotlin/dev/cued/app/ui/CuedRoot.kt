package dev.cued.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.cued.app.Graph
import dev.cued.app.data.SmartList
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.components.MiniPlayer
import dev.cued.app.ui.components.TrackSheet
import dev.cued.app.ui.screens.DownloadsScreen
import dev.cued.app.ui.screens.HomeScreen
import dev.cued.app.ui.screens.LibraryScreen
import dev.cued.app.ui.screens.NowPlayingScreen
import dev.cued.app.ui.screens.PlaylistDetailScreen
import dev.cued.app.ui.screens.PlaylistsScreen
import dev.cued.app.ui.screens.ReceiveScreen
import dev.cued.app.ui.screens.SettingsScreen
import dev.cued.app.ui.screens.ShareScreen
import dev.cued.app.ui.screens.TrackListScreen
import dev.cued.core.share.SharePayload
import java.net.URLEncoder
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Something the activity received from outside: a share payload, a link to download, or "open now playing". */
sealed class Inbound {
    data class Share(val payload: SharePayload) : Inbound()
    data class Download(val source: String) : Inbound()
    data object NowPlaying : Inbound()
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    LIBRARY("library", "Library", Icons.Default.LibraryMusic),
    PLAYLISTS("playlists", "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    DOWNLOADS("downloads", "Downloads", Icons.Default.Download),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}

@OptIn(UnstableApi::class)
@Composable
fun CuedRoot(graph: Graph, inbound: StateFlow<Inbound?>, onInboundHandled: () -> Unit) {
    val factory = remember { CuedVmFactory(graph) }
    val lvm: LibraryViewModel = viewModel(factory = factory)
    val pvm: PlayerViewModel = viewModel(factory = factory)
    val dvm: DownloadViewModel = viewModel(factory = factory)
    val svm: ShareViewModel = viewModel(factory = factory)
    val setvm: SettingsViewModel = viewModel(factory = factory)

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val player by pvm.state.collectAsState()
    val currentTrack by pvm.currentTrack.collectAsState()
    val genreMap by lvm.genreMap.collectAsState()
    val allGenres by lvm.genres.collectAsState()
    val playlists by lvm.playlists.collectAsState()
    val smartLists by lvm.smartLists.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var sheetTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var pendingDownload by remember { mutableStateOf<String?>(null) }

    val incoming by inbound.collectAsState()
    LaunchedEffect(incoming) {
        when (val i = incoming) {
            is Inbound.Share -> { snackbar.showSnackbar(svm.receive(i.payload)) }
            is Inbound.Download -> { pendingDownload = i.source; nav.navigate(Tab.DOWNLOADS.route) { launchSingleTop = true } }
            Inbound.NowPlaying -> nav.navigate("nowplaying") { launchSingleTop = true }
            null -> {}
        }
        if (incoming != null) onInboundHandled()
    }

    val play: (List<TrackEntity>, Int) -> Unit = { list, idx -> pvm.play(list, idx) }
    val more: (TrackEntity) -> Unit = { sheetTrack = it }
    val showTabs = Tab.entries.any { it.route == route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showTabs) Column {
                MiniPlayer(player, currentTrack?.albumId, onOpen = { nav.navigate("nowplaying") }, onToggle = { pvm.togglePlay() }, onNext = { pvm.next() })
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.navigate(t.route) { popUpTo(Tab.HOME.route) { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            NavHost(nav, startDestination = Tab.HOME.route) {
                composable(Tab.HOME.route) {
                    HomeScreen(lvm, onPlay = play, onOpenList = { nav.navigate("list/${it.name}") }, onTrackMore = more)
                }
                composable(Tab.LIBRARY.route) {
                    LibraryScreen(lvm, player.trackId, onPlay = play, onTrackMore = more, onOpenGenre = { nav.navigate("genre/${URLEncoder.encode(it, "UTF-8")}") })
                }
                composable(Tab.PLAYLISTS.route) { PlaylistsScreen(lvm, onOpen = { nav.navigate("playlist/$it") }) }
                composable(Tab.DOWNLOADS.route) {
                    DownloadsScreen(dvm, initialSource = pendingDownload, onSourceConsumed = { pendingDownload = null })
                }
                composable(Tab.SETTINGS.route) { SettingsScreen(setvm, onOpenReceive = { nav.navigate("receive") }) }

                composable("nowplaying") { NowPlayingScreen(pvm, lvm, onClose = { nav.popBackStack() }, onMore = { id -> scope.launch { graph.library.track(id)?.let { sheetTrack = it } } }) }
                composable("playlist/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    PlaylistDetailScreen(lvm, e.arguments!!.getLong("id"), player.trackId, onBack = { nav.popBackStack() }, onPlay = play, onTrackMore = more)
                }
                composable("list/{kind}") { e ->
                    val kind = SmartList.valueOf(e.arguments!!.getString("kind")!!)
                    val tracks = smartLists[kind].orEmpty()
                    TrackListScreen(kind.title, kind.blurb, tracks, genreMap, playingId = player.trackId, onBack = { nav.popBackStack() }, onPlay = play, onTrackMore = more,
                        onSaveAsPlaylist = { name -> lvm.saveAsPlaylist(name, tracks.map { it.id }) { scope.launch { snackbar.showSnackbar("Saved as $name") } } })
                }
                composable("genre/{name}") { e ->
                    val genre = java.net.URLDecoder.decode(e.arguments!!.getString("name")!!, "UTF-8")
                    val flow = remember(genre) { lvm.byGenre(genre) }
                    val tracks by flow.collectAsState(initial = emptyList())
                    TrackListScreen(genre, "${tracks.size} tracks", tracks, genreMap, playingId = player.trackId, onBack = { nav.popBackStack() }, onPlay = play, onTrackMore = more,
                        onSaveAsPlaylist = { name -> lvm.saveAsPlaylist(name, tracks.map { it.id }) })
                }
                composable("similar/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    val id = e.arguments!!.getLong("id")
                    var result by remember { mutableStateOf<List<Pair<TrackEntity, List<String>>>>(emptyList()) }
                    var seed by remember { mutableStateOf<TrackEntity?>(null) }
                    LaunchedEffect(id) { seed = graph.library.track(id); result = lvm.similarTo(id) }
                    TrackListScreen("More like ${seed?.title ?: "this"}", "Genre, artist, tempo and co-play heuristics", result.map { it.first }, genreMap,
                        reasons = result.associate { it.first.id to it.second }, playingId = player.trackId, onBack = { nav.popBackStack() }, onPlay = play, onTrackMore = more,
                        onSaveAsPlaylist = { name -> lvm.saveAsPlaylist(name, result.map { it.first.id }) })
                }
                composable("share/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    val id = e.arguments!!.getLong("id")
                    val flow = remember(id) { lvm.track(id) }
                    val t by flow.collectAsState(initial = null)
                    ShareScreen(svm, id, t?.let { "${it.artist} – ${it.title}" } ?: "", onBack = { nav.popBackStack() })
                }
                composable("receive") {
                    ReceiveScreen(svm, onBack = { nav.popBackStack() }, onReceived = { msg -> scope.launch { snackbar.showSnackbar(msg) }; nav.popBackStack() })
                }
            }
        }
    }

    sheetTrack?.let { t ->
        val flow = remember(t.id) { lvm.track(t.id) }
        val live by flow.collectAsState(initial = t)
        val tr = live ?: t
        TrackSheet(
            track = tr, genres = genreMap[tr.id].orEmpty(), allGenres = allGenres, playlists = playlists,
            onDismiss = { sheetTrack = null },
            onPlay = { pvm.play(listOf(tr)) },
            onPlayNext = { pvm.playNext(tr) },
            onEnqueue = { pvm.enqueue(listOf(tr)) },
            onToggleFavourite = { lvm.toggleFavourite(tr) },
            onAddToPlaylist = { pid -> lvm.addToPlaylist(pid, tr.id) },
            onCreatePlaylistAndAdd = { name -> lvm.createPlaylist(name) { pid -> lvm.addToPlaylist(pid, tr.id) } },
            onSetGenres = { lvm.setGenres(tr.id, it) },
            onSetSourceLink = { lvm.setSourceLink(tr.id, it) },
            onShare = { nav.navigate("share/${tr.id}") },
            onSimilar = { nav.navigate("similar/${tr.id}") },
            onAnalyse = { lvm.analyse(tr.id) },
        )
    }
}
