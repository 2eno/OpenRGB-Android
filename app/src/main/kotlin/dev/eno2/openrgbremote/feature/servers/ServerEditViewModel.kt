package dev.eno2.openrgbremote.feature.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.core.domain.model.ConnectionProblem
import dev.eno2.openrgbremote.core.domain.model.ConnectionTestResult
import dev.eno2.openrgbremote.core.domain.repository.ConnectionRepository
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository
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
}

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
) {
    val isEdit: Boolean get() = serverId != 0L
}

@HiltViewModel
class ServerEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val servers: ServerRepository,
    private val connections: ConnectionRepository,
    private val saveServer: SaveServer,
    private val validate: ValidateServerInput,
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
                    _state.update { it.copy(serverId = s.id, name = s.name, host = s.host, port = s.port.toString(), autoConnect = s.autoConnect) }
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

    fun testConnection() {
        val current = _state.value
        val input = when (val result = validate(current.name, current.host, current.port)) {
            is ServerInputValidation.Invalid -> {
                _state.update { it.copy(hostError = result.hostError, portError = result.portError) }
                return
            }
            is ServerInputValidation.Valid -> result.input
        }
        testJob?.cancel()
        _state.update { it.copy(test = TestState.Running) }
        testJob = viewModelScope.launch {
            val test = when (val result = connections.testConnection(input.host, input.port)) {
                is ConnectionTestResult.Success -> TestState.Success(result)
                is ConnectionTestResult.Failure -> TestState.Failure(result.problem, result.detail)
            }
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
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = saveServer(current.serverId, current.name, current.host, current.port, current.autoConnect)
            _state.update {
                when (result) {
                    is SaveServerResult.Saved -> it.copy(saving = false, savedId = result.id)
                    is SaveServerResult.Invalid -> it.copy(saving = false, hostError = result.validation.hostError, portError = result.validation.portError)
                    is SaveServerResult.Duplicate -> it.copy(saving = false, duplicateOf = result.existing.name)
                }
            }
        }
    }
}
