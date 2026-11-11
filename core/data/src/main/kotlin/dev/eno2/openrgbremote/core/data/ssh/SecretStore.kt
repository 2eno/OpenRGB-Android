package dev.eno2.openrgbremote.core.data.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small secrets (SSH passwords, the app's private key), stored encrypted. */
interface SecretStore {
    suspend fun get(name: String): String?

    /** null removes the entry. */
    suspend fun put(name: String, value: String?)
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore (hardware-backed where available, never
 * leaves it). Only ciphertexts are written, to their own SharedPreferences file, which the backup
 * rules leave out: restored elsewhere, the key would be missing anyway. If the key becomes unusable
 * (e.g. the user reset the lock screen on some devices), reading returns null and the user enters
 * the password again.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun get(name: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(name, null) ?: return@withContext null
        try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val ivLength = bytes[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 1, ivLength))
            String(cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override suspend fun put(name: String, value: String?): Unit = withContext(Dispatchers.IO) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return@withContext
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val bytes = byteArrayOf(iv.size.toByte()) + iv + encrypted
        prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "ssh_secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "openrgb-remote-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
