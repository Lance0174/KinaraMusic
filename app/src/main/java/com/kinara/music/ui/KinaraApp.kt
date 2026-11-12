/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kinara.music.MainViewModel
import com.kinara.music.data.*
import com.kinara.music.online.isEnabled
import com.kinara.music.online.toTrack
import com.kinara.music.player.PlayerViewModel
import com.kinara.music.ui.theme.KinaraDarkMode
import com.kinara.music.ui.theme.KinaraThemeKind
import com.kinara.music.ui.theme.KinaraSurfaceStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Navigation owns durable intent; the player service remains independent of presentation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KinaraApp(vm: MainViewModel, playerVm: PlayerViewModel,
    libraryState: LibraryUiState, onRequestPermission: () -> Unit, onRefreshLibrary: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val player by playerVm.ui.collectAsStateWithLifecycle()
    val progress = playerVm.progress.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val imports by vm.import.collectAsStateWithLifecycle()
    val favorites by playerVm.favorites.collectAsStateWithLifecycle()
    val storedPlaylists by playerVm.playlists.collectAsStateWithLifecycle()
    val playlists = remember(storedPlaylists, favorites) {
        listOf(Playlist(LibraryRepository.FAVORITES, "我喜欢的音乐", 0, favorites)) + storedPlaylists
    }
    val lyricContent by vm.lyrics.collectAsStateWithLifecycle()
    val favoriteIds = remember(favorites) { favorites.map { it.mediaId }.toSet() }
    val context = LocalContext.current
    val files = rememberLibraryFiles(playerVm.library)
    val downloads = remember(context) { com.kinara.music.download.Downloads.get(context) }
    var isOnline by remember { mutableStateOf(NetworkState.isOnline(context)) }
    val connectionScope = rememberCoroutineScope()
    var route by rememberSaveable { mutableStateOf(KinaraNav.LIBRARY) }
    var localQuery by rememberSaveable { mutableStateOf("") }
    var onlineQuery by rememberSaveable { mutableStateOf("") }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var queueOnOpen by rememberSaveable { mutableStateOf(false) }
    var landscapeRequested by rememberSaveable { mutableStateOf(false) }
    val openPlayer: (Boolean) -> Unit = { queue -> queueOnOpen = queue; showPlayer = true }
    val closePlayer: () -> Unit = { landscapeRequested = false; showPlayer = false }
    var pendingAdd by remember { mutableStateOf<Track?>(null) }
    var createForTrack by remember { mutableStateOf(false) }
    var downloadTrack by remember { mutableStateOf<Track?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val savedScreens = rememberSaveableStateHolder()

    DisposableEffect(context) {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { connectionScope.launch { isOnline = NetworkState.isOnline(context) } }
            override fun onLost(network: android.net.Network) { connectionScope.launch { isOnline = NetworkState.isOnline(context) } }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                connectionScope.launch { isOnline = NetworkState.isOnline(context) }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    val lyricTrack = player.actualTrack ?: player.current
    val lyrics = if (lyricContent.identity == lyricIdentity(player.current ?: lyricTrack)) lyricContent.lines else emptyList()
    LaunchedEffect(storedPlaylists, favorites, isOnline, settings.autoTrackInfo, settings.sources) {
        playerVm.library.refreshArtwork(settings, isOnline)
    }
    LaunchedEffect(player.current?.mediaId, lyricTrack?.onlineKey?.platform, lyricTrack?.onlineKey?.id, isOnline, settings.sources) {
        vm.loadLyric(lyricTrack, player.current)
    }
    LaunchedEffect(Unit) { if (onlineQuery.isNotBlank() && online.keyword != onlineQuery) vm.searchOnline(onlineQuery) }
    LaunchedEffect(showPlayer, settings.lyricRenderer) {
        if (!showPlayer || settings.lyricRenderer != LyricRenderer.FOLIA_LUMIERE) landscapeRequested = false
    }
    BackHandler(showPlayer) { if (landscapeRequested) landscapeRequested = false else closePlayer() }
    BackHandler(!showPlayer && imports.title.isNotBlank()) { vm.closeImport() }
    BackHandler(!showPlayer && route != KinaraNav.LIBRARY) { route = KinaraNav.LIBRARY }

    var libraryTracks by remember { mutableStateOf(emptyList<Track>()) }
    LaunchedEffect(libraryState, favorites, storedPlaylists) {
        libraryTracks = withContext(Dispatchers.Default) {
            ((libraryState as? LibraryUiState.Ready)?.tracks.orEmpty() + favorites + storedPlaylists.flatMap { it.tracks })
                .distinctBy { it.mediaId }
        }
    }
    var visibleTracks by remember { mutableStateOf(emptyList<Track>()) }
    LaunchedEffect(libraryTracks, localQuery) {
        if (localQuery.isNotBlank()) delay(150)
        visibleTracks = withContext(Dispatchers.Default) { playerVm.searchLocal(libraryTracks, localQuery) }
    }
    val displayLibrary = if (libraryTracks.isNotEmpty()) LibraryUiState.Ready(libraryTracks) else libraryState
    val lockedLandscape = landscapeRequested && showPlayer && player.current != null &&
        locksLandscape(PlayerEntry.EXPLICIT_LANDSCAPE, settings.lyricRenderer)
    CompositionLocalProvider(com.kinara.music.player.LocalPlayerProgress provides progress, LocalArtworkOnline provides isOnline) {
        LandscapeImmersive(lockedLandscape)
        if (showPlayer && player.current != null) PlayerBackdrop {
            PlayScreen(player, lyrics, playerVm::togglePlayPause, playerVm::next, playerVm::previous, playerVm::seekTo,
                onClose = closePlayer, favorite = player.current?.mediaId in favoriteIds,
                onFavorite = { player.current?.let(playerVm::toggleFavorite) }, onQueuePick = playerVm::playAt,
                onAdd = { pendingAdd = player.current }, onDownload = { downloadTrack = player.current },
                onTimer = { showTimer = true }, onDiagnostics = { showDiagnostics = true }, onMode = playerVm::cycleMode,
                settings = settings, onVisualMode = { mode -> vm.update { it.copy(playerVisualMode = mode) } },
                onLyricSettings = { closePlayer(); route = KinaraNav.SETTINGS },
                onLyricOffset = { identity, offset -> vm.update { it.withLyricOffset(identity, offset) } },
                onPlayNext = playerVm::playNext, onMoveInQueue = playerVm::moveInQueue,
                onRemoveFromQueue = playerVm::removeFromQueue, onSpeed = playerVm::setSpeed,
                lockedLandscape = lockedLandscape, onEnterLandscape = { landscapeRequested = true },
                onExitLandscape = { landscapeRequested = false }, initialQueue = queueOnOpen)
        } else KinaraShell(route, { route = it }, player, playerVm::togglePlayPause, playerVm::next,
            { openPlayer(false) }, artworkColors = if (settings.surfaceStyle == KinaraSurfaceStyle.GLASS &&
                settings.theme == KinaraThemeKind.DEFAULT) rememberArtworkColors(player.current) else emptyList(),
            onOpenQueue = { openPlayer(true) }, onPrevious = playerVm::previous, onSeek = playerVm::seekTo,
            onMode = playerVm::cycleMode) {
            Column(Modifier.fillMaxSize()) {
                PageTitle(route.label, when (route) {
                    KinaraNav.LIBRARY -> "你的音乐，始终在这里"
                    KinaraNav.SEARCH -> "一首喜欢的歌，值得被找到"
                    KinaraNav.PLAYLISTS -> "把喜欢，整理成自己的形状"
                    KinaraNav.DOWNLOADS -> "离线时，也有音乐陪伴"
                    KinaraNav.SETTINGS -> "让 Kinara，更像你的 Kinara"
                }) {
                    if (route != KinaraNav.SEARCH) IconButton({ route = KinaraNav.SEARCH }) { KinaraIcon(Glyph.SEARCH, "搜索音乐") }
                    if (route == KinaraNav.LIBRARY) IconButton({ files.importAudio(null) }) { KinaraIcon(Glyph.PLUS, "导入本地歌曲") }
                }
                savedScreens.SaveableStateProvider(route.name) {
                    when (route) {
                        KinaraNav.LIBRARY -> LibraryScreen(displayLibrary, player, localQuery, { localQuery = it }, visibleTracks,
                            playerVm::playQueue, onRequestPermission, playerVm::toggleFavorite, favoriteIds,
                            { pendingAdd = it }, onPlayNext = playerVm::playNext, onOpenPlayer = { openPlayer(false) },
                            onRefresh = onRefreshLibrary)
                        KinaraNav.SEARCH -> Column(Modifier.fillMaxSize()) {
                            SearchField(onlineQuery, { onlineQuery = it; vm.searchOnline(it) }, "歌曲、歌手、专辑",
                                Modifier.padding(horizontal = 22.dp, vertical = 2.dp))
                            Spacer(Modifier.height(10.dp))
                            OnlineSearchScreen(online, { list, index -> playerVm.playQueue(list.map { it.toTrack(settings.preferredBitrate) }, index) },
                                { isOnline = NetworkState.isOnline(context); vm.searchOnline(onlineQuery) }, !isOnline, favoriteIds,
                                { playerVm.toggleFavorite(it.toTrack(settings.preferredBitrate)) }, { pendingAdd = it.toTrack(settings.preferredBitrate) },
                                enabledSources = com.kinara.music.online.SOURCE_PRIORITY.filter { settings.sources.isEnabled(it) },
                                onPlayNext = { t -> playerVm.playNext(t.toTrack(settings.preferredBitrate)) },
                                onOpenSettings = { route = KinaraNav.SETTINGS })
                        }
                        KinaraNav.PLAYLISTS -> if (imports.title.isNotBlank()) PlaylistResultScreen(imports.title, imports.message, imports.tracks,
                            onPlay = { t -> playerVm.playQueue(imports.tracks.map { it.toTrack(settings.preferredBitrate) }, imports.tracks.indexOf(t)) },
                            onClose = vm::closeImport, onSave = {
                                playerVm.saveImportedPlaylist(imports.title, imports.tracks.map { it.toTrack(settings.preferredBitrate) }); vm.closeImport()
                            }, importing = imports.importing, onPlayNext = { t -> playerVm.playNext(t.toTrack(settings.preferredBitrate)) })
                        else PlaylistScreen(playlists, player.current?.mediaId, playerVm::playQueue, playerVm::createPlaylist,
                            playerVm::renamePlaylist, playerVm::deletePlaylist, playerVm::removeFromPlaylist,
                            vm::importNetease, vm::importQQ, files.importAudio, files.importJson, files.export, onPlayNext = playerVm::playNext)
                        KinaraNav.DOWNLOADS -> DownloadsScreen(downloads, playerVm::playQueue, { pendingAdd = it }, onPlayNext = playerVm::playNext)
                        KinaraNav.SETTINGS -> SettingsScreen(settings, vm::setTheme, { vm.setDarkMode(KinaraDarkMode.valueOf(it)) },
                            vm::setSource, vm::setBitrate, vm::setCacheLimit, { showDiagnostics = true },
                            onPreferences = vm::update, onPreviewLyrics = if (player.current == null) null else ({
                                vm.update { it.copy(playerVisualMode = PlayerVisualMode.LYRICS) }; openPlayer(false)
                            }))
                    }
                }
            }
        }
    }
    if (showTimer) SleepTimerDialog({ showTimer = false }, playerVm::setSleepTimer)
    if (showDiagnostics) DiagnosticsDialog { showDiagnostics = false }
    downloadTrack?.let { track -> DownloadQualityDialog(track, settings.preferredBitrate, { downloadTrack = null }) { bitrate ->
        downloads.enqueue(track, bitrate); downloadTrack = null; closePlayer(); route = KinaraNav.DOWNLOADS
    } }
    LibraryMessages(playerVm.library)
    pendingAdd?.let { track ->
        ModalBottomSheet(onDismissRequest = { pendingAdd = null; createForTrack = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("加入歌单", style = MaterialTheme.typography.headlineSmall)
                Text(track.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                OutlinedButton({ createForTrack = true }, Modifier.fillMaxWidth()) { Text("新建歌单并加入") }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(playlists, key = { it.id }) { playlist -> TextButton({ playerVm.addToPlaylist(playlist.id, track); pendingAdd = null }, Modifier.fillMaxWidth()) {
                        Text(playlist.name, Modifier.weight(1f)); Text("${playlist.tracks.size} 首")
                    } }
                }
            }
        }
        if (createForTrack) NameDialog("新建歌单", onDismiss = { createForTrack = false }) { name ->
            playerVm.saveImportedPlaylist(name, listOf(track)); createForTrack = false; pendingAdd = null
        }
    }
}
