// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// FILE: WakeWordService.kt - UPDATED FOR TFLite
// Replace Porcupine with TFLite Wake Word Detector
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

package com.example.wakewordpoc.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.wakewordpoc.MainActivity
import com.example.wakewordpoc.R
import com.example.wakewordpoc.WakeWordConfig
import com.example.wakewordpoc.ml.TFLiteWakeWordDetector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WakeWordService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // ✅ REPLACED: porcupineManager → TFLiteWakeWordDetector
    private var tfliteDetector: TFLiteWakeWordDetector? = null
    
    private var recorder: MediaRecorder? = null
    private var currentOutput: File? = null
    private var recording = false

    private val stopRecordingRunnable = Runnable { stopRecordingAndResume() }

    companion object {
        const val ACTION_START = "com.example.wakewordpoc.action.START"
        const val ACTION_STOP = "com.example.wakewordpoc.action.STOP"
        const val ACTION_SIMULATE_DETECTION = "com.example.wakewordpoc.action.SIMULATE_DETECTION"

        private const val CHANNEL_ID = "wake_word_listener"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "WakeWordService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // ✅ Initialize TFLite detector
        tfliteDetector = TFLiteWakeWordDetector(this)
        Log.d(TAG, "TFLite detector initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SIMULATE_DETECTION -> onWakeWordDetected()
            else -> startListening()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────────────
    // ✅ UPDATED: Listening with TFLite (replaces Porcupine)
    // ────────────────────────────────────────────────────────────────
    
    private fun startListening() {
        if (!hasMicrophonePermission()) {
            WakeWordConfig.setError(this, "Microphone permission is missing")
            stopSelf()
            return
        }

        if (!startAsMicrophoneForeground("Listening for Hey M with TFLite")) {
            stopSelf()
            return
        }

        WakeWordConfig.setServiceState(this, running = true, engineActive = false)

        if (recording || tfliteDetector == null) return

        runCatching {
            // ✅ Start TFLite detector (replaces Porcupine)
            tfliteDetector?.startListening()
            WakeWordConfig.setServiceState(this, running = true, engineActive = true)
            updateNotification("Listening for Hey M (TFLite)")
            Log.d(TAG, "✓ TFLite listener started")
        }.onFailure {
            WakeWordConfig.setServiceState(this, running = true, engineActive = false)
            WakeWordConfig.setError(this, "TFLite failed: ${it.message}")
            updateNotification("Wake engine inactive")
            Log.e(TAG, "❌ TFLite initialization failed: ${it.message}", it)
        }
    }

    private fun onWakeWordDetected() {
        if (recording) return

        Log.d(TAG, "🎤 Wake word detected!")
        WakeWordConfig.markDetection(this)
        wakeScreen()
        openControlScreen()
        
        // ✅ Stop TFLite detector (replaces stopPorcupine)
        tfliteDetector?.stopListening()
        
        startTwoMinuteRecording()
    }

    // ────────────────────────────────────────────────────────────────
    // ✅ CORRECTED: Audio Recording at 16kHz PCM (for TFLite compatibility)
    // ────────────────────────────────────────────────────────────────
    
    private fun startTwoMinuteRecording() {
        if (!hasMicrophonePermission()) {
            WakeWordConfig.setRecording(this, false)
            WakeWordConfig.setError(this, "Recording blocked: microphone permission is missing")
            return
        }

        val output = nextOutputFile()
        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        runCatching {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            
            // ✅ CRITICAL CHANGE: 16kHz PCM instead of 44.1kHz AAC
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.RAW_AMR)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.PCM_16BIT)
            newRecorder.setAudioSamplingRate(16000)  // ✅ Changed from 44100 to 16000
            newRecorder.setAudioEncodingBitRate(128000)
            
            newRecorder.setOutputFile(output.absolutePath)
            newRecorder.prepare()
            newRecorder.start()

            recorder = newRecorder
            currentOutput = output
            recording = true
            WakeWordConfig.setRecording(this, true)
            updateNotification("Recording for ${WakeWordConfig.RECORD_SECONDS}s")
            mainHandler.removeCallbacks(stopRecordingRunnable)
            mainHandler.postDelayed(
                stopRecordingRunnable,
                WakeWordConfig.RECORD_SECONDS * 1000L,
            )
            Log.d(TAG, "✓ Recording started at 16kHz PCM")
        }.onFailure {
            newRecorder.releaseIgnoringErrors()
            WakeWordConfig.setRecording(this, false)
            WakeWordConfig.setError(this, "Recording failed: ${it.message}")
            startListening()
            Log.e(TAG, "❌ Recording failed: ${it.message}", it)
        }
    }

    private fun stopRecordingAndResume() {
        val output = currentOutput
        recorder?.stopIgnoringErrors()
        recorder?.releaseIgnoringErrors()
        recorder = null
        currentOutput = null
        recording = false

        if (output != null) {
            WakeWordConfig.setLastFile(this, output.absolutePath)
            Log.d(TAG, "✓ Recording saved: ${output.absolutePath}")
        } else {
            WakeWordConfig.setRecording(this, false)
        }

        startListening()
    }

    private fun stopEverything() {
        mainHandler.removeCallbacks(stopRecordingRunnable)
        recorder?.stopIgnoringErrors()
        recorder?.releaseIgnoringErrors()
        recorder = null
        currentOutput = null
        recording = false
        
        // ✅ Stop TFLite detector
        tfliteDetector?.stopListening()
        
        WakeWordConfig.setRecording(this, false)
        WakeWordConfig.setServiceState(this, running = false, engineActive = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun nextOutputFile(): File {
        val directory = File(getExternalFilesDir(null), "wake-recordings").apply {
            mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(directory, "hey_m_$timestamp.m4a")
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val powerManager = getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "$packageName:WakeWordDetected",
        )
        wakeLock.acquire(10_000L)
    }

    private fun openControlScreen() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_WAKE_EVENT, true)
        startActivity(intent)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val testIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeWordService::class.java).setAction(ACTION_SIMULATE_DETECTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hey M (TFLite)")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.mipmap.ic_launcher, "Test", testIntent)
            .addAction(R.mipmap.ic_launcher, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hey M listener",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent wake word listener with TFLite"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun startAsMicrophoneForeground(text: String): Boolean {
        val notification = buildNotification(text)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            WakeWordConfig.setError(this, "Foreground microphone start failed: ${it.message}")
        }.isSuccess
    }

    private fun MediaRecorder.stopIgnoringErrors() {
        runCatching { stop() }
    }

    private fun MediaRecorder.releaseIgnoringErrors() {
        runCatching { release() }
    }
}
