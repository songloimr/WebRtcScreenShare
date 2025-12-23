package com.songloimr.webrtcscreenshare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.songloimr.webrtcscreenshare.ScreenRecordingService.Companion.ACTION_PERMISSION_RESULT
import com.songloimr.webrtcscreenshare.ScreenRecordingService.Companion.EXTRA_PERMISSION_INTENT

class TransparentActivity : Activity() {

    companion object {
        const val REQUEST_CODE_MEDIA_PROJECTION = 1001
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_CODE_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            val resultIntent = Intent(this, ScreenRecordingService::class.java).apply {
                action = ACTION_PERMISSION_RESULT
                putExtra(EXTRA_PERMISSION_INTENT, data)
            }
            startService(resultIntent)
        }
        finish()
    }
}
