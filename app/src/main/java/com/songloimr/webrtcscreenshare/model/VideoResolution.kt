package com.songloimr.webrtcscreenshare.model

enum class VideoResolution(val width: Int, val height: Int) {
    ORIGINAL(0, 0),
    RESOLUTION_480P(640, 480),
    RESOLUTION_720P(1280, 720),
    RESOLUTION_1080P(1920, 1080);

    companion object {
        fun fromQuality(quality: Int): VideoResolution {
            return when (quality) {
                0 -> ORIGINAL
                1 -> RESOLUTION_480P
                2 -> RESOLUTION_720P
                3 -> RESOLUTION_1080P
                else -> ORIGINAL
            }
        }
    }
}