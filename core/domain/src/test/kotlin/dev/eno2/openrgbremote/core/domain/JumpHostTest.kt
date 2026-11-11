package dev.eno2.openrgbremote.core.domain

import dev.eno2.openrgbremote.core.domain.model.JumpHost
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.model.SshAuth
import dev.eno2.openrgbremote.core.domain.model.SshHostKey
import dev.eno2.openrgbremote.core.domain.testing.FakeServerRepository
import dev.eno2.openrgbremote.core.domain.usecase.HostError
import dev.eno2.openrgbremote.core.domain.usecase.JumpHostForm
import dev.eno2.openrgbremote.core.domain.usecase.PortError
import dev.eno2.openrgbremote.core.domain.usecase.SaveServer
import dev.eno2.openrgbremote.core.domain.usecase.SaveServerResult
import dev.eno2.openrgbremote.core.domain.usecase.ServerInputValidation
import dev.eno2.openrgbremote.core.domain.usecase.UsernameError
import dev.eno2.openrgbremote.core.domain.usecase.ValidateServerInput
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class JumpHostTest {

    @Test
    fun `fingerprint matches ssh-keygen`() {
        // A real OpenSSH host key; `ssh-keygen -lf` printed this fingerprint for it.
        val key = SshHostKey("ssh-ed25519", "AAAAC3NzaC1lZDI1NTE5AAAAIHzPJK0H0Czs/WWP9M2Jvozk91qwAP9JiaUmIiwTIFll")
        assertEquals("SHA256:FPu/2aqmv4N9cDCuh+xXJSjiT+Nc3gjXkQR7KZJDyZ8", key.fingerprint)
        assertEquals(key, SshHostKey.decode(key.encode()))
        assertEquals(key, SshHostKey.decode("ssh-ed25519 ${key.base64} root@pc"), "a comment like in .pub files is ignored")
        assertNull(SshHostKey.decode("ssh-ed25519 not*base64"))
        assertNull(SshHostKey.decode(null))
    }

    @Test
    fun `jump host fields are validated and normalized`() {
        val validate = ValidateServerInput()
        val ok = assertIs<ServerInputValidation.Valid>(validate("", "127.0.0.1", "6742", JumpHostForm(" PC.local ", "2222", " zeno ", SshAuth.PASSWORD)))
        assertEquals(JumpHost("pc.local", 2222, "zeno", SshAuth.PASSWORD), ok.input.jumpHost)
        assertEquals("pc.local", ok.input.name, "named after the jump host, not 127.0.0.1")

        val bad = assertIs<ServerInputValidation.Invalid>(validate("", "127.0.0.1", "6742", JumpHostForm("", "0", "zeno@pc")))
        assertEquals(HostError.EMPTY, bad.jumpErrors?.host)
        assertEquals(PortError.OUT_OF_RANGE, bad.jumpErrors?.port)
        assertEquals(UsernameError.INVALID, bad.jumpErrors?.username)
        for (user in listOf("pi", "first.last", "_svc", "user-1", "adm\$")) {
            assertIs<ServerInputValidation.Valid>(validate("", "127.0.0.1", "6742", JumpHostForm("pc", "22", user)), user)
        }
    }

    @Test
    fun `the same OpenRGB address may be saved once per jump host`() = runTest {
        val servers = FakeServerRepository()
        val save = SaveServer(servers)
        assertIs<SaveServerResult.Saved>(save(0, "A", "127.0.0.1", "6742", jumpHost = JumpHostForm("a.local", "22", "zeno")))
        assertIs<SaveServerResult.Saved>(save(0, "B", "127.0.0.1", "6742", jumpHost = JumpHostForm("b.local", "22", "zeno")))
        assertIs<SaveServerResult.Saved>(save(0, "Local", "127.0.0.1", "6742"))
        val duplicate = assertIs<SaveServerResult.Duplicate>(save(0, "C", "127.0.0.1", "6742", jumpHost = JumpHostForm("a.local", "22", "other")))
        assertEquals("A", duplicate.existing.name)
        assertEquals(3, servers.servers.value.size)
        assertEquals(listOf(null, "a.local", "b.local"), servers.servers.value.map(Server::jumpHost).map { it?.host }.sortedBy { it ?: "" })
    }
}
