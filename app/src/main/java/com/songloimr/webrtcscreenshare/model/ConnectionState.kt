package com.songloimr.webrtcscreenshare.model

/**
 * Sealed class representing the connection state of the video streaming session.
 */
sealed class ConnectionState {
    /** Initial state before any connection attempt */
    object Idle : ConnectionState()

    /** Connection is being established */
    object Connecting : ConnectionState()

    /** Successfully connected to the remote peer */
    object Connected : ConnectionState()

    /** Disconnected from the remote peer */
    data class Disconnected(val reason: String) : ConnectionState()

    /** An error occurred during connection */
    data class Error(val exception: Exception) : ConnectionState()
}
