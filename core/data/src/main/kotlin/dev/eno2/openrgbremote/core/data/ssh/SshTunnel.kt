package dev.eno2.openrgbremote.core.data.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import dev.eno2.openrgbremote.sdk.transport.SocketConnector
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import dev.eno2.openrgbremote.core.domain.model.JumpHost
import dev.eno2.openrgbremote.core.domain.model.SshAuth
import dev.eno2.openrgbremote.core.domain.model.SshHostKey
import dev.eno2.openrgbremote.sdk.client.ConnectionError
import dev.eno2.openrgbremote.sdk.client.ConnectionErrorReason
import dev.eno2.openrgbremote.sdk.client.OpenRgbException
import dev.eno2.openrgbremote.sdk.transport.PacketTransport
import dev.eno2.openrgbremote.sdk.transport.StreamTransport
import dev.eno2.openrgbremote.sdk.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** How to log in to a jump host. */
sealed interface SshLogin {
    data class Password(val password: String) : SshLogin
    class Key(val keyPair: AppKeyPair) : SshLogin

    /** The password was never entered (or could not be decrypted any more). */
    data object MissingPassword : SshLogin
}

/** The jump host's key is not the confirmed one; carried as the cause of an [OpenRgbException]. */
class UntrustedHostKeyException(val key: SshHostKey, val changed: Boolean) :
    IOException(if (changed) "Host key of the jump host changed: ${key.fingerprint}" else "Host key not confirmed yet: ${key.fingerprint}")

/** One-time JSch configuration, shared by every session. */
internal object SshConfig {
    @Volatile private var initialized = false

    /**
     * JSch picks JCE or BouncyCastle for Ed25519 and Curve25519 by the Java version. Android
     * reports none it recognizes and lacks those algorithms in its JCE, so BouncyCastle is set
     * explicitly. That also makes the JVM unit tests run exactly the code path of the phone.
     */
    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            initialized = true
        }
    }
}

/**
 * Opens OpenRGB connections through an SSH jump host, like `ssh -J` or `ssh -L`: one SSH session
 * per connection with a `direct-tcpip` channel to the OpenRGB server as seen from the jump host.
 * No local port is opened, so other apps on the phone cannot use the tunnel.
 *
 * Host keys are pinned: only the key the user confirmed is accepted. A different key fails with
 * [ConnectionErrorReason.JUMP_HOST_KEY_CHANGED]; none confirmed yet with [ConnectionErrorReason.JUMP_HOST_KEY_UNKNOWN].
 * Both, like a rejected login, are permanent errors that are not retried automatically.
 */
