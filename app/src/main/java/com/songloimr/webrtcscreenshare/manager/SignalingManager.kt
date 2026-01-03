package com.songloimr.webrtcscreenshare.manager

import android.util.Log
import com.google.gson.Gson
import com.songloimr.webrtcscreenshare.model.AnswerMessage
import com.songloimr.webrtcscreenshare.model.IceCandidateMessage
import com.songloimr.webrtcscreenshare.model.OfferMessage
import com.songloimr.webrtcscreenshare.model.SettingsUpdateMessage
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import kotlin.apply
import kotlin.collections.firstOrNull
import kotlin.jvm.java
import kotlin.let

class SignalingManager(
    private val onConnected: () -> Unit,
    private val onAnswer: (sdp: String) -> Unit,
    private val onIceCandidate: (IceCandidateMessage) -> Unit,
    private val onPermissionRequest: () -> Unit,
    private val onSettingsUpdate: (SettingsUpdateMessage) -> Unit,
    private val onError: (Exception) -> Unit
) {
    companion object {
        private const val TAG = "SignalingManager"

        private const val EVENT_ANSWER = "answer"
        private const val EVENT_ICE = "ice"
        private const val EVENT_OFFER = "offer"
        private const val EVENT_PERMISSION_REQUEST = "permission_request"
        private const val EVENT_SETTINGS_UPDATE = "settings_update"

        // Reconnection configuration
        private const val RECONNECTION_ATTEMPTS = 10
        private const val RECONNECTION_DELAY_MS = 6000L

    }

    private val gson = Gson()
    private var socket: Socket? = null
    private var reconnectionAttempts = 0

    fun connect(url: String) {
        try {
            val options = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = RECONNECTION_ATTEMPTS
                reconnectionDelay = RECONNECTION_DELAY_MS
                reconnectionDelayMax = RECONNECTION_DELAY_MS
            }

            socket = IO.socket(url, options).apply {
                setupEventListeners()
                connect()
            }

            Log.d(TAG, "Connecting to signaling server: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to signaling server", e)
            onError(kotlin.Exception("Failed to connect to signaling server: ${e.message}", e))
        }
    }

    fun disconnect() {
        socket?.let { s ->
            s.off()
            s.disconnect()
            Log.d(TAG, "Disconnected from signaling server")
        }
        socket = null
        reconnectionAttempts = 0
    }

    private fun Socket.setupEventListeners() {
        on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "Socket.IO connected")
            reconnectionAttempts = 0
            onConnected()
        }

        on(Socket.EVENT_DISCONNECT) { args ->
            val reason = args.firstOrNull()?.toString() ?: "unknown"
            Log.d(TAG, "Socket.IO disconnected: $reason")
        }

        on(Socket.EVENT_CONNECT_ERROR) { args ->
            reconnectionAttempts++
            val error = args.firstOrNull()
            Log.e(
                TAG,
                "Socket.IO connection error (attempt $reconnectionAttempts/${RECONNECTION_ATTEMPTS}): $error"
            )

            if (reconnectionAttempts >= RECONNECTION_ATTEMPTS) {
                onError(kotlin.Exception("Failed to connect after $RECONNECTION_ATTEMPTS attempts"))
            }
        }

        // Signaling events
        on(EVENT_ANSWER) { args ->
            handleAnswer(args)
        }

        on(EVENT_ICE) { args ->
            handleIceCandidate(args)
        }

        on(EVENT_PERMISSION_REQUEST) {
            Log.d(TAG, "Permission request received from peer")
            onPermissionRequest()
        }

        on(EVENT_SETTINGS_UPDATE) { args ->
            handleSettingsUpdate(args)
        }
    }

    private fun handleSettingsUpdate(args: Array<Any>) {
        try {
            val data = args.firstOrNull()
            if (data == null) {
                Log.w(TAG, "Received settings update with no data")
                return
            }

            val json = when (data) {
                is JSONObject -> data.toString()
                is String -> data
                else -> data.toString()
            }

            val message = gson.fromJson(json, SettingsUpdateMessage::class.java)
            Log.d(TAG, "Received settings update $json")
            onSettingsUpdate(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle settings update", e)
            onError(kotlin.Exception("Failed to handle settings update: ${e.message}", e))
        }
    }

    fun emitOffer(sdp: String) {
        val socket = this.socket
        if (socket == null || !socket.connected()) {
            onError(kotlin.Exception("Cannot emit offer: socket not connected"))
            return
        }

        try {
            val offerMessage = OfferMessage(sdp)
            val json = JSONObject(gson.toJson(offerMessage))
            socket.emit(EVENT_OFFER, json)
            Log.d(TAG, "Emitted offer to peer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit offer", e)
            onError(kotlin.Exception("Failed to emit offer: ${e.message}", e))
        }
    }

    fun emitIceCandidate(candidate: String, sdpMLineIndex: Int, sdpMid: String) {
        val socket = this.socket
        if (socket == null || !socket.connected()) {
            onError(kotlin.Exception("Cannot emit ICE candidate: socket not connected"))
            return
        }

        try {
            val iceMessage = IceCandidateMessage(
                candidate = candidate,
                sdpMLineIndex = sdpMLineIndex,
                sdpMid = sdpMid
            )
            val json = JSONObject(gson.toJson(iceMessage))
            socket.emit(EVENT_ICE, json)
            Log.d(TAG, "Emitted ICE candidate to peer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to emit ICE candidate", e)
            onError(kotlin.Exception("Failed to emit ICE candidate: ${e.message}", e))
        }
    }

    private fun handleAnswer(args: Array<Any>) {
        try {
            val data = args.firstOrNull()
            if (data == null) {
                Log.w(TAG, "Received answer with no data")
                return
            }

            val json = when (data) {
                is JSONObject -> data.toString()
                is String -> data
                else -> data.toString()
            }

            val message = gson.fromJson(json, AnswerMessage::class.java)
            Log.d(TAG, "Received answer $json")
            onAnswer(message.sdp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse answer", e)
            onError(kotlin.Exception("Failed to parse answer: ${e.message}", e))
        }
    }

    private fun handleIceCandidate(args: Array<Any>) {
        try {
            val data = args.firstOrNull()
            if (data == null) {
                Log.w(TAG, "Received ice with no data")
                return
            }

            val json = when (data) {
                is JSONObject -> data.toString()
                is String -> data
                else -> data.toString()
            }

            val message = gson.fromJson(json, IceCandidateMessage::class.java)
            onIceCandidate(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ice candidate", e)
            onError(kotlin.Exception("Failed to parse ice candidate: ${e.message}", e))
        }
    }

    fun connected(): Boolean {
        return socket?.connected() ?: false
    }
}