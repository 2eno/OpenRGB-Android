package io.github.openrgbremote.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.openrgbremote.core.domain.usecase.ScanNetwork
import io.github.openrgbremote.core.domain.usecase.ScanState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(private val scanNetwork: ScanNetwork) : ViewModel() {

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        scan()
    }

    /** (Re)starts the scan; a running scan is cancelled first. */
    fun scan() {
        job?.cancel()
        _state.value = ScanState()
        job = viewModelScope.launch { scanNetwork().collect { _state.value = it } }
    }
}
