package com.example.wakewordpoc

import android.content.Context
import android.content.SharedPreferences

object WakeWordConfig {
    const val DEFAULT_KEYWORD_ASSET = "hey_m_android.ppn"
    const val RECORD_SECONDS = 120

    private const val PREFS = "wake_word_poc"
    private const val KEY_ACCESS_KEY = "access_key"
    private const val KEY_KEYWORD_PATH = "keyword_path"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_SERVICE_RUNNING = "service_running"
    private const val KEY_ENGINE_ACTIVE = "engine_active"
    private const val KEY_RECORDING = "recording"
    private const val KEY_LAST_DETECTION = "last_detection"
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
        prefs(context).edit().putBoolean(KEY_RECORDING, recording).apply()
    }

    fun markDetection(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_DETECTION, System.currentTimeMillis())
            .remove(KEY_LAST_ERROR)
            .apply()
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
            lastDetection = prefs.getLong(KEY_LAST_DETECTION, 0L),
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
    val lastDetection: Long,
    val lastFile: String,
    val lastError: String,
    val rootResult: String,
)
