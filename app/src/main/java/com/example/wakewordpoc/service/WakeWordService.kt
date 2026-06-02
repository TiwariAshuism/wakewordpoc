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
import android.speech.tts.TextToSpeech
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
    private var wakeWordDetector: TFLiteWakeWordDetector? = null
    private var recorder: MediaRecorder? = null
    private var currentOutput: File? = null
    private var recording = false
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private val stopRecordingRunnable = Runnable { stopRecordingAndResume() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wakeWordDetector = TFLiteWakeWordDetector(this) { confidence ->
            onWakeWordDetected(confidence)
        }
        textToSpeech = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                textToSpeech?.language = Locale.US
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP_RECORDING -> {
                if (recording) stopRecordingAndResume()
            }

            ACTION_SIMULATE_DETECTION -> onWakeWordDetected(1f)
            else -> startListening()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun startListening() {
        WakeWordConfig.setMlStatus(this, "Service start requested")
        if (!hasMicrophonePermission()) {
            WakeWordConfig.setError(this, "Microphone permission is missing")
            WakeWordConfig.setMlStatus(this, "Missing microphone permission")
            stopSelf()
            return
        }

        if (!startAsMicrophoneForeground("Listening for Hey M")) {
            WakeWordConfig.setMlStatus(this, "Foreground microphone start failed")
            stopSelf()
            return
        }

        WakeWordConfig.setServiceState(this, running = true, engineActive = false)

        if (recording) return

        runCatching {
            wakeWordDetector?.startListening()
            WakeWordConfig.setServiceState(this, running = true, engineActive = true)
            WakeWordConfig.setMlStatus(this, "Wake detector active")
            updateNotification("Listening for Hey M")
        }.onFailure {
            WakeWordConfig.setServiceState(this, running = true, engineActive = false)
            WakeWordConfig.setError(this, "TFLite failed: ${it.message}")
            WakeWordConfig.setMlStatus(this, "TFLite failed: ${it.message}")
            updateNotification("Wake engine inactive")
        }
    }

    private fun onWakeWordDetected(confidence: Float) {
        if (recording) return

        WakeWordConfig.markDetection(this, confidence)
        showDetectionNotification(confidence)
        speakGreeting()
        wakeScreen()
        openControlScreen()
        wakeWordDetector?.stopListening()
        WakeWordConfig.setServiceState(this, running = true, engineActive = false)
        startTwoMinuteRecording()
    }

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
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioSamplingRate(44100)
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
        }.onFailure {
            newRecorder.releaseIgnoringErrors()
            WakeWordConfig.setRecording(this, false)
            WakeWordConfig.setError(this, "Recording failed: ${it.message}")
            startListening()
        }
    }

    private fun speakGreeting() {
        if (!ttsReady) return
        textToSpeech?.speak(
            "Good morning. I am listening.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "hey_m_greeting",
        )
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

    private fun stopRecordingAndResume() {
        val output = currentOutput
        recorder?.stopIgnoringErrors()
        recorder?.releaseIgnoringErrors()
        recorder = null
        currentOutput = null
        recording = false

        if (output != null) {
            WakeWordConfig.setLastFile(this, output.absolutePath)
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
        wakeWordDetector?.release()
        wakeWordDetector = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
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
            .setContentTitle("Hey M")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.mipmap.ic_launcher, "Test", testIntent)
            .addAction(R.mipmap.ic_launcher, "Stop", stopIntent)
            .build()
    }

    private fun showDetectionNotification(confidence: Float) {
        val openIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_WAKE_EVENT, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val percent = (confidence * 100f).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(this, DETECTION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hey M detected")
            .setContentText("Confidence $percent%. Recording started.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(DETECTION_NOTIFICATION_ID, notification)
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
            description = "Persistent wake word listener"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val detectionChannel = NotificationChannel(
            DETECTION_CHANNEL_ID,
            "Hey M detections",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Wake word detection alerts"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(detectionChannel)
    }

    private fun MediaRecorder.stopIgnoringErrors() {
        runCatching { stop() }
    }

    private fun MediaRecorder.releaseIgnoringErrors() {
        runCatching { release() }
    }

    companion object {
        const val ACTION_START = "com.example.wakewordpoc.action.START"
        const val ACTION_STOP = "com.example.wakewordpoc.action.STOP"
        const val ACTION_STOP_RECORDING = "com.example.wakewordpoc.action.STOP_RECORDING"
        const val ACTION_SIMULATE_DETECTION = "com.example.wakewordpoc.action.SIMULATE_DETECTION"

        private const val CHANNEL_ID = "wake_word_listener"
        private const val DETECTION_CHANNEL_ID = "wake_word_detections"
        private const val NOTIFICATION_ID = 42
        private const val DETECTION_NOTIFICATION_ID = 44
    }
}
