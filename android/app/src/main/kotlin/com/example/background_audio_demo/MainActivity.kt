package com.example.background_audio_demo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.background_audio_demo/audio_service"
    private var audioService: AudioRecordingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioRecordingService.AudioRecordingBinder
            audioService = binder.getService()
            serviceBound = true
            Log.d("MainActivity", "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            serviceBound = false
            Log.d("MainActivity", "Service disconnected")
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startRecording" -> {
                    val outputPath = call.argument<String>("outputPath")
                    if (outputPath != null) {
                        AudioRecordingService.startRecording(this, outputPath)
                        result.success(true)
                    } else {
                        result.error("INVALID_ARGUMENT", "Output path is required", null)
                    }
                }
                "stopRecording" -> {
                    AudioRecordingService.stopRecording(this)
                    result.success(true)
                }
                "pauseRecording" -> {
                    AudioRecordingService.pauseRecording(this)
                    result.success(true)
                }
                "resumeRecording" -> {
                    AudioRecordingService.resumeRecording(this)
                    result.success(true)
                }
                "getRecordingState" -> {
                    val state = audioService?.getRecordingState()
                    if (state != null) {
                        val (isRecording, isPaused, outputPath) = state
                        result.success(mapOf(
                            "isRecording" to isRecording,
                            "isPaused" to isPaused,
                            "outputPath" to outputPath
                        ))
                    } else {
                        result.success(mapOf(
                            "isRecording" to false,
                            "isPaused" to false,
                            "outputPath" to null
                        ))
                    }
                }
                "requestBatteryOptimization" -> {
                    requestBatteryOptimizationExemption()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to the service
        val intent = Intent(this, AudioRecordingService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Unbind from the service
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent().apply {
                action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to request battery optimization exemption", e)
        }
    }
}
