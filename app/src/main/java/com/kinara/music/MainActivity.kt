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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kinara.music.player.PlayerViewModel
import com.kinara.music.ui.KinaraApp
import com.kinara.music.ui.theme.KinaraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val playerVm: PlayerViewModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.loadLibrary(force = true) else vm.markLibraryDenied()
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
            // 曲库状态改由 ViewModel 持有：转屏不再回到 Loading，也就不会重复全量扫描。
            val libraryState by vm.library.collectAsStateWithLifecycle()
            KinaraTheme(settings.theme, settings.darkMode, settings.surfaceStyle) {
                KinaraApp(vm, playerVm, libraryState, ::requestAudio, ::refreshLibrary)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        playerVm.ensureController()
        if (!hasAudioPermission()) vm.markLibraryDenied() else vm.loadLibrary()
    }

    private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED
    private fun refreshLibrary() { if (hasAudioPermission()) vm.loadLibrary(force = true) else requestAudio() }
    private fun requestAudio() {
        if (hasAudioPermission()) { vm.loadLibrary(force = true); return }
        val prompts = getSharedPreferences("permission_prompts", MODE_PRIVATE)
        if (prompts.getBoolean(audioPermission(), false) && !shouldShowRequestPermissionRationale(audioPermission())) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            prompts.edit().putBoolean(audioPermission(), true).apply()
            permission.launch(audioPermission())
        }
    }
}
