package com.songloimr.webrtcscreenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.songloimr.webrtcscreenshare.manager.ConnectionStateManager
import com.songloimr.webrtcscreenshare.manager.SignalingManager
import com.songloimr.webrtcscreenshare.manager.WebRTCManager
import com.songloimr.webrtcscreenshare.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate

class ScreenRecordingService : Service() {

    companion object {
        private const val TAG = "ScreenRecordingService"

        // Notification constants
        const val NOTIFICATION_ID = 1012
        private const val DEFAULT_CHANNEL_ID = "screen_recording_channel"
        private const val DEFAULT_CHANNEL_NAME = "Screen Recording"
        private const val DEFAULT_NOTIFICATION_TITLE = "Screen Recording"
        private const val DEFAULT_NOTIFICATION_TEXT = "Screen recording in progress"

        // Intent actions
        const val ACTION_STOP_RECORDING = "STOP_RECORDING"
        const val ACTION_START_SERVICE = "START_SERVICE"
        const val ACTION_PERMISSION_RESULT = "PERMISSION_RESULT"

        // Intent extras
        const val EXTRA_PERMISSION_INTENT = "permission_intent"
    }

    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenRecordingService = this@ScreenRecordingService
    }

    private var mediaProjectionIntent: Intent? = null

    @Volatile
    private var isRecording = false
    private var isInitialized = false

    private var signalingManager: SignalingManager? = null
    private lateinit var webRTCManager: WebRTCManager
    private lateinit var connectionStateManager: ConnectionStateManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        if (!isInitialized) {

            // Create notification channel for Android O+
            createNotificationChannel()

            // Start as foreground service with notification
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

            handleStartService()
            isInitialized = true
        }

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                mediaProjectionIntent = parsePermissionIntent(intent)
            }

            ACTION_PERMISSION_RESULT -> {
                parsePermissionIntent(intent)?.let {
                    mediaProjectionIntent = it
                    startConnection(it)
                }
            }

            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }

        return START_STICKY
    }

    private fun parsePermissionIntent(intent: Intent?): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_PERMISSION_INTENT, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_PERMISSION_INTENT)
        }
    }

    private fun requestPermissionFromActivity() {
        val intent = Intent(this, TransparentActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ContextCompat.startActivity(this, intent, null)
    }

    private fun startConnection(intent: Intent?) {
        synchronized(this) {

            if (!this::webRTCManager.isInitialized) {
                webRTCManager = WebRTCManager(
                    context = this,
                    onLocalIceCandidate = { candidate ->
                        signalingManager?.emitIceCandidate(
                            candidate.sdp,
                            candidate.sdpMLineIndex,
                            candidate.sdpMid
                        )
                    },
                    onConnectionStateChange = { state ->
                        Log.d(TAG, "WebRTC connection state changed: $state")
                        connectionStateManager.setState(state)
                    },
                )
            }

            webRTCManager.apply {
                createPeerConnection(intent!!)
                serviceScope.launch {
                    val offer = createOffer().getOrThrow()
                    signalingManager?.emitOffer(offer)
                }
                isRecording = true
            }
        }
    }

    private fun stopConnection(){
        webRTCManager.stopConnection()
        isRecording = false
    }


    private fun handleStartService() {
        connectionStateManager = ConnectionStateManager()
        signalingManager = SignalingManager(
            onConnected = {
                Log.d(TAG, "Connected to signaling server")
                serviceScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScreenRecordingService,
                        "Connected to signaling server",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onAnswer = { sdp ->
                webRTCManager.setRemoteAnswer(sdp)
            },
            onIceCandidate = { iceMessage ->
                val candidate = IceCandidate(
                    iceMessage.sdpMid,
                    iceMessage.sdpMLineIndex,
                    iceMessage.candidate
                )
                webRTCManager.addIceCandidate(candidate)
            },
            onPermissionRequest = {
                Log.d(TAG, "Permission request received from peer")
                if (isRecording) {
                    return@SignalingManager
                }

                if (mediaProjectionIntent == null || Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) {
                    requestPermissionFromActivity()
                } else {
                    startConnection(mediaProjectionIntent)
                }
            },
            onError = { exception ->
                Log.e(TAG, "Signaling error: ${exception.message}")
                connectionStateManager.setState(ConnectionState.Error(exception))
            }
        ).apply {
            connect("https://32e160303c09.ngrok-free.app")
        }
        observeConnectionState()
    }

    private fun observeConnectionState() {
        serviceScope.launch {
            connectionStateManager.stateFlow.collectLatest { state ->
                Log.d(TAG, "Connection state changed: $state")

                when (state) {
                    is ConnectionState.Connected -> {
                        serviceScope.launch(Dispatchers.Main) {
                            Toast.makeText(
                                this@ScreenRecordingService,
                                "Screen sharing started",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    is ConnectionState.Disconnected -> {
                        stopConnection()
                        serviceScope.launch(Dispatchers.Main) {
                            Toast.makeText(
                                this@ScreenRecordingService,
                                "Disconnected: ${state.reason}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    is ConnectionState.Error -> {
                        stopConnection()
                        Log.e(TAG, "Connection error: ${state.exception.message}")
                    }

                    else -> {
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DEFAULT_CHANNEL_ID,
                DEFAULT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notification channel for screen recording service"
                setShowBadge(false)
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            Log.d(TAG, "Notification channel created")
        }
    }

    private fun createNotification(): Notification {
        // Create stop action pending intent
        val stopIntent = Intent(this, this::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(
            this, DEFAULT_CHANNEL_ID
        ).setContentTitle(DEFAULT_NOTIFICATION_TITLE).setContentText(DEFAULT_NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause, "Stop", stopPendingIntent
            ).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "$TAG onDestroy")

        if (this::webRTCManager.isInitialized) {
            webRTCManager.release()
        }
        signalingManager?.disconnect()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopSelf()
        return super.onUnbind(intent)
    }
}