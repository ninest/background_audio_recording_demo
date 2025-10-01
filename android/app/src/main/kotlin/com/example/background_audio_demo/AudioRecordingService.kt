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
        private const val EXTRA_OUTPUT_PATH = "output_path"
        private const val EXTRA_CHUNK_DURATION_MS = "chunk_duration_ms"
        
        fun startRecording(context: Context, outputPath: String, chunkDurationMs: Long? = null) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_OUTPUT_PATH, outputPath)
                if (chunkDurationMs != null && chunkDurationMs > 0L) {
                    putExtra(EXTRA_CHUNK_DURATION_MS, chunkDurationMs)
                }
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
    private var chunkDurationMs: Long? = null
    private var chunkIndex: Int = 0
    private var basePathWithoutExt: String? = null
    private var fileExtension: String = ".m4a"
    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = AudioRecordingBinder()
    private var heartbeatHandler: Handler? = null
    private var heartbeatRunnable: Runnable? = null
    private var rotateHandler: Handler? = null
    private var rotateRunnable: Runnable? = null

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
                val path = intent.getStringExtra(EXTRA_OUTPUT_PATH)
                if (path != null) {
                    val maybeChunkMs: Long? = if (intent.hasExtra(EXTRA_CHUNK_DURATION_MS)) intent.getLongExtra(EXTRA_CHUNK_DURATION_MS, 0L) else null
                    startRecording(path, maybeChunkMs?.takeIf { it > 0L })
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

    private fun startRecording(path: String, requestedChunkDurationMs: Long? = null) {
        try {
            if (isRecording) {
                Log.w(TAG, "Already recording")
                return
            }

            // Setup chunking configuration
            setupChunking(path, requestedChunkDurationMs)

            // Ensure directory exists
            val currentFilePath = currentChunkFilePath()
            val file = File(currentFilePath)
            file.parentFile?.mkdirs()

            Log.d(TAG, "Starting MediaRecorder for path: $currentFilePath (chunkDurationMs=$chunkDurationMs)")

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
                    setOutputFile(currentFilePath)

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
            outputPath = currentFilePath

            startForeground(NOTIFICATION_ID, createNotification(true, false))
            Log.d(TAG, "Recording started successfully: $currentFilePath")

            // Start chunk rotation timer if enabled
            startChunkRotationTimer()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            mediaRecorder?.release()
            mediaRecorder = null
            stopSelf()
        }
    }

    private fun setupChunking(initialPath: String, requestedChunkDurationMs: Long?) {
        // Derive base name and extension
        val file = File(initialPath)
        val name = file.name
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex > 0) {
            fileExtension = name.substring(dotIndex)
            basePathWithoutExt = File(file.parent ?: "", name.substring(0, dotIndex)).path
        } else {
            fileExtension = ".m4a"
            basePathWithoutExt = initialPath
        }
        chunkIndex = 1
        chunkDurationMs = requestedChunkDurationMs?.takeIf { it > 0L }
        Log.d(TAG, "Chunking configured: base=$basePathWithoutExt ext=$fileExtension chunkMs=$chunkDurationMs")
    }

    private fun currentChunkFilePath(): String {
        val base = basePathWithoutExt ?: return outputPath ?: ""
        return String.format("%s_%04d%s", base, chunkIndex, fileExtension)
    }

    private fun rotateToNextChunk() {
        if (!isRecording || isPaused) {
            // Will be rescheduled on resume if needed
            return
        }
        try {
            Log.d(TAG, "Rotating to next chunk from index=$chunkIndex")
            // Stop current recorder
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null

            // Increment index and start new file
            chunkIndex += 1
            val nextPath = currentChunkFilePath()
            val file = File(nextPath)
            file.parentFile?.mkdirs()

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
                    setOutputFile(nextPath)

                    prepare()
                    start()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start next chunk recorder", e)
                    release()
                    throw e
                }
            }

            outputPath = nextPath
            startForeground(NOTIFICATION_ID, createNotification(true, false))
            Log.d(TAG, "Recording continued in new chunk: $nextPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed during chunk rotation", e)
        }
    }

    private fun startChunkRotationTimer() {
        // Clear any previous timer
        rotateRunnable?.let { rotateHandler?.removeCallbacks(it) }
        rotateHandler = Handler(Looper.getMainLooper())
        val duration = chunkDurationMs
        if (duration == null || duration <= 0L) {
            Log.d(TAG, "Chunk rotation disabled")
            return
        }
        rotateRunnable = object : Runnable {
            override fun run() {
                if (isRecording && !isPaused) {
                    rotateToNextChunk()
                }
                // Schedule next rotation
                rotateHandler?.postDelayed(this, duration)
            }
        }
        rotateHandler?.postDelayed(rotateRunnable!!, duration)
        Log.d(TAG, "Chunk rotation timer started: every ${duration}ms")
    }

    private fun stopChunkRotationTimer() {
        rotateRunnable?.let { rotateHandler?.removeCallbacks(it) }
        rotateRunnable = null
        rotateHandler = null
        Log.d(TAG, "Chunk rotation timer stopped")
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
                // Pause chunk rotation timer
                stopChunkRotationTimer()
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
                // Resume chunk rotation timer from full interval
                startChunkRotationTimer()
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
            // Stop timers first
            stopChunkRotationTimer()
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
