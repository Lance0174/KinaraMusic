/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials live in the private no-backup directory, encrypted by an Android Keystore key.
 * Cookies are used only by BiliApi against api.bilibili.com, never by the shared Worker/CDN.
 */
object BiliSession : BiliLoginStorage {
    data class Account(val name: String, val userId: String)
    private lateinit var storage: AtomicFile
    private val lock = Mutex()
    private var loaded = false
    @Volatile private var cookieValue = ""
    @Volatile var revision = 0L
        private set
    private val _account = MutableStateFlow<Account?>(null)
    override val account: StateFlow<Account?> = _account

    fun initialize(context: Context) {
        if (!::storage.isInitialized) storage = AtomicFile(File(context.noBackupFilesDir, "bili-session.enc"))
    }

    suspend fun cookie(): String { load(); return cookieValue }

    override suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (loaded || !::storage.isInitialized) return@withLock
            loaded = true
            if (!storage.baseFile.exists()) return@withLock
            try {
                val bytes = storage.readFully()
                require(bytes.size > 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
                cookieValue = json.getString("cookie")
                _account.value = Account(json.getString("name"), json.getString("uid"))
            } catch (_: Exception) {
                // A restored file without its device key cannot be decrypted. Ask for login again.
                storage.delete()
                cookieValue = ""
                _account.value = null
            }
        }
    }

    override suspend fun save(cookie: String, account: Account) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(::storage.isInitialized)
            val plain = JSONObject().put("cookie", cookie).put("name", account.name).put("uid", account.userId)
                .toString().toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.iv + cipher.doFinal(plain)
            val output = storage.startWrite()
            try { output.write(encrypted); storage.finishWrite(output) }
            catch (e: Exception) { storage.failWrite(output); throw e }
            cookieValue = cookie
            loaded = true
            revision += 1
            _account.value = account
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (::storage.isInitialized) storage.delete()
            cookieValue = ""
            _account.value = null
            loaded = true
            revision += 1
        }
    }

    private fun key(): SecretKey {
        val alias = "kinara.bilibili.session.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
