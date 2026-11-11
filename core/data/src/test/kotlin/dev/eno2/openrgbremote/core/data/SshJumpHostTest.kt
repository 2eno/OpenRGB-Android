package dev.eno2.openrgbremote.core.data

import dev.eno2.openrgbremote.core.data.connection.ConnectionManager
import dev.eno2.openrgbremote.core.data.connection.NetworkMonitor
import dev.eno2.openrgbremote.core.data.ssh.SecretStore
import dev.eno2.openrgbremote.core.data.ssh.SshCredentials
import dev.eno2.openrgbremote.core.data.ssh.SshJumpTransports
import dev.eno2.openrgbremote.core.domain.model.ConnectionProblem
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.ConnectionTestResult
import dev.eno2.openrgbremote.core.domain.model.JumpHost
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.model.SshAuth
import dev.eno2.openrgbremote.core.domain.model.SshHostKey
import dev.eno2.openrgbremote.core.domain.testing.FakeServerRepository
import dev.eno2.openrgbremote.sdk.client.OpenRgbClientConfig
import dev.eno2.openrgbremote.sdk.client.ReconnectPolicy
import dev.eno2.openrgbremote.sdk.protocol.PacketId
import dev.eno2.openrgbremote.sdk.testing.FakeOpenRgbServer
import dev.eno2.openrgbremote.sdk.testing.closedPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.kex.KexProposalOption
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Test
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The jump host tunnel against a real SSH server (Apache MINA SSHD in this process) with a fake
 * OpenRGB server behind it: login with password and with the app's key, host key pinning, the
 * error classes, and that rejected logins are not retried.
 */
class SshJumpHostTest {

    private class MemorySecrets : SecretStore {
        val values = ConcurrentHashMap<String, String>()
        override suspend fun get(name: String) = values[name]
        override suspend fun put(name: String, value: String?) {
            if (value == null) values.remove(name) else values[name] = value
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val openRgb = FakeOpenRgbServer(6)
    private val secrets = MemorySecrets()
    private val credentials = SshCredentials(secrets)
    private val passwordAttempts = AtomicInteger()
    @Volatile private var authorizedKey: PublicKey? = null
    private val kexAlgorithms = java.util.concurrent.CopyOnWriteArraySet<String>()

    private val sshd = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider()
        passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            passwordAttempts.incrementAndGet()
            user == "zeno" && password == "secret"
        }
        publickeyAuthenticator = PublickeyAuthenticator { user, key, _ -> user == "zeno" && authorizedKey?.let { KeyUtils.compareKeys(it, key) } == true }
        keyboardInteractiveAuthenticator = null
        forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        start()
    }

