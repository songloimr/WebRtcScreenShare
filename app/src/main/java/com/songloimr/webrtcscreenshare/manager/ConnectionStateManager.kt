package com.songloimr.webrtcscreenshare.manager

import com.songloimr.webrtcscreenshare.model.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectionStateManager {
    
    private val _stateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    val stateFlow: StateFlow<ConnectionState> = _stateFlow.asStateFlow()


    fun setState(state: ConnectionState) {
        _stateFlow.value = state
    }

    fun getCurrentState(): ConnectionState {
        return _stateFlow.value
    }
}