class SshTunnelTransportFactory(
    private val jumpHost: JumpHost,
    private val login: suspend () -> SshLogin,
    private val keepAliveMillis: Int = 15_000,
) : TransportFactory {

    override suspend fun connect(host: String, port: Int, connectTimeoutMillis: Int): PacketTransport {
        val login = login()
        if (login is SshLogin.MissingPassword) {
            throw OpenRgbException(ConnectionErrorReason.JUMP_HOST_AUTH_FAILED, "No password saved for ${jumpHost.username}@${jumpHost.address}")
        }
        SshConfig.ensureInitialized()
        val pinned = PinnedHostKey(jumpHost.hostKey)
        val jsch = JSch().apply { setHostKeyRepository(pinned) }
        if (login is SshLogin.Key) jsch.addIdentity(KEY_NAME, login.keyPair.privateKey, null, null)
        val session = jsch.getSession(jumpHost.username, jumpHost.host, jumpHost.port).apply {
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", if (login is SshLogin.Password) "password,keyboard-interactive" else "publickey")
            if (login is SshLogin.Password) {
                setPassword(login.password.toByteArray(Charsets.UTF_8))
                setUserInfo(PasswordAnswers(login.password))
            }
            // Every address of the name, like OpenSSH (JSch alone only tries the first one).
            setSocketFactory(AllAddressesSocketFactory(connectTimeoutMillis))
            // Keeps NAT and firewalls from dropping an idle tunnel, and notices a dead one.
            setServerAliveInterval(keepAliveMillis)
            setServerAliveCountMax(3)
        }
        return blocking(onCancel = { session.disconnect() }) {
            try {
                session.connect(connectTimeoutMillis)
            } catch (e: JSchException) {
                session.disconnect()
                throw classifySessionError(e, pinned)
            }
            val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            try {
                channel.setHost(host)
                channel.setPort(port)
                val input = channel.inputStream
                val output = channel.outputStream
                channel.connect(connectTimeoutMillis)
                StreamTransport(
                    input = input,
                    output = output,
                    onClose = {
                        channel.disconnect()
                        session.disconnect()
                    },
                    isAlive = { session.isConnected && !channel.isClosed },
                )
            } catch (e: Exception) {
                channel.disconnect()
                session.disconnect()
                // Logged in fine, but the jump host could not open the connection onwards.
                throw OpenRgbException(
                    ConnectionErrorReason.JUMP_TARGET_UNREACHABLE,
                    "${jumpHost.address} could not connect to $host:$port: ${e.message}",
                    e,
                )
            }
        }
    }

    private fun classifySessionError(e: JSchException, pinned: PinnedHostKey): OpenRgbException {
        val presented = pinned.presented
        val where = "${jumpHost.username}@${jumpHost.address}"
        return when {
            e is JSchUnknownHostKeyException && presented != null ->
                OpenRgbException(ConnectionErrorReason.JUMP_HOST_KEY_UNKNOWN, e.message ?: "Unknown host key", UntrustedHostKeyException(presented, changed = false))
            e is JSchChangedHostKeyException && presented != null ->
                OpenRgbException(ConnectionErrorReason.JUMP_HOST_KEY_CHANGED, e.message ?: "Host key changed", UntrustedHostKeyException(presented, changed = true))
            // JSch's auth exceptions are package-private; their messages are "Auth fail", "Auth cancel ...".
            e.message?.startsWith("Auth ") == true ->
                OpenRgbException(ConnectionErrorReason.JUMP_HOST_AUTH_FAILED, "Login as $where rejected (${authName()})", e)
            else -> {
                // Socket problems arrive as the cause; their classification gives the detail.
                val detail = e.cause?.let { ConnectionError.from(it) }
                OpenRgbException(ConnectionErrorReason.JUMP_HOST_UNREACHABLE, "$where: ${detail?.message ?: e.message}", e.cause ?: e)
            }
        }
    }

    private fun authName() = if (jumpHost.auth == SshAuth.PASSWORD) "password" else "app key"

    /** Accepts exactly the confirmed key and remembers what the server presented. */
    private class PinnedHostKey(private val confirmed: SshHostKey?) : HostKeyRepository {
        @Volatile var presented: SshHostKey? = null

        override fun check(host: String, key: ByteArray): Int {
            val type = runCatching { HostKey(host, HostKey.GUESS, key).type }.getOrDefault("unknown")
            val seen = SshHostKey(type, Base64.getEncoder().encodeToString(key))
            presented = seen
            return when {
                confirmed == null -> HostKeyRepository.NOT_INCLUDED
                confirmed.base64 == seen.base64 -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }

        // The app stores confirmed keys itself; JSch must not add or remove any.
        override fun add(hostkey: HostKey, ui: UserInfo?) = Unit
        override fun remove(host: String, type: String?) = Unit
        override fun remove(host: String, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "openrgb-remote"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private class AllAddressesSocketFactory(private val timeoutMillis: Int) : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket = SocketConnector.connect(host, port, timeoutMillis)
        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    /** Answers "Password:" prompts of servers that use keyboard-interactive instead of password auth. */
    private class PasswordAnswers(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) = Unit

        override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<String>, echo: BooleanArray): Array<String>? =
            // Only plain password prompts; anything else (e.g. a one-time code) cannot be answered.
            if (prompt.size == 1 && !echo[0]) arrayOf(password) else null
    }

    private companion object {
        const val KEY_NAME = "openrgb-remote"
        val ioExecutor = Dispatchers.IO.asExecutor()

        /** Runs blocking JSch calls on an IO thread; cancelling disconnects, which unblocks them. */
        suspend fun <T> blocking(onCancel: () -> Unit, block: () -> T): T = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { onCancel() }
            ioExecutor.execute {
                val result = runCatching(block)
                result.fold(
                    onSuccess = { if (cont.isActive) cont.resume(it) else (it as? AutoCloseable)?.close() },
                    onFailure = { if (cont.isActive) cont.resumeWithException(it) },
                )
            }
        }
    }
}

/** Creates the transport for servers behind a jump host; separate so tests can replace it. */
fun interface JumpTransports {
    /** [password] overrides the saved one (connection test with the values typed in the form). */
    fun create(jumpHost: JumpHost, serverId: Long, password: String?): TransportFactory

    companion object {
        /** For setups without SSH support: every jump host connection fails clearly. */
        val Unsupported = JumpTransports { jumpHost, _, _ ->
            TransportFactory { _, _, _ -> throw OpenRgbException(ConnectionErrorReason.JUMP_HOST_UNREACHABLE, "SSH is not available (${jumpHost.address})") }
        }
    }
}

class SshJumpTransports(private val credentials: SshCredentials) : JumpTransports {
    override fun create(jumpHost: JumpHost, serverId: Long, password: String?): TransportFactory =
        SshTunnelTransportFactory(jumpHost, login = {
            when (jumpHost.auth) {
                SshAuth.APP_KEY -> SshLogin.Key(credentials.appKeyPair())
                SshAuth.PASSWORD -> (password ?: credentials.password(serverId))?.let { SshLogin.Password(it) } ?: SshLogin.MissingPassword
            }
        })
}
