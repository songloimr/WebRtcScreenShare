package com.songloimr.webrtcscreenshare

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.songloimr.webrtcscreenshare.ScreenRecordingService.Companion.ACTION_START_SERVICE
import com.songloimr.webrtcscreenshare.ScreenRecordingService.Companion.EXTRA_PERMISSION_INTENT


class MainActivity : AppCompatActivity() {

    companion object {
        private const val START_SERVICE_REQUEST_CODE = 100
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001;
    }

    private lateinit var mService: ScreenRecordingService
    private var mBound: Boolean = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
            val binder = service as ScreenRecordingService.LocalBinder
            mService = binder.getService()
            mBound = true
            onResume()
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            mBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mBound) {
            unbindService(connection)
            mBound = false
        }
    }

    private val btnStartService by lazy {
        findViewById<MaterialButton>(R.id.btn_start_service)
    }

    private val btnOverlayPermission by lazy {
        findViewById<MaterialButton>(R.id.btn_overlay_permission)
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

        btnStartService.setOnClickListener {
            if (mBound) {
                unbindService(connection)
                mBound = false
                onResume()
                return@setOnClickListener
            }

            val intent = createCaptureIntent()
            startActivityForResult(intent, START_SERVICE_REQUEST_CODE);
        }

        btnOverlayPermission.setOnClickListener {
            if (!canDrawOverlays()) {
                requestOverlayPermission()
            }
        }

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        val canDrawOverlays = canDrawOverlays()

        btnOverlayPermission.isEnabled = !canDrawOverlays
        btnStartService.isEnabled = canDrawOverlays
        btnStartService.text = if (mBound) "Stop service" else "Start service"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            return
        }
        if (requestCode == START_SERVICE_REQUEST_CODE) {
            start(data)
            Intent(this, ScreenRecordingService::class.java).also { intent ->
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
    }

    private fun start(mediaProjectionIntent: Intent? = null) {
        val intent = Intent(this, ScreenRecordingService::class.java).apply {
            this.action = ACTION_START_SERVICE
            putExtra(EXTRA_PERMISSION_INTENT, mediaProjectionIntent)
        }
        ContextCompat.startForegroundService(this@MainActivity, intent)
    }

    private fun createCaptureIntent(): Intent {
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mediaProjectionManager.createScreenCaptureIntent()
    }
}