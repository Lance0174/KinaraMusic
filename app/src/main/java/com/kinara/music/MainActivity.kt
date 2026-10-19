/* Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 * Platform lifecycle/permissions only. UI routing and composition live in ui/KinaraApp.
 */
package com.kinara.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kinara.music.account.BiliAccountViewModel
import com.kinara.music.data.LocalLibrary
import com.kinara.music.player.PlayerViewModel
import com.kinara.music.ui.KinaraApp
import com.kinara.music.ui.LibraryUiState
import com.kinara.music.ui.theme.KinaraTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val playerVm: PlayerViewModel by viewModels()
    private val accountVm: BiliAccountViewModel by viewModels()
    private var libraryState by mutableStateOf<LibraryUiState>(LibraryUiState.Loading)
    private var scan: Job? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadLibrary() else libraryState = LibraryUiState.Denied
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { if (playerVm.ui.value.playing) playerVm.syncProgress(); delay(500) }
            }
        }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            KinaraTheme(settings.theme, settings.darkMode) {
                KinaraApp(vm, playerVm, accountVm, libraryState, ::requestAudio, ::refreshLibrary)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        playerVm.ensureController()
        if (!hasAudioPermission()) { scan?.cancel(); libraryState = LibraryUiState.Denied }
        else if (libraryState !is LibraryUiState.Ready && scan?.isActive != true) loadLibrary()
    }

    private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED
    private fun refreshLibrary() { if (hasAudioPermission()) loadLibrary() else requestAudio() }
    private fun requestAudio() {
        if (hasAudioPermission()) { loadLibrary(); return }
        val prompts = getSharedPreferences("permission_prompts", MODE_PRIVATE)
        if (prompts.getBoolean(audioPermission(), false) && !shouldShowRequestPermissionRationale(audioPermission())) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            prompts.edit().putBoolean(audioPermission(), true).apply()
            permission.launch(audioPermission())
        }
    }

    private fun loadLibrary() {
        scan?.cancel()
        libraryState = LibraryUiState.Loading
        scan = lifecycleScope.launch {
            try {
                val tracks = LocalLibrary.scan(this@MainActivity)
                libraryState = if (tracks.isEmpty()) LibraryUiState.Empty else LibraryUiState.Ready(tracks)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                libraryState = if (e is SecurityException) LibraryUiState.Denied
                    else LibraryUiState.Failed("请确认音频文件仍在手机上，然后重试。")
            }
        }
    }
}
