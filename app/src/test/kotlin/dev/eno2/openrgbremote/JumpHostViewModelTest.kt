package dev.eno2.openrgbremote

import androidx.lifecycle.SavedStateHandle
import dev.eno2.openrgbremote.core.domain.model.ConnectionProblem
import dev.eno2.openrgbremote.core.domain.model.ConnectionTestResult
import dev.eno2.openrgbremote.core.domain.model.JumpHost
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.model.SshAuth
import dev.eno2.openrgbremote.core.domain.model.SshHostKey
import dev.eno2.openrgbremote.core.domain.testing.FakeConnectionRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeServerRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeSshCredentials
import dev.eno2.openrgbremote.core.domain.usecase.SaveServer
import dev.eno2.openrgbremote.core.domain.usecase.UsernameError
import dev.eno2.openrgbremote.core.domain.usecase.ValidateServerInput
import dev.eno2.openrgbremote.feature.servers.ServerEditViewModel
import dev.eno2.openrgbremote.feature.servers.TestState
import dev.eno2.openrgbremote.feature.servers.authorizedKeysCommand
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JumpHostViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val hostKey = SshHostKey("ssh-ed25519", "AAAAC3NzaC1lZDI1NTE5AAAAIBVCVjp3WzO4KkP8w0yJUCgXKVIRQ5U6aFYxM1Tq0e8t")
    private val servers = FakeServerRepository()
    private val connections = FakeConnectionRepository()
    private val credentials = FakeSshCredentials()

    private fun viewModel(vararg args: Pair<String, Any?>) =
        ServerEditViewModel(SavedStateHandle(mapOf(*args)), servers, connections, SaveServer(servers), ValidateServerInput(), credentials)

    @Test
    fun `switching to a jump host moves the address there and points OpenRGB at localhost`() = runTest {
        val vm = viewModel()
        vm.onHostChange("pc.local")
        vm.onUseJumpHostChange(true)
        assertEquals("pc.local", vm.state.value.jumpHost)
        assertEquals("127.0.0.1", vm.state.value.host)
        assertNotNull(vm.state.value.appPublicKey, "the key login is the default, so its key is shown")
        assertEquals(
            "restrict,port-forwarding,permitopen=\"127.0.0.1:6742\",command=\"/bin/false\" ${credentials.appPublicKey()}",
            vm.state.value.authorizedKeyLine,
        )
        assertTrue(authorizedKeysCommand(vm.state.value.authorizedKeyLine!!).endsWith("chmod 600 ~/.ssh/authorized_keys"))

        vm.onUseJumpHostChange(false)
        assertEquals("pc.local", vm.state.value.host, "switching back restores the direct address")
    }

    @Test
    fun `jump host input is validated`() = runTest {
        val vm = viewModel()
        vm.onUseJumpHostChange(true)
        vm.onHostChange("127.0.0.1")
        vm.onJumpHostChange("pc.local")
        vm.testConnection()
        assertEquals(UsernameError.EMPTY, vm.state.value.jumpErrors?.username)
        vm.onJumpUserChange("zeno@pc.local")
        vm.testConnection()
        assertEquals(UsernameError.INVALID, vm.state.value.jumpErrors?.username, "user@host typed into the user field")

        vm.onJumpUserChange("zeno")
        vm.onJumpAuthChange(SshAuth.PASSWORD)
        vm.testConnection()
        assertTrue(vm.state.value.passwordError)
        assertTrue(connections.calls.isEmpty(), "nothing is sent with incomplete input")
        vm.onJumpPasswordChange("secret")
        assertFalse(vm.state.value.passwordError)
    }

    @Test
    fun `the fingerprint is confirmed before saving and the password is stored encrypted`() = runTest {
        val vm = viewModel()
        vm.onHostChange("pc.local")
        vm.onUseJumpHostChange(true)
        vm.onJumpUserChange("zeno")
        vm.onJumpAuthChange(SshAuth.PASSWORD)
        vm.onJumpPasswordChange("secret")

        // Save without a confirmed key: the app asks for the fingerprint first.
        connections.testResults += ConnectionTestResult.UntrustedHostKey(hostKey, changed = false)
        vm.save()
        assertEquals(TestState.UntrustedHostKey(hostKey, false), vm.state.value.test)
        assertNull(vm.state.value.savedId)
        assertEquals(listOf("test 127.0.0.1:6742 via zeno@pc.local:22 PASSWORD key=null pw=secret"), connections.calls)

        vm.trustHostKey()
        val id = assertNotNull(vm.state.value.savedId, "saved right after confirming")
        val saved = servers.getServer(id)!!
        assertEquals(JumpHost("pc.local", 22, "zeno", SshAuth.PASSWORD, hostKey), saved.jumpHost)
        assertEquals("127.0.0.1", saved.host)
        assertEquals("pc.local", saved.name, "the jump host makes a better default name than 127.0.0.1")
        assertEquals("secret", credentials.password(id))
    }

    @Test
    fun `a confirmed key is used for the next test and forgotten when the jump host changes`() = runTest {
        val vm = viewModel()
        vm.onHostChange("pc.local")
        vm.onUseJumpHostChange(true)
        vm.onJumpUserChange("zeno")
        connections.testResults += ConnectionTestResult.UntrustedHostKey(hostKey, changed = false)
        vm.testConnection()
        vm.trustHostKey()
        assertEquals(hostKey, vm.state.value.jumpHostKey)
        assertIs<TestState.Success>(vm.state.value.test, "tested again with the key pinned")
        assertEquals("test 127.0.0.1:6742 via zeno@pc.local:22 APP_KEY key=ssh-ed25519 pw=null", connections.calls.last())

        vm.onJumpHostChange("other.local")
        assertNull(vm.state.value.jumpHostKey, "a different host must be confirmed again")

        connections.testResults += ConnectionTestResult.UntrustedHostKey(hostKey, changed = false)
        vm.testConnection()
        vm.rejectHostKey()
        assertEquals(TestState.Idle, vm.state.value.test)
        assertNull(vm.state.value.jumpHostKey)
    }

    @Test
    fun `editing keeps the saved password unless a new one is typed`() = runTest {
        servers.save(Server(7, "Desk", "127.0.0.1", jumpHost = JumpHost("pc.local", 2222, "zeno", SshAuth.PASSWORD, hostKey)))
        credentials.setPassword(7, "old")
        val vm = viewModel("serverId" to 7L)
        val state = vm.state.value
        assertTrue(state.useJumpHost && state.hasSavedPassword)
        assertEquals("2222", state.jumpPort)
        assertEquals(hostKey, state.jumpHostKey)

        vm.testConnection()
        assertEquals("test 127.0.0.1:6742 via zeno@pc.local:2222 PASSWORD key=ssh-ed25519 pw=null", connections.calls.last(), "the saved password is used")
        vm.save()
        assertEquals("old", credentials.password(7))

        val again = viewModel("serverId" to 7L)
        again.onJumpAuthChange(SshAuth.APP_KEY)
        again.save()
        assertNull(credentials.password(7), "a key login keeps no password")
        assertEquals(SshAuth.APP_KEY, servers.getServer(7)!!.jumpHost?.auth)
    }

    @Test
    fun `failures behind the jump host are shown as problems`() = runTest {
        val vm = viewModel()
        vm.onHostChange("pc.local")
        vm.onUseJumpHostChange(true)
        vm.onJumpUserChange("zeno")
        connections.testResults += ConnectionTestResult.Failure(ConnectionProblem.JUMP_HOST_AUTH_FAILED, "rejected")
        vm.save()
        assertEquals(TestState.Failure(ConnectionProblem.JUMP_HOST_AUTH_FAILED, "rejected"), vm.state.value.test)
        assertNull(vm.state.value.savedId)
    }
}
