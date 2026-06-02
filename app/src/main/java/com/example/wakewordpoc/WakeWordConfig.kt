package com.example.wakewordpoc

import android.content.Context
import android.content.SharedPreferences

object WakeWordConfig {
    const val DEFAULT_KEYWORD_ASSET = "stage2_fp16.tflite"
    const val STAGE1_MODEL_ASSET = "stage1_fp16.tflite"
    const val STAGE2_MODEL_ASSET = "stage2_fp16.tflite"
    const val RECORD_SECONDS = 120

    private const val PREFS = "wake_word_poc"
    private const val KEY_ACCESS_KEY = "access_key"
    private const val KEY_KEYWORD_PATH = "keyword_path"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_SERVICE_RUNNING = "service_running"
    private const val KEY_ENGINE_ACTIVE = "engine_active"
    private const val KEY_RECORDING = "recording"
    private const val KEY_RECORDING_STARTED = "recording_started"
    private const val KEY_LAST_DETECTION = "last_detection"
    private const val KEY_LAST_CONFIDENCE = "last_confidence"
    private const val KEY_DETECTION_COUNT = "detection_count"
    private const val KEY_LAST_STAGE1_SCORE = "last_stage1_score"
    private const val KEY_LAST_STAGE2_SCORE = "last_stage2_score"
    private const val KEY_LAST_ML_STATUS = "last_ml_status"
    private const val KEY_AUDIO_WINDOWS = "audio_windows"
    private const val KEY_LAST_FILE = "last_file"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_ROOT_RESULT = "root_result"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun accessKey(context: Context): String =
        prefs(context).getString(KEY_ACCESS_KEY, "").orEmpty()

    fun keywordPath(context: Context): String =
        prefs(context).getString(KEY_KEYWORD_PATH, DEFAULT_KEYWORD_ASSET).orEmpty()

    fun autoStart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, true)

    fun saveSettings(
        context: Context,
        accessKey: String,
        keywordPath: String,
        autoStart: Boolean,
    ) {
        prefs(context).edit()
            .putString(KEY_ACCESS_KEY, accessKey.trim())
            .putString(KEY_KEYWORD_PATH, keywordPath.trim().ifBlank { DEFAULT_KEYWORD_ASSET })
            .putBoolean(KEY_AUTO_START, autoStart)
            .apply()
    }

    fun setServiceState(context: Context, running: Boolean, engineActive: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SERVICE_RUNNING, running)
            .putBoolean(KEY_ENGINE_ACTIVE, engineActive)
            .apply()
    }

    fun setRecording(context: Context, recording: Boolean) {
        val edit = prefs(context).edit().putBoolean(KEY_RECORDING, recording)
        if (recording) {
            edit.putLong(KEY_RECORDING_STARTED, System.currentTimeMillis())
        }
        edit.apply()
    }

    fun markDetection(context: Context, confidence: Float) {
        val prefs = prefs(context)
        prefs.edit()
            .putLong(KEY_LAST_DETECTION, System.currentTimeMillis())
            .putFloat(KEY_LAST_CONFIDENCE, confidence)
            .putInt(KEY_DETECTION_COUNT, prefs.getInt(KEY_DETECTION_COUNT, 0) + 1)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun setMlStatus(
        context: Context,
        status: String,
        stage1Score: Float? = null,
        stage2Score: Float? = null,
        audioWindows: Int? = null,
    ) {
        val edit = prefs(context).edit().putString(KEY_LAST_ML_STATUS, status)
        if (stage1Score != null) edit.putFloat(KEY_LAST_STAGE1_SCORE, stage1Score)
        if (stage2Score != null) edit.putFloat(KEY_LAST_STAGE2_SCORE, stage2Score)
        if (audioWindows != null) edit.putInt(KEY_AUDIO_WINDOWS, audioWindows)
        edit.apply()
    }

    fun setLastFile(context: Context, path: String) {
        prefs(context).edit()
            .putString(KEY_LAST_FILE, path)
            .putBoolean(KEY_RECORDING, false)
            .apply()
    }

    fun setError(context: Context, message: String) {
        prefs(context).edit().putString(KEY_LAST_ERROR, message).apply()
    }

    fun setRootResult(context: Context, message: String) {
        prefs(context).edit().putString(KEY_ROOT_RESULT, message).apply()
    }

    fun snapshot(context: Context): WakeWordStatus {
        val prefs = prefs(context)
        return WakeWordStatus(
            accessKeySet = accessKey(context).isNotBlank(),
            keywordPath = keywordPath(context),
            autoStart = autoStart(context),
            serviceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false),
            engineActive = prefs.getBoolean(KEY_ENGINE_ACTIVE, false),
            recording = prefs.getBoolean(KEY_RECORDING, false),
            recordingStarted = prefs.getLong(KEY_RECORDING_STARTED, 0L),
            lastDetection = prefs.getLong(KEY_LAST_DETECTION, 0L),
            lastConfidence = prefs.getFloat(KEY_LAST_CONFIDENCE, 0f),
            detectionCount = prefs.getInt(KEY_DETECTION_COUNT, 0),
            lastStage1Score = prefs.getFloat(KEY_LAST_STAGE1_SCORE, 0f),
            lastStage2Score = prefs.getFloat(KEY_LAST_STAGE2_SCORE, 0f),
            lastMlStatus = prefs.getString(KEY_LAST_ML_STATUS, "").orEmpty(),
            audioWindows = prefs.getInt(KEY_AUDIO_WINDOWS, 0),
            lastFile = prefs.getString(KEY_LAST_FILE, "").orEmpty(),
            lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty(),
            rootResult = prefs.getString(KEY_ROOT_RESULT, "").orEmpty(),
        )
    }
}

data class WakeWordStatus(
    val accessKeySet: Boolean,
    val keywordPath: String,
    val autoStart: Boolean,
    val serviceRunning: Boolean,
    val engineActive: Boolean,
    val recording: Boolean,
    val recordingStarted: Long,
    val lastDetection: Long,
    val lastConfidence: Float,
    val detectionCount: Int,
    val lastStage1Score: Float,
    val lastStage2Score: Float,
    val lastMlStatus: String,
    val audioWindows: Int,
    val lastFile: String,
    val lastError: String,
    val rootResult: String,
)
