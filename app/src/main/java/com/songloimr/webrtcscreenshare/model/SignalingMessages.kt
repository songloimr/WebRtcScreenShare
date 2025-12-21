package com.songloimr.webrtcscreenshare.model

import com.google.gson.annotations.SerializedName


data class OfferMessage(
    @SerializedName("sdp")
    val sdp: String
)

data class AnswerMessage(
    @SerializedName("sdp")
    val sdp: String
)

data class IceCandidateMessage(
    @SerializedName("candidate")
    val candidate: String,
    @SerializedName("sdpMLineIndex")
    val sdpMLineIndex: Int,
    @SerializedName("sdpMid")
    val sdpMid: String?
)
