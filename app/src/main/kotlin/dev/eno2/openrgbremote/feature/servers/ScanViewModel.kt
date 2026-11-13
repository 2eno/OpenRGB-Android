package dev.eno2.openrgbremote.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.core.domain.repository.LocalNetworkPermission
import dev.eno2.openrgbremote.core.domain.usecase.ScanNetwork
import dev.eno2.openrgbremote.core.domain.usecase.ScanState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Scans as soon as it can: without the local network permission (Android 17+) every probe would
 * only time out and the scan would wrongly report "nothing found", so it waits for the grant.
 */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanNetwork: ScanNetwork,
    private val localNetwork: LocalNetworkPermission,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        scan()
        viewModelScope.launch { localNetwork.granted.drop(1).filter { it }.collect { if (_state.value.permissionMissing) scan() } }
    }

    /** (Re)starts the scan; a running scan is cancelled first. */
    fun scan() {
        job?.cancel()
        if (!localNetwork.granted.value) {
            _state.value = ScanState(running = false, permissionMissing = true)
            return
        }
        _state.value = ScanState()
        job = viewModelScope.launch { scanNetwork().collect { _state.value = it } }
    }
}
