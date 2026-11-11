package dev.eno2.openrgbremote.core.data.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import dev.eno2.openrgbremote.core.domain.repository.SshCredentialRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

/** The app's SSH key pair in OpenSSH formats. */
class AppKeyPair(val privateKey: ByteArray, val publicKey: String)

/**
 * Passwords per server and one Ed25519 key pair for the whole app, both kept in the [SecretStore].
 * The key is generated on first use, like `ssh-keygen -t ed25519`; the user adds its public key to
 * `~/.ssh/authorized_keys` on the jump host. It is never exported.
 */
class SshCredentials(private val store: SecretStore) : SshCredentialRepository {
    private val keyLock = Mutex()

    override suspend fun appPublicKey(): String = appKeyPair().publicKey

    suspend fun appKeyPair(): AppKeyPair = keyLock.withLock {
        val privateKey = store.get(PRIVATE_KEY)
        val publicKey = store.get(PUBLIC_KEY)
        if (privateKey != null && publicKey != null) return AppKeyPair(privateKey.toByteArray(), publicKey)

        SshConfig.ensureInitialized()
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        try {
            // No passphrase: the store already encrypts it with a device-bound key.
            val privateOut = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, null) }
            val publicOut = ByteArrayOutputStream().also { pair.writePublicKey(it, KEY_COMMENT) }
            val generated = AppKeyPair(privateOut.toByteArray(), publicOut.toString(Charsets.UTF_8.name()).trim())
            store.put(PRIVATE_KEY, String(generated.privateKey, Charsets.UTF_8))
            store.put(PUBLIC_KEY, generated.publicKey)
            generated
        } finally {
            pair.dispose()
        }
    }

    override suspend fun password(serverId: Long): String? = store.get(passwordName(serverId))

    override suspend fun setPassword(serverId: Long, password: String?) = store.put(passwordName(serverId), password?.takeIf { it.isNotEmpty() })

    private fun passwordName(serverId: Long) = "password_$serverId"

    private companion object {
        const val PRIVATE_KEY = "app_key_private"
        const val PUBLIC_KEY = "app_key_public"
        const val KEY_COMMENT = "openrgb-remote@android"
    }
}
