/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34])
class LocalAudioImportTest {
    @Test fun unknownSizeProviderCannotFillPrivateStorage() = runBlocking {
        val output = java.io.ByteArrayOutputStream()
        try {
            copyImportedAudio(ByteArray(1025).inputStream(), output, maxBytes = 1024)
            fail("must reject oversized stream")
        } catch (_: IllegalStateException) { assertTrue(output.size() <= 1024) }
    }
    @Test fun privateCopyStopsWhenCancelled() = runBlocking {
        val job = kotlinx.coroutines.Job()
        val input = object : java.io.ByteArrayInputStream(ByteArray(65536)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                job.cancel(); return super.read(b, off, len)
            }
        }
        val output = java.io.ByteArrayOutputStream()
        try {
            kotlinx.coroutines.withContext(job) { copyImportedAudio(input, output) }
            fail("must cancel")
        } catch (_: kotlinx.coroutines.CancellationException) { assertTrue(output.size() < 65536) }
    }
    @Test fun missingTagsUseFilenameAndRepeatedImportKeepsStableIdentity() = checkImport(false)
    @Test @Config(shadows = [NonPersistentResolver::class])
    fun providersWithoutPersistentGrantsAreCopiedAndRemainReadable() = checkImport(true)

    private fun checkImport(expectCopy: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "fixture.wav").apply { writeBytes("RIFF0000WAVEfmt ".toByteArray() + ByteArray(128)) }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("夜航.wav")) }
            override fun getType(uri: Uri) = "audio/wav"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "kinara.fixture" })
        ShadowContentResolver.registerProviderInternal("kinara.fixture", provider)
        val uri = Uri.parse("content://kinara.fixture/audio/1")
        context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
        val result = LocalAudioImport.read(context, listOf(uri, uri))
        assertEquals(0, result.failed); assertEquals(1, result.tracks.size)
        val track = result.tracks.single()
        assertEquals("夜航", track.title); assertEquals("未知艺术家", track.artist)
        assertTrue(track.isLocal); assertNull(track.onlineKey)
        val repeated = LocalAudioImport.read(context, listOf(uri)).tracks.single()
        assertEquals(track.mediaId, repeated.mediaId); assertEquals(track.localPath, repeated.localPath)
        if (expectCopy) assertFalse(track.localPath!!.startsWith("content://"))
        if (track.localPath!!.startsWith("content://")) assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
        else assertArrayEquals(file.readBytes(), File(track.localPath!!).readBytes())
    }

    @Implements(ContentResolver::class)
    class NonPersistentResolver : ShadowContentResolver() {
        @Implementation
        override fun takePersistableUriPermission(uri: Uri, flags: Int) { throw SecurityException("provider does not persist grants") }
    }
}
