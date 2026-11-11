package dev.eno2.openrgbremote.feature.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.core.domain.model.ConnectionProblem
import dev.eno2.openrgbremote.core.domain.model.ConnectionTestResult
import dev.eno2.openrgbremote.core.domain.repository.ConnectionRepository
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository
import dev.eno2.openrgbremote.core.domain.repository.SshCredentialRepository
import dev.eno2.openrgbremote.core.domain.model.JumpHost
import dev.eno2.openrgbremote.core.domain.model.SshAuth
import dev.eno2.openrgbremote.core.domain.model.SshHostKey
import dev.eno2.openrgbremote.core.domain.usecase.JumpHostErrors
import dev.eno2.openrgbremote.core.domain.usecase.JumpHostForm
import dev.eno2.openrgbremote.core.domain.usecase.HostError
import dev.eno2.openrgbremote.core.domain.usecase.PortError
import dev.eno2.openrgbremote.core.domain.usecase.SaveServer
import dev.eno2.openrgbremote.core.domain.usecase.SaveServerResult
import dev.eno2.openrgbremote.core.domain.usecase.ServerInputValidation
import dev.eno2.openrgbremote.core.domain.usecase.ValidateServerInput
import dev.eno2.openrgbremote.sdk.protocol.ProtocolConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val result: ConnectionTestResult.Success) : TestState
    data class Failure(val problem: ConnectionProblem, val detail: String) : TestState

    /** The jump host's key needs the user's confirmation (first contact, or it [changed]). */
    data class UntrustedHostKey(val key: SshHostKey, val changed: Boolean) : TestState
}

/**
 * Line for ~/.ssh/authorized_keys that only allows tunneling to [host]:[port]. `restrict` turns off
 * forwarding, pty and agent, `port-forwarding` re-enables forwarding, `permitopen` limits it to
 * OpenRGB, and the forced command stops shells and commands (it does not affect the tunnel).
 */
fun restrictedAuthorizedKey(publicKey: String, host: String, port: String): String {
    val target = host.trim().ifEmpty { "127.0.0.1" }
    val targetPort = port.trim().ifEmpty { ProtocolConstants.DEFAULT_PORT.toString() }
    val address = if (':' in target) "[$target]:$targetPort" else "$target:$targetPort"
    return "restrict,port-forwarding,permitopen=\"$address\",command=\"/bin/false\" $publicKey"
}

/**
 * Shell command for the jump host that adds [line] to authorized_keys with the permissions
 * OpenSSH insists on (StrictModes ignores the file if others can write to it).
 */
fun authorizedKeysCommand(line: String): String =
    "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '$line' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"

data class ServerEditUiState(
    val serverId: Long = 0,
    val name: String = "",
    val host: String = "",
    val port: String = ProtocolConstants.DEFAULT_PORT.toString(),
    val autoConnect: Boolean = true,
    val hostError: HostError? = null,
    val portError: PortError? = null,
    /** Name of the saved server that already uses this address. */
    val duplicateOf: String? = null,
    val test: TestState = TestState.Idle,
    val saving: Boolean = false,
    /** Set once saved; the screen then closes. */
    val savedId: Long? = null,
    val useJumpHost: Boolean = false,
    val jumpHost: String = "",
    val jumpPort: String = JumpHost.DEFAULT_PORT.toString(),
    val jumpUser: String = "",
    val jumpAuth: SshAuth = SshAuth.APP_KEY,
    /** Typed password; empty while editing means "keep the saved one". */
    val jumpPassword: String = "",
    val hasSavedPassword: Boolean = false,
    /** The confirmed key; reset when the jump host's address changes. */
    val jumpHostKey: SshHostKey? = null,
    val jumpErrors: JumpHostErrors? = null,
    /** Password login chosen but no password typed or saved. */
    val passwordError: Boolean = false,
    /** The app's public key, loaded when the key login is shown. */
    val appPublicKey: String? = null,
) {
    val isEdit: Boolean get() = serverId != 0L
    val passwordMissing: Boolean get() = useJumpHost && jumpAuth == SshAuth.PASSWORD && jumpPassword.isEmpty() && !hasSavedPassword

    /** What to append to authorized_keys on the jump host; null until the key is loaded. */
    val authorizedKeyLine: String? get() = appPublicKey?.let { restrictedAuthorizedKey(it, host, port) }

    fun jumpForm(): JumpHostForm? = if (useJumpHost) JumpHostForm(jumpHost, jumpPort, jumpUser, jumpAuth, jumpHostKey) else null
}