    /** The host key as the user would confirm it, read from the server itself. */
    private val serverHostKey: SshHostKey = run {
        val key = sshd.keyPairProvider.loadKeys(null).first().public
        val blob = org.apache.sshd.common.util.buffer.ByteArrayBuffer().apply { putRawPublicKey(key) }.compactData
        SshHostKey(KeyUtils.getKeyType(key), Base64.getEncoder().encodeToString(blob))
    }
    private val serverFingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, sshd.keyPairProvider.loadKeys(null).first().public)

    private val network = object : NetworkMonitor { override val isOnline = MutableStateFlow(true) }
    private val config = OpenRgbClientConfig(protocolVersionTimeoutMillis = 500, heartbeatIntervalMillis = 0, deviceListDebounceMillis = 20)

    private fun manager(servers: List<Server> = emptyList()) = ConnectionManager(
        FakeServerRepository(servers), network, scope, config,
        reconnectPolicy = ReconnectPolicy(initialDelayMillis = 50, maxDelayMillis = 100, jitter = 0.0),
        jumpTransports = SshJumpTransports(credentials),
    )

    private fun jump(auth: SshAuth = SshAuth.PASSWORD, key: SshHostKey? = serverHostKey, port: Int = sshd.port) =
        JumpHost("127.0.0.1", port, "zeno", auth, key)

    @After
    fun tearDown() {
        scope.cancel()
        sshd.stop(true)
        openRgb.close()
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    @Test
    fun `first contact shows the host key fingerprint to confirm`() = test {
        val result = manager().testConnection("127.0.0.1", openRgb.port, jump(key = null), password = "secret")
        val untrusted = assertIs<ConnectionTestResult.UntrustedHostKey>(result)
        assertEquals(false, untrusted.changed)
        assertEquals(serverHostKey, untrusted.key)
        assertEquals(serverFingerprint, untrusted.key.fingerprint, "same fingerprint as ssh-keygen -lf on the server")
        assertEquals(0, passwordAttempts.get(), "no password is sent to an unconfirmed host")
    }

    @Test
    fun `password login tunnels the OpenRGB protocol`() = test {
        val result = assertIs<ConnectionTestResult.Success>(manager().testConnection("127.0.0.1", openRgb.port, jump(), password = "secret"))
        assertEquals(6, result.protocolVersion)
        assertEquals(openRgb.devices.size, result.deviceCount)

        // A saved server connects through the tunnel and LED updates arrive at OpenRGB.
        credentials.setPassword(1, "secret")
        val manager = manager(listOf(Server(1, "Desk", "127.0.0.1", openRgb.port, jumpHost = jump())))
        manager.statuses.first { it[1L] is ConnectionStatus.Connected }
        val client = manager.client(1)!!
        val device = client.devices.value.first()
        assertTrue(client.updateLeds(device.id, IntArray(device.ledCount) { 0x00FF00 }))
        repeat(50) { if (openRgb.received.none { it.packet.packetId == PacketId.RGBCONTROLLER_UPDATELEDS }) delay(50) }
        assertTrue(openRgb.received.any { it.packet.packetId == PacketId.RGBCONTROLLER_UPDATELEDS })
    }

    @Test
    fun `the app key logs in once it is authorized`() = test {
        val publicKey = credentials.appPublicKey()
        assertTrue(publicKey.startsWith("ssh-ed25519 ") && publicKey.endsWith(" openrgb-remote@android"), publicKey)
        assertEquals(publicKey, credentials.appPublicKey(), "generated once, then reused")

        val before = manager().testConnection("127.0.0.1", openRgb.port, jump(SshAuth.APP_KEY))
        assertEquals(ConnectionProblem.JUMP_HOST_AUTH_FAILED, assertIs<ConnectionTestResult.Failure>(before).problem)

        authorizedKey = AuthorizedKeyEntry.parseAuthorizedKeyEntry(publicKey).resolvePublicKey(null, PublicKeyEntryResolver.IGNORING)
        assertIs<ConnectionTestResult.Success>(manager().testConnection("127.0.0.1", openRgb.port, jump(SshAuth.APP_KEY)))
    }

    @Test
    fun `a rejected login is reported and not retried`() = test {
        val result = manager().testConnection("127.0.0.1", openRgb.port, jump(), password = "wrong")
        assertEquals(ConnectionProblem.JUMP_HOST_AUTH_FAILED, assertIs<ConnectionTestResult.Failure>(result).problem)

        credentials.setPassword(1, "wrong")
        val manager = manager(listOf(Server(1, "Desk", "127.0.0.1", openRgb.port, jumpHost = jump())))
        val failed = assertIs<ConnectionStatus.Failed>(manager.statuses.first { it[1L] is ConnectionStatus.Failed || it[1L] is ConnectionStatus.Reconnecting }[1L])
        assertEquals(ConnectionProblem.JUMP_HOST_AUTH_FAILED, failed.problem)
        val attempts = passwordAttempts.get()
        delay(500)
        assertEquals(attempts, passwordAttempts.get(), "no further logins: fail2ban would ban the phone")

        val noPassword = manager(listOf(Server(2, "Other", "127.0.0.1", openRgb.port, jumpHost = jump())))
        assertEquals(ConnectionProblem.JUMP_HOST_AUTH_FAILED, assertIs<ConnectionStatus.Failed>(noPassword.statuses.first { it[2L] is ConnectionStatus.Failed }[2L]).problem)
    }

    @Test
    fun `a different host key is refused before logging in`() = test {
        val otherKey = SshHostKey(serverHostKey.type, Base64.getEncoder().encodeToString(ByteArray(51) { 7 }))
        val result = assertIs<ConnectionTestResult.UntrustedHostKey>(manager().testConnection("127.0.0.1", openRgb.port, jump(key = otherKey), password = "secret"))
        assertTrue(result.changed)
        assertEquals(serverHostKey, result.key)
        assertNotEquals(otherKey.fingerprint, result.key.fingerprint)

        credentials.setPassword(1, "secret")
        val manager = manager(listOf(Server(1, "Desk", "127.0.0.1", openRgb.port, jumpHost = jump(key = otherKey))))
        val failed = assertIs<ConnectionStatus.Failed>(manager.statuses.first { it[1L] is ConnectionStatus.Failed }[1L])
        assertEquals(ConnectionProblem.JUMP_HOST_KEY_CHANGED, failed.problem)
        assertEquals(0, passwordAttempts.get(), "the password never reaches a host with the wrong key")
    }

    @Test
    fun `unreachable jump host and unreachable OpenRGB are told apart`() = test {
        val noSsh = manager().testConnection("127.0.0.1", openRgb.port, jump(port = closedPort()), password = "secret")
        assertEquals(ConnectionProblem.JUMP_HOST_UNREACHABLE, assertIs<ConnectionTestResult.Failure>(noSsh).problem)

        val noOpenRgb = manager().testConnection("127.0.0.1", closedPort(), jump(), password = "secret")
        assertEquals(ConnectionProblem.JUMP_TARGET_UNREACHABLE, assertIs<ConnectionTestResult.Failure>(noOpenRgb).problem)
    }

    @Test
    fun `a dropped tunnel reconnects like a dropped connection`() = test {
        credentials.setPassword(1, "secret")
        val manager = manager(listOf(Server(1, "Desk", "127.0.0.1", openRgb.port, jumpHost = jump())))
        manager.statuses.first { it[1L] is ConnectionStatus.Connected }
        sshd.activeSessions.forEach { it.close(true) }
        manager.statuses.first { it[1L] is ConnectionStatus.Reconnecting || it[1L] is ConnectionStatus.Connecting }
        manager.statuses.first { it[1L] is ConnectionStatus.Connected }
    }

    @Test
    fun `modern algorithms work through BouncyCastle like on Android`() = test {
        // An OpenSSH-like server: Ed25519 host key. The JVM's own Ed25519 is not used (see SshConfig).
        val modern = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider().apply { algorithm = org.apache.sshd.common.util.security.SecurityUtils.EDDSA }
            passwordAuthenticator = PasswordAuthenticator { _, password, _ -> password == "secret" }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            addSessionListener(object : SessionListener {
                override fun sessionNegotiationEnd(
                    session: Session, clientProposal: MutableMap<KexProposalOption, String>, serverProposal: MutableMap<KexProposalOption, String>,
                    negotiatedOptions: MutableMap<KexProposalOption, String>, reason: Throwable?,
                ) {
                    negotiatedOptions[KexProposalOption.ALGORITHMS]?.let { kexAlgorithms += it }
                }
            })
            start()
        }
        try {
            val firstResult = manager().testConnection("127.0.0.1", openRgb.port, jump(key = null, port = modern.port), password = "secret")
            val first = assertIs<ConnectionTestResult.UntrustedHostKey>(firstResult, firstResult.toString())
            assertEquals("ssh-ed25519", first.key.type)
            val success = manager().testConnection("127.0.0.1", openRgb.port, jump(key = first.key, port = modern.port), password = "secret")
            assertIs<ConnectionTestResult.Success>(success)
            val kex = kexAlgorithms.single()
            assertTrue("25519" in kex, "negotiated $kex: Curve25519 key exchange via BouncyCastle")
        } finally {
            modern.stop(true)
        }
    }
}
