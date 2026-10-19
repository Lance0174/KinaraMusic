/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.kinara.music.account.BiliAccountViewModel
import com.kinara.music.data.NetworkState
import com.kinara.music.data.Track
import com.kinara.music.data.withLyricOffset
import com.kinara.music.online.toTrack
import com.kinara.music.online.isEnabled
import com.kinara.music.player.PlayerViewModel
import com.kinara.music.ui.theme.KinaraDarkMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class LibrarySection(val label: String) { SONGS("本地"), FAVORITES("收藏"), PLAYLISTS("歌单"), DOWNLOADS("下载") }

/** Routing and state collection belong here; Activity only owns platform permissions/lifecycle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KinaraApp(vm: MainViewModel, playerVm: PlayerViewModel, accountVm: BiliAccountViewModel,
    libraryState: LibraryUiState, onRequestPermission: () -> Unit, onRefreshLibrary: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val player by playerVm.ui.collectAsStateWithLifecycle()
    val progress = playerVm.progress.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val imports by vm.import.collectAsStateWithLifecycle()
    val favorites by playerVm.favorites.collectAsStateWithLifecycle()
    val storedPlaylists by playerVm.playlists.collectAsStateWithLifecycle()
    val playlists = remember(storedPlaylists, favorites) { listOf(com.kinara.music.data.Playlist(com.kinara.music.data.LibraryRepository.FAVORITES, "我喜欢", 0, favorites)) + storedPlaylists }
    val lyricContent by vm.lyrics.collectAsStateWithLifecycle()
    val account by accountVm.account.collectAsStateWithLifecycle()
    val login by accountVm.login.collectAsStateWithLifecycle()
    val favoriteIds = remember(favorites) { favorites.map { it.mediaId }.toSet() }
    val context = LocalContext.current
    val files = rememberLibraryFiles(playerVm.library)
    val downloads = remember(context) { com.kinara.music.download.Downloads.get(context) }
    var isOnline by remember { mutableStateOf(NetworkState.isOnline(context)) }
    val connectionScope = rememberCoroutineScope()
    var route by rememberSaveable { mutableStateOf(KinaraNav.LIBRARY) }
    var section by rememberSaveable { mutableStateOf(LibrarySection.SONGS) }
    var localQuery by rememberSaveable { mutableStateOf("") }
    var onlineQuery by rememberSaveable { mutableStateOf("") }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var pendingAdd by remember { mutableStateOf<Track?>(null) }
    var createForTrack by remember { mutableStateOf(false) }
    var downloadTrack by remember { mutableStateOf<Track?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val savedScreens = rememberSaveableStateHolder()
    var lastAccount by remember { mutableStateOf(account) }

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
    // Hide the previous recording synchronously, before the new LaunchedEffect fetch starts.
    val lyrics = if (lyricContent.identity == com.kinara.music.data.lyricIdentity(lyricTrack)) lyricContent.lines else emptyList()
    LaunchedEffect(storedPlaylists, favorites, isOnline, settings.autoTrackInfo, settings.sources) {
        playerVm.library.refreshArtwork(settings, isOnline)
    }
    LaunchedEffect(player.current?.mediaId, lyricTrack?.onlineKey?.platform, lyricTrack?.onlineKey?.id, isOnline, settings.sources) {
        vm.loadLyric(lyricTrack)
    }
    LaunchedEffect(account) {
        if (lastAccount != account && onlineQuery.isNotBlank() && settings.sources.bilibili) vm.searchOnline(onlineQuery)
        lastAccount = account
    }
    // Restoring text after process recreation must also restore its results.
    LaunchedEffect(Unit) { if (onlineQuery.isNotBlank() && online.keyword != onlineQuery) vm.searchOnline(onlineQuery) }
    BackHandler(showPlayer) { showPlayer = false }
    BackHandler(!showPlayer && imports.title.isNotBlank()) { vm.closeImport() }
    BackHandler(!showPlayer && route != KinaraNav.LIBRARY && !login.visible) { route = KinaraNav.LIBRARY }

    CompositionLocalProvider(com.kinara.music.player.LocalPlayerProgress provides progress, LocalArtworkOnline provides isOnline) {
    if (showPlayer && player.current != null) PlayerBackdrop(emptyList()) {
        PlayScreen(player, lyrics, playerVm::togglePlayPause, playerVm::next, playerVm::previous, playerVm::seekTo,
            onClose = { showPlayer = false }, favorite = player.current?.mediaId in favoriteIds,
            onFavorite = { player.current?.let(playerVm::toggleFavorite) }, onQueuePick = playerVm::playAt,
            onAdd = { pendingAdd = player.current }, onDownload = { downloadTrack = player.current },
            onTimer = { showTimer = true }, onDiagnostics = { showDiagnostics = true }, onMode = playerVm::cycleMode,
            settings = settings, onVisualMode = { mode -> vm.update { it.copy(playerVisualMode = mode) } },
            onLyricSettings = { showPlayer = false; route = KinaraNav.SETTINGS },
            onLyricOffset = { identity, offset -> vm.update { it.withLyricOffset(identity, offset) } })
    } else KinaraShell(route, { route = it }, player, playerVm::togglePlayPause, playerVm::next, { showPlayer = true }) {
        Column(Modifier.fillMaxSize()) {
            PageTitle(route.label, when (route) {
                KinaraNav.LIBRARY -> "KINARA · 为喜欢留一个位置"
                KinaraNav.SEARCH -> "发现下一首喜欢"
                KinaraNav.SETTINGS -> "让音乐更合心意"
            }) {
                if (route == KinaraNav.LIBRARY && section == LibrarySection.SONGS) {
                    IconButton({ files.importAudio(null); section = LibrarySection.PLAYLISTS }) { KinaraIcon(Glyph.IMPORT, "导入本地歌曲") }
                    IconButton(onRefreshLibrary) { KinaraIcon(Glyph.REFRESH, "刷新音乐库") }
                }
            }
            savedScreens.SaveableStateProvider(route.name) {
                when (route) {
                    KinaraNav.LIBRARY -> Column(Modifier.fillMaxSize()) {
                        LibrarySections(section, { section = it })
                        if (imports.title.isNotBlank() && section == LibrarySection.PLAYLISTS) PlaylistResultScreen(imports.title, imports.message, imports.tracks,
                            onPlay = { t -> playerVm.playQueue(imports.tracks.map { it.toTrack(settings.preferredBitrate) }, imports.tracks.indexOf(t)) },
                            onClose = vm::closeImport, onSave = {
                                playerVm.saveImportedPlaylist(imports.title, imports.tracks.map { it.toTrack(settings.preferredBitrate) }); vm.closeImport()
                            }, importing = imports.importing)
                        else savedScreens.SaveableStateProvider("library-${section.name}") {
                            when (section) {
                                LibrarySection.SONGS -> {
                                    val tracks by produceState(emptyList<Track>(), libraryState, localQuery) {
                                        if (localQuery.isNotBlank()) delay(150)
                                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                            (libraryState as? LibraryUiState.Ready)?.let { playerVm.searchLocal(it.tracks, localQuery) }.orEmpty()
                                        }
                                    }
                                    LibraryScreen(libraryState, player, localQuery, { localQuery = it }, tracks, playerVm::playQueue,
                                        onRequestPermission, playerVm::toggleFavorite, favoriteIds, { pendingAdd = it })
                                }
                                LibrarySection.FAVORITES -> FavoriteScreen(favorites, player.current?.mediaId, playerVm::playQueue,
                                    { route = KinaraNav.SEARCH }, playerVm::toggleFavorite, { pendingAdd = it })
                                LibrarySection.PLAYLISTS -> PlaylistScreen(playlists, player.current?.mediaId, playerVm::playQueue,
                                    playerVm::createPlaylist, playerVm::renamePlaylist, playerVm::deletePlaylist, playerVm::removeFromPlaylist, vm::importNetease, vm::importQQ,
                                    files.importAudio, files.importJson, files.export)
                                LibrarySection.DOWNLOADS -> DownloadsScreen(downloads, playerVm::playQueue, { pendingAdd = it })
                            }
                        }
                    }
                    KinaraNav.SEARCH -> Column(Modifier.fillMaxSize()) {
                        SearchField(onlineQuery, { onlineQuery = it; vm.searchOnline(it) }, "歌曲、艺术家、B站视频", Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                        Spacer(Modifier.height(10.dp))
                        OnlineSearchScreen(online, { list, index ->
                            playerVm.playQueue(list.map { it.toTrack(settings.preferredBitrate) }, index)
                        }, { isOnline = NetworkState.isOnline(context); vm.searchOnline(onlineQuery) }, !isOnline, favoriteIds,
                            { playerVm.toggleFavorite(it.toTrack(settings.preferredBitrate)) }, { pendingAdd = it.toTrack(settings.preferredBitrate) },
                            enabledSources = com.kinara.music.online.SOURCE_PRIORITY.filter { settings.sources.isEnabled(it) },
                            onOpenSettings = { route = KinaraNav.SETTINGS })
                    }
                    KinaraNav.SETTINGS -> SettingsScreen(settings, vm::setTheme, { vm.setDarkMode(KinaraDarkMode.valueOf(it)) }, vm::setSource,
                        account, accountVm::openLogin, accountVm::logout, vm::setBitrate, vm::setCacheLimit, { showDiagnostics = true },
                        onPreferences = vm::update)
                }
            }
        }
    }
    }
    BiliLoginDialog(login, accountVm::openLogin, accountVm::closeLogin)
    if (showTimer) SleepTimerDialog({ showTimer = false }, playerVm::setSleepTimer)
    if (showDiagnostics) DiagnosticsDialog { showDiagnostics = false }
    downloadTrack?.let { track -> DownloadQualityDialog(track, settings.preferredBitrate, { downloadTrack = null }) { bitrate ->
        downloads.enqueue(track, bitrate); downloadTrack = null; showPlayer = false; route = KinaraNav.LIBRARY; section = LibrarySection.DOWNLOADS
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

@Composable
fun LibrarySections(current: LibrarySection, onSelect: (LibrarySection) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibrarySection.entries.forEach { section -> ChoiceChip(section.label, current == section, { onSelect(section) }) }
    }
}
