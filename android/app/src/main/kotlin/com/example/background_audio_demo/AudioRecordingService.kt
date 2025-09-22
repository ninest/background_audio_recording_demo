package com.example.background_audio_demo

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException

class AudioRecordingService : Service() {
    companion object {
        private const val TAG = "AudioRecordingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_recording_channel"
        private const val ACTION_START_RECORDING = "START_RECORDING"
        private const val ACTION_STOP_RECORDING = "STOP_RECORDING"
        private const val ACTION_PAUSE_RECORDING = "PAUSE_RECORDING"
        private const val ACTION_RESUME_RECORDING = "RESUME_RECORDING"
        
        fun startRecording(context: Context, outputPath: String) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra("output_path", outputPath)
            }
            context.startForegroundService(intent)
        }
        
        fun stopRecording(context: Context) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }
        
        fun pauseRecording(context: Context) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_PAUSE_RECORDING
            }
            context.startService(intent)
        }
        
        fun resumeRecording(context: Context) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_RESUME_RECORDING
            }
            context.startService(intent)
        }
    }

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var isPaused = false
    private var outputPath: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = AudioRecordingBinder()
    private var heartbeatHandler: Handler? = null
    private var heartbeatRunnable: Runnable? = null

    inner class AudioRecordingBinder : Binder() {
        fun getService(): AudioRecordingService = this@AudioRecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        acquireWakeLock()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with action: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val path = intent.getStringExtra("output_path")
                if (path != null) {
                    startRecording(path)
                }
            }
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_PAUSE_RECORDING -> pauseRecording()
            ACTION_RESUME_RECORDING -> resumeRecording()
        }
        
        return START_STICKY // Restart service if killed by system
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for background audio recording"
                setSound(null, null)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(isRecording: Boolean, isPaused: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            !isRecording -> "Audio Recording Service"
            isPaused -> "Recording Paused"
            else -> "Recording in Progress"
        }

        val text = when {
            !isRecording -> "Ready to record"
            isPaused -> "Tap to return to app"
            else -> "Recording audio in background"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudioRecordingService::WakeLock"
        )
        // Acquire indefinitely for recording - will be released when service stops
        wakeLock?.acquire()
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun startHeartbeat() {
        heartbeatHandler = Handler(Looper.getMainLooper())
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    Log.d(TAG, "Heartbeat - Recording active (isPaused: $isPaused)")
                    // Update notification to show we're still alive
                    startForeground(NOTIFICATION_ID, createNotification(isRecording, isPaused))
                }
                // Schedule next heartbeat in 30 seconds
                heartbeatHandler?.postDelayed(this, 30000)
            }
        }
        heartbeatHandler?.post(heartbeatRunnable!!)
        Log.d(TAG, "Heartbeat started")
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler?.removeCallbacks(it) }
        heartbeatHandler = null
        heartbeatRunnable = null
        Log.d(TAG, "Heartbeat stopped")
    }

    private fun startRecording(path: String) {
        try {
            if (isRecording) {
                Log.w(TAG, "Already recording")
                return
            }

            outputPath = path

            // Ensure directory exists
            val file = File(path)
            file.parentFile?.mkdirs()

            Log.d(TAG, "Starting MediaRecorder for path: $path")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                try {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                    setOutputFile(path)

                    Log.d(TAG, "MediaRecorder configured, preparing...")
                    prepare()
                    Log.d(TAG, "MediaRecorder prepared, starting...")
                    start()
                    Log.d(TAG, "MediaRecorder started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to configure MediaRecorder", e)
                    release()
                    throw e
                }
            }

            isRecording = true
            isPaused = false

            startForeground(NOTIFICATION_ID, createNotification(true, false))
            Log.d(TAG, "Recording started successfully: $path")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            mediaRecorder?.release()
            mediaRecorder = null
            stopSelf()
        }
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) {
            Log.w(TAG, "Cannot pause - not recording or already paused (isRecording: $isRecording, isPaused: $isPaused)")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Log.d(TAG, "Pausing MediaRecorder...")
                mediaRecorder?.pause()
                isPaused = true
                startForeground(NOTIFICATION_ID, createNotification(true, true))
                Log.d(TAG, "Recording paused successfully")
            } else {
                Log.w(TAG, "Pause not supported on Android version ${Build.VERSION.SDK_INT}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording", e)
        }
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) {
            Log.w(TAG, "Cannot resume - not recording or not paused (isRecording: $isRecording, isPaused: $isPaused)")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Log.d(TAG, "Resuming MediaRecorder...")
                mediaRecorder?.resume()
                isPaused = false
                startForeground(NOTIFICATION_ID, createNotification(true, false))
                Log.d(TAG, "Recording resumed successfully")
            } else {
                Log.w(TAG, "Resume not supported on Android version ${Build.VERSION.SDK_INT}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recording", e)
        }
    }

    private fun stopRecording() {
        try {
            Log.d(TAG, "Stopping recording (isRecording: $isRecording, isPaused: $isPaused)")
            mediaRecorder?.apply {
                if (isRecording) {
                    Log.d(TAG, "Stopping MediaRecorder...")
                    stop()
                    Log.d(TAG, "Releasing MediaRecorder...")
                    release()
                }
            }
            mediaRecorder = null
            isRecording = false
            isPaused = false

            Log.d(TAG, "Recording stopped successfully, saved to: $outputPath")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            // Still clean up
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            isPaused = false
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopHeartbeat()
        stopRecording()
        releaseWakeLock()
        super.onDestroy()
    }

    fun getRecordingState(): Triple<Boolean, Boolean, String?> {
        return Triple(isRecording, isPaused, outputPath)
    }
}