@HiltViewModel
class ServerEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val servers: ServerRepository,
    private val connections: ConnectionRepository,
    private val saveServer: SaveServer,
    private val validate: ValidateServerInput,
    private val credentials: SshCredentialRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerEditUiState())
    val state: StateFlow<ServerEditUiState> = _state.asStateFlow()
    private var testJob: Job? = null

    init {
        // Arguments of ServerEditRoute; absent when the form is embedded (onboarding).
        val serverId = savedStateHandle.get<Long>("serverId") ?: 0L
        if (serverId != 0L) {
            viewModelScope.launch {
                servers.getServer(serverId)?.let { s ->
                    val jump = s.jumpHost
                    val hasPassword = jump?.auth == SshAuth.PASSWORD && credentials.password(s.id) != null
                    _state.update {
                        it.copy(
                            serverId = s.id, name = s.name, host = s.host, port = s.port.toString(), autoConnect = s.autoConnect,
                            useJumpHost = jump != null,
                            jumpHost = jump?.host.orEmpty(),
                            jumpPort = (jump?.port ?: JumpHost.DEFAULT_PORT).toString(),
                            jumpUser = jump?.username.orEmpty(),
                            jumpAuth = jump?.auth ?: SshAuth.APP_KEY,
                            jumpHostKey = jump?.hostKey,
                            hasSavedPassword = hasPassword,
                        )
                    }
                    if (jump?.auth == SshAuth.APP_KEY) loadPublicKey()
                }
            }
        } else {
            prefill(savedStateHandle.get<String>("host"), savedStateHandle.get<Int>("port"), savedStateHandle.get<String>("name"))
        }
    }

    fun prefill(host: String?, port: Int?, name: String?) = _state.update {
        it.copy(
            host = host ?: it.host,
            port = port?.toString() ?: it.port,
            name = name ?: it.name,
            hostError = null, portError = null, duplicateOf = null, test = TestState.Idle, savedId = null,
        )
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }

    fun onHostChange(value: String) = _state.update { it.copy(host = value, hostError = null, duplicateOf = null, test = TestState.Idle) }

    fun onPortChange(value: String) =
        _state.update { it.copy(port = value.filter(Char::isDigit).take(5), portError = null, duplicateOf = null, test = TestState.Idle) }

    fun onAutoConnectChange(value: Boolean) = _state.update { it.copy(autoConnect = value) }

    /**
     * Most often OpenRGB runs on the jump host itself, so switching on moves the typed address to
     * the jump host and points OpenRGB at 127.0.0.1 (where its default server listens); switching
     * off reverses that.
     */
    fun onUseJumpHostChange(value: Boolean) {
        _state.update { s ->
            when {
                value && !s.useJumpHost && s.jumpHost.isEmpty() && s.host.isNotBlank() && s.host.trim() != LOCALHOST ->
                    s.copy(useJumpHost = true, jumpHost = s.host.trim(), host = LOCALHOST, hostError = null)
                !value && s.useJumpHost && s.host.trim() == LOCALHOST && s.jumpHost.isNotBlank() ->
                    s.copy(useJumpHost = false, host = s.jumpHost.trim(), hostError = null)
                else -> s.copy(useJumpHost = value)
            }.copy(test = TestState.Idle, duplicateOf = null, jumpErrors = null)
        }
        if (value && _state.value.jumpAuth == SshAuth.APP_KEY) loadPublicKey()
    }

    fun onJumpHostChange(value: String) = editJump(resetKey = true) { it.copy(jumpHost = value) }

    fun onJumpPortChange(value: String) = editJump(resetKey = true) { it.copy(jumpPort = value.filter(Char::isDigit).take(5)) }

    fun onJumpUserChange(value: String) = editJump { it.copy(jumpUser = value) }

    fun onJumpAuthChange(value: SshAuth) {
        editJump { it.copy(jumpAuth = value) }
        if (value == SshAuth.APP_KEY) loadPublicKey()
    }

    fun onJumpPasswordChange(value: String) = editJump { it.copy(jumpPassword = value) }

    /** The user compared the fingerprint and trusts it; the test runs again with the key pinned. */
    fun trustHostKey() {
        val pending = _state.value.test as? TestState.UntrustedHostKey ?: return
        _state.update { it.copy(jumpHostKey = pending.key, test = TestState.Idle) }
        if (saveAfterTrust) {
            saveAfterTrust = false
            save()
        } else {
            testConnection()
        }
    }

    fun rejectHostKey() {
        saveAfterTrust = false
        _state.update { if (it.test is TestState.UntrustedHostKey) it.copy(test = TestState.Idle) else it }
    }

    private var saveAfterTrust = false

    private fun editJump(resetKey: Boolean = false, transform: (ServerEditUiState) -> ServerEditUiState) = _state.update { s ->
        // A different jump host has a different key: confirming the old one would mean nothing.
        transform(s).let { if (resetKey) it.copy(jumpHostKey = null) else it }.copy(jumpErrors = null, passwordError = false, duplicateOf = null, test = TestState.Idle)
    }

    private fun loadPublicKey() {
        if (_state.value.appPublicKey != null) return
        viewModelScope.launch {
            val key = credentials.appPublicKey()
            _state.update { it.copy(appPublicKey = key) }
        }
    }

    fun testConnection() {
        val current = _state.value
        if (current.passwordMissing) {
            _state.update { it.copy(passwordError = true) }
            return
        }
        val input = when (val result = validate(current.name, current.host, current.port, current.jumpForm())) {
            is ServerInputValidation.Invalid -> {
                _state.update { it.copy(hostError = result.hostError, portError = result.portError, jumpErrors = result.jumpErrors) }
                return
            }
            is ServerInputValidation.Valid -> result.input
        }
        testJob?.cancel()
        _state.update { it.copy(test = TestState.Running) }
        testJob = viewModelScope.launch {
            val password = current.jumpPassword.takeIf { current.jumpAuth == SshAuth.PASSWORD && it.isNotEmpty() }
            val test = when (val result = connections.testConnection(input.host, input.port, input.jumpHost, password, current.serverId)) {
                is ConnectionTestResult.Success -> TestState.Success(result)
                is ConnectionTestResult.Failure -> TestState.Failure(result.problem, result.detail)
                is ConnectionTestResult.UntrustedHostKey -> TestState.UntrustedHostKey(result.key, result.changed)
            }
            // "Save" waits for a fingerprint to confirm; any other outcome is shown instead.
            if (test !is TestState.UntrustedHostKey) saveAfterTrust = false
            _state.update { state ->
                // A successful test is a good moment to suggest the PC's own name.
                val suggestedName = (test as? TestState.Success)?.result?.serverHostname
                state.copy(test = test, name = state.name.ifBlank { suggestedName.orEmpty() })
            }
        }
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        if (current.passwordMissing) {
            _state.update { it.copy(passwordError = true) }
            return
        }
        // Without a confirmed fingerprint the server could never connect: fetch it first, and save
        // right after the user confirmed it.
        if (current.useJumpHost && current.jumpHostKey == null) {
            saveAfterTrust = true
            testConnection()
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = saveServer(current.serverId, current.name, current.host, current.port, current.autoConnect, current.jumpForm())
            if (result is SaveServerResult.Saved) {
                // Only a password login keeps a password; a typed one replaces the saved one.
                when {
                    !current.useJumpHost || current.jumpAuth == SshAuth.APP_KEY -> credentials.setPassword(result.id, null)
                    current.jumpPassword.isNotEmpty() -> credentials.setPassword(result.id, current.jumpPassword)
                }
            }
            _state.update {
                when (result) {
                    is SaveServerResult.Saved -> it.copy(saving = false, savedId = result.id)
                    is SaveServerResult.Invalid -> it.copy(
                        saving = false,
                        hostError = result.validation.hostError,
                        portError = result.validation.portError,
                        jumpErrors = result.validation.jumpErrors,
                    )
                    is SaveServerResult.Duplicate -> it.copy(saving = false, duplicateOf = result.existing.name)
                }
            }
        }
    }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
