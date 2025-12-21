package com.songloimr.webrtcscreenshare.manager

import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.songloimr.webrtcscreenshare.model.ConnectionState
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRTCManager(
    private val context: Context,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionStateChange: (ConnectionState) -> Unit
) {
    companion object {
        private const val TAG = "WebRTCManager"
        private const val VIDEO_TRACK_ID = "screen_video_track"
        private const val TARGET_BITRATE_BPS = 2_000_000
        private const val TARGET_FPS = 60
    }

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private lateinit var surfaceTextureHelper: SurfaceTextureHelper

    private var isInitialized = false


    private val eglBase: EglBase by lazy {
        EglBase.create()
    }

    fun initialize() {
        // Initialize PeerConnectionFactory
        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/${PeerConnectionFactory.TRIAL_ENABLED}/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // Create encoder/decoder factories with H264 preference
        val encoderFactory = HardwareVideoEncoderFactory(
            eglBase.eglBaseContext,
            false, // enableIntelVp8Encoder
            true  // enableH264HighProfile - prefer H264
        )
        val decoderFactory = HardwareVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory =
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options()).createPeerConnectionFactory()

        isInitialized = true
        Log.d(TAG, "WebRTCManager initialized with H264 codec preference")
    }

    fun createPeerConnection(
        mediaProjectionIntent: Intent
    ) {
        // Create ICE servers configuration
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.services.mozilla.com:3478").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            sdpSemantics = PeerConnection.SdpSemantics.PLAN_B
        }

        // Create PeerConnection with observer
        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig, createPeerConnectionObserver()
        )

        // Create screen capture video track
        createScreenCaptureTrack(mediaProjectionIntent)

        Log.d(TAG, "PeerConnection created successfully")
    }

    private fun createScreenCaptureTrack(mediaProjectionIntent: Intent) {
        // Create SurfaceTextureHelper for video capture
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        // Create screen capturer
        screenCapturer = ScreenCapturerAndroid(
            mediaProjectionIntent, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    onConnectionStateChange(ConnectionState.Disconnected("MediaProjection stopped"))
                }
            })

        // Create video source
        val videoSource = peerConnectionFactory.createVideoSource(screenCapturer!!.isScreencast)

        screenCapturer?.apply {
            // Initialize capturer
            initialize(surfaceTextureHelper, context, videoSource.capturerObserver)

            // Start capturing at specified resolution and frame rate
            val wm = context.getSystemService(WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(displayMetrics)

            val width = displayMetrics.widthPixels;
            val height = displayMetrics.heightPixels;

            startCapture(width, height, TARGET_FPS)
        }

        // Create video track
        val videoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource).also {
            it.setEnabled(true)
        }

        val localStream = peerConnectionFactory.createLocalMediaStream("local_stream_video")
        localStream.addTrack(videoTrack)

        peerConnection?.addStream(localStream)

        // Configure video encoding parameters
        configureVideoEncoding()

        Log.d(TAG, "Screen capture track created")
    }

    private fun configureVideoEncoding() {
        peerConnection?.senders?.forEachIndexed { i, sender ->
            if (sender.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                val parameters = sender.parameters
                if (parameters.encodings.isNotEmpty()) {
                    parameters.encodings[0].maxBitrateBps = TARGET_BITRATE_BPS
                    parameters.encodings[0].minBitrateBps = TARGET_BITRATE_BPS
                    parameters.encodings[0].maxFramerate = TARGET_FPS

                    sender.parameters = parameters
                }
            }
        }
    }

    suspend fun createOffer(): Result<String> {
        val pc = peerConnection
            ?: return Result.failure(IllegalStateException("createOffer: PeerConnection not created"))

        return try {
            val sdpConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }

            val offer = suspendCancellableCoroutine { continuation ->
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        sdp?.let {
                            continuation.resume(it)
                        } ?: continuation.resumeWithException(Exception("SDP is null"))
                    }

                    override fun onCreateFailure(error: String?) {
                        continuation.resumeWithException(Exception("Failed to create offer: $error"))
                    }

                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, sdpConstraints)
            }

            // SDP Munging
            var modifiedSdp: String = ""
            val regex = Pattern.compile("a=rtpmap:([0-9]+) H264/([0-9]+)")
            val codecMatcher = regex.matcher(offer.description)
            if (codecMatcher.find()) {
                val codecRtpMap = codecMatcher.group(1)
                modifiedSdp = offer.description.split(System.lineSeparator()).filterNot {
                    it.startsWith("a=extmap") || it.startsWith("a=rtpmap") && !it.contains(":$codecRtpMap") || it.startsWith(
                        "a=rtcp-fb"
                    ) && !it.contains(":$codecRtpMap") || it.startsWith("a=fmtp") && !it.contains(":$codecRtpMap")
                }.toMutableList().also {
                    it.add(10, "b=AS:${TARGET_BITRATE_BPS / 1000}")
                    it.add(11, "b=TIAS:${TARGET_BITRATE_BPS}")
                }.joinToString(separator = System.lineSeparator()) {
                    if (it.startsWith("m=video")) {
                        it.split(" ").subList(0, 3).joinToString(" ") + " $codecRtpMap"
                    } else {
                        it
                    }
                }
            }
            val modifiedOffer = SessionDescription(offer.type, modifiedSdp)

            // Set local description
            suspendCancellableCoroutine { continuation ->
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        continuation.resume(Unit)
                    }

                    override fun onSetFailure(error: String?) {
                        continuation.resumeWithException(Exception("Failed to set local description: $error"))
                    }

                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, modifiedOffer)
            }

            Log.d(TAG, "SDP offer created and set as local description")
            Result.success(modifiedOffer.description)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create offer", e)
            Result.failure(e)
        }
    }
    
    fun setRemoteAnswer(sdp: String) {
        val pc = peerConnection ?: run {
            Log.e(TAG, "setRemoteAnswer: PeerConnection not created")
            return
        }

        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.i(TAG, "Remote answer set successfully")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote answer: $error")
                onConnectionStateChange(ConnectionState.Error(Exception("Failed to set remote answer: $error")))
            }

            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, sessionDescription)
    }
    
    fun addIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection ?: run {
            Log.e(TAG, "addIceCandidate: PeerConnection not created")
            return
        }

        pc.addIceCandidate(candidate)
        Log.d(TAG, "ICE candidate added: ${candidate.sdp}")
    }

    fun close() {
        Log.d(TAG, "Closing PeerConnection")

        // Stop screen capture
        try {
            screenCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen capture", e)
        }

        // Release surface texture helper
        surfaceTextureHelper.dispose()

        peerConnection?.dispose()
        peerConnection = null

        Log.d(TAG, "PeerConnection closed")
    }

    fun release() {
        Log.d(TAG, "Releasing WebRTCManager")

        close()

        // Release factory
        peerConnectionFactory.dispose()

        // Release EglBase
        eglBase.release()

        isInitialized = false

        Log.d(TAG, "WebRTCManager released")
    }

    private fun createPeerConnectionObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "Local ICE candidate: ${it.sdp}")
                    onLocalIceCandidate(it)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "ICE candidates removed")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "Connection state changed: $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        onConnectionStateChange(ConnectionState.Connected)
                    }

                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        onConnectionStateChange(ConnectionState.Disconnected("Peer disconnected"))
                    }

                    PeerConnection.PeerConnectionState.FAILED -> {
                        onConnectionStateChange(ConnectionState.Error(Exception("ICE connection failed")))
                    }

                    PeerConnection.PeerConnectionState.CLOSED -> {
                        onConnectionStateChange(ConnectionState.Disconnected("Connection closed"))
                    }

                    else -> { /* Ignore other states */
                    }
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "ICE connection receiving: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "Stream added")
            }

            override fun onRemoveStream(stream: MediaStream?) {
                Log.d(TAG, "Stream removed")
            }

            override fun onDataChannel(channel: DataChannel?) {
                Log.d(TAG, "DataChannel received: ${channel?.label()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "Track added")
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                Log.d(TAG, "Track received")
            }
        }
    }
}