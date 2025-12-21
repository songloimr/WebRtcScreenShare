package com.songloimr.webrtcscreenshare

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.songloimr.webrtcscreenshare.ScreenRecordingService.Companion.ACTION_PERMISSION_RESULT
import com.songloimr.webrtcscreenshare.ScreenRecordingService.Companion.ACTION_START_SERVICE
import com.songloimr.webrtcscreenshare.ScreenRecordingService.Companion.EXTRA_PERMISSION_INTENT

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 101
        private const val REQUEST_CODE_START_SERVICE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<MaterialButton>(R.id.btn_start_service).setOnClickListener {
            val intent = createCaptureIntent()
            startActivityForResult(intent, REQUEST_CODE_START_SERVICE);
        }

        findViewById<MaterialButton>(R.id.btn_start_record).setOnClickListener {
            val intent = createCaptureIntent()
            startActivityForResult(intent, REQUEST_CODE_SCREEN_CAPTURE);
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_START_SERVICE) {
            start(ACTION_START_SERVICE)
        } else if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == RESULT_OK) {
            start(ACTION_PERMISSION_RESULT, data)
        }
    }

    private fun start(action: String, mediaProjectionIntent: Intent? = null) {
        val intent = Intent(this, ScreenRecordingService::class.java).apply {
            this.action = action
            if (mediaProjectionIntent != null) {
                putExtra(EXTRA_PERMISSION_INTENT, mediaProjectionIntent)
            }
        }
        ContextCompat.startForegroundService(this@MainActivity, intent)
    }

    private fun createCaptureIntent(): Intent {
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mediaProjectionManager.createScreenCaptureIntent()
    }
}