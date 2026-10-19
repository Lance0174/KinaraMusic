/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.kinara.music.data.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class LibraryFiles(val importAudio: (String?) -> Unit, val importJson: () -> Unit, val export: (Playlist) -> Unit)

@Composable
fun rememberLibraryFiles(library: LibraryRepository): LibraryFiles {
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf("") }
    val audio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) library.importAudio(uris, target)
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(library::importJson) }
    val output = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && exporting.isNotBlank()) library.exportById(uri, exporting)
        exporting = ""
    }
    return LibraryFiles({ id -> target = id; audio.launch(arrayOf("audio/*", "application/ogg")) },
        { json.launch(arrayOf("application/json", "text/plain")) },
        { playlist -> exporting = playlist.id; output.launch("${playlist.name.take(60)}.json") })
}

@Composable
fun LibraryMessages(library: LibraryRepository) {
    val notice by library.notice.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); library.clearNotice() } }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        androidx.compose.material3.SnackbarHost(snackbar, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
            .navigationBarsPadding().padding(16.dp))
    }
}
