package com.songloimr.webrtcscreenshare.manager

import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
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
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
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
        private const val TARGET_BITRATE_BPS = 3000 * 1000
        private const val TARGET_FPS = 60
    }

    private var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private val surfaceTextureHelper: SurfaceTextureHelper by lazy {
        SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
    }

    private val eglBase: EglBase by lazy {
        EglBase.create()
    }

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // Create encoder/decoder
        val encoderFactory = HardwareVideoEncoderFactory(
            eglBase.eglBaseContext,
            false, // enableIntelVp8Encoder
            false  // enableH264HighProfile
        )
        val decoderFactory = HardwareVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory =
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()

        Log.d(TAG, "WebRTCManager initialized")
    }

    fun createPeerConnection(
        mediaProjectionIntent: Intent
    ) {
        if (peerConnection == null) {
        // Create ICE servers configuration
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=udp").apply {
                setUsername("openrelayproject")
                setPassword("openrelayproject")
            }.createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        // Create PeerConnection with observer
        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig, createPeerConnectionObserver()
        )
        }

        // Create screen capture video track
        createScreenCaptureTrack(mediaProjectionIntent)
        Log.d(TAG, "PeerConnection created successfully")
    }

    private fun createScreenCaptureTrack(mediaProjectionPermissionResultData: Intent) {
        if (localVideoTrack == null || localVideoTrack!!.state() == MediaStreamTrack.State.ENDED) {
            // Create video source
            videoSource = peerConnectionFactory.createVideoSource(true)

            // Create video track
            localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        }

        if (screenCapturer == null) {
            val mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    onConnectionStateChange(ConnectionState.Disconnected("MediaProjection stopped"))
                }
            }

            // Create screen capturer
            screenCapturer = ScreenCapturerAndroid(mediaProjectionPermissionResultData, mediaProjectionCallback)
        }
        // Initialize capturer
        screenCapturer!!.initialize(
            surfaceTextureHelper,
            context,
            videoSource?.capturerObserver
        )

        screenCapturer!!.apply {
            // Start capturing at specified resolution and frame rate
            val wm = context.getSystemService(WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(displayMetrics)

            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels

            startCapture(width, height, TARGET_FPS)
        }

        localVideoTrack!!.setEnabled(true)

        val transceiverInit = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
        )
        val transceiver = peerConnection?.addTransceiver(localVideoTrack, transceiverInit)
        val parameters = transceiver?.sender?.parameters

        parameters?.encodings?.forEach { encoding ->
            encoding.maxBitrateBps = TARGET_BITRATE_BPS
            encoding.minBitrateBps = 500 * 1000
            encoding.maxFramerate = TARGET_FPS
        }
        parameters?.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE

        // Set updated parameters to the transceiver
        transceiver?.sender?.parameters = parameters

        Log.d(TAG, "Screen capture track created")
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

            val modifiedSdp = mungeSdp(offer.description)
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

    private fun mungeSdp(originalSdp: String): String {
        data class CodecInfo(val name: String, val pattern: Pattern, val priority: Int)

        val codecPriorities = listOf(
            CodecInfo("AV1", Pattern.compile("a=rtpmap:([0-9]+) AV1/([0-9]+)"), 1),
            CodecInfo("H265", Pattern.compile("a=rtpmap:([0-9]+) H265/([0-9]+)"), 2),
            CodecInfo("H264", Pattern.compile("a=rtpmap:([0-9]+) H264/([0-9]+)"), 3)
        )

        var selectedCodecIds = mutableListOf<String>()
        var selectedCodecName: String

        for (codecInfo in codecPriorities) {
            val matcher = codecInfo.pattern.matcher(originalSdp)
            val codecIds = mutableListOf<String>()

            while (matcher.find()) {
                matcher.group(1)?.let { codecIds.add(it) }
            }

            if (codecIds.isNotEmpty()) {
                selectedCodecIds = codecIds
                selectedCodecName = codecInfo.name
                Log.d(TAG, "Selected codec: $selectedCodecName with IDs: $selectedCodecIds")
                break
            }
        }

        if (selectedCodecIds.isEmpty()) {
            Log.d(TAG, "No preferred codecs found, skipping SDP munging")
            return originalSdp
        }

        val modifiedSdp = originalSdp.split(System.lineSeparator()).filterNot {
            it.startsWith("a=extmap") ||
                    (it.startsWith("a=rtpmap") && selectedCodecIds.none { id -> it.contains(":$id ") }) ||
                    (it.startsWith("a=rtcp-fb") && selectedCodecIds.none { id -> it.contains(":$id ") }) ||
                    (it.startsWith("a=fmtp") && selectedCodecIds.none { id -> it.contains(":$id ") })
        }.toMutableList()
            .joinToString(separator = System.lineSeparator()) { line ->
                if (line.startsWith("m=video")) {
                    val parts = line.split(" ")
                    "${parts[0]} ${parts[1]} ${parts[2]} ${selectedCodecIds.joinToString(" ")}"
                } else {
                    line
                }
            }

        return modifiedSdp
    }

    fun stopConnection() {
        Log.d(TAG, "Closing PeerConnection")

        try {
            screenCapturer?.stopCapture()
        } catch (_: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            screenCapturer?.dispose()
            screenCapturer = null
        }
        localVideoTrack?.setEnabled(false)
        peerConnection?.transceivers?.forEach {
            it.stop()
        }

        Log.d(TAG, "PeerConnection closed")
    }

    fun release() {
        Log.d(TAG, "Releasing WebRTCManager")

        stopConnection()
        screenCapturer?.dispose()
        videoSource?.dispose()
        localVideoTrack?.dispose()
        surfaceTextureHelper.dispose()
        // Release factory
        peerConnectionFactory.dispose()

        // Release EglBase
        eglBase.release()

        Log.d(TAG, "WebRTCManager released")
    }

    private fun createPeerConnectionObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) {
                    return
                }
                onLocalIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "ICE candidates removed")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")

                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        onConnectionStateChange(ConnectionState.Connected)
                    }

                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        peerConnection?.restartIce()
                        onConnectionStateChange(ConnectionState.Disconnected("ICE connection closed"))
                    }

                    PeerConnection.IceConnectionState.FAILED -> {
                        peerConnection?.restartIce()
                        onConnectionStateChange(ConnectionState.Error(Exception("ICE connection failed")))
                    }

                    else -> {}
                }
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