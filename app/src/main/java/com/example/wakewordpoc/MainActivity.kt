package com.example.wakewordpoc

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.wakewordpoc.service.WakeWordService
import com.example.wakewordpoc.ui.theme.WakewordpocTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent?.getBooleanExtra(EXTRA_WAKE_EVENT, false) == true) {
            requestKeyguardDismissal()
        }

        setContent {
            WakewordpocTheme {
                HeyMScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_WAKE_EVENT, false)) {
            requestKeyguardDismissal()
        }
    }

    private fun requestKeyguardDismissal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(KEYGUARD_SERVICE) as KeyguardManager)
                .newKeyguardLock("HeyM")
                .disableKeyguard()
        }
    }

    companion object {
        const val EXTRA_WAKE_EVENT = "wake_event"
    }
}

@Composable
private fun HeyMScreen() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(WakeWordConfig.snapshot(context)) }
    var accessKey by remember { mutableStateOf(WakeWordConfig.accessKey(context)) }
    var keywordPath by remember { mutableStateOf(WakeWordConfig.keywordPath(context)) }
    var autoStart by remember { mutableStateOf(WakeWordConfig.autoStart(context)) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            pendingAction?.invoke()
        }
        pendingAction = null
        status = WakeWordConfig.snapshot(context)
    }

    fun runWithPermissions(action: () -> Unit) {
        if (hasRuntimePermissions(context)) {
            action()
        } else {
            pendingAction = action
            requestPermissions(context, permissionsLauncher::launch)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            status = WakeWordConfig.snapshot(context)
            delay(1000L)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Hey M",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Wake listener: ${status.engineLabel()}",
                style = MaterialTheme.typography.titleMedium,
            )

            ControlCard(title = "Runtime") {
                StatusLine("Service", if (status.serviceRunning) "running" else "stopped")
                StatusLine("Recorder", if (status.recording) "recording" else "idle")
                StatusLine("Last trigger", status.lastDetectionLabel())
                StatusLine("Last file", status.lastFile.ifBlank { "none" })
                if (status.lastError.isNotBlank()) {
                    StatusLine("Last error", status.lastError)
                }
            }

            ControlCard(title = "Picovoice") {
                OutlinedTextField(
                    value = accessKey,
                    onValueChange = { accessKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("AccessKey") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Password,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keywordPath,
                    onValueChange = { keywordPath = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Keyword asset/path") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoStart, onCheckedChange = { autoStart = it })
                    Text("Start after boot")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        WakeWordConfig.saveSettings(context, accessKey, keywordPath, autoStart)
                        status = WakeWordConfig.snapshot(context)
                    }) {
                        Text("Save")
                    }
                    TextButton(onClick = {
                        keywordPath = WakeWordConfig.DEFAULT_KEYWORD_ASSET
                    }) {
                        Text("Use asset")
                    }
                }
            }

            ControlCard(title = "Controls") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runWithPermissions { startWakeService(context) }
                        },
                    ) {
                        Text("Start")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { stopWakeService(context) },
                    ) {
                        Text("Stop")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runWithPermissions { simulateDetection(context) }
                    },
                ) {
                    Text("Simulate Wake")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { openBatterySettings(context) }) {
                    Text("Battery Settings")
                }
            }

            ControlCard(title = "Root") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runRootCommand(
                                context,
                                "settings put global stay_on_while_plugged_in 3",
                            )
                            status = WakeWordConfig.snapshot(context)
                        },
                    ) {
                        Text("Stay Awake")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runRootCommand(context, "svc power stayon true")
                            status = WakeWordConfig.snapshot(context)
                        },
                    ) {
                        Text("Power Stayon")
                    }
                }
                if (status.rootResult.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status.rootResult, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ControlCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun WakeWordStatus.engineLabel(): String = when {
    recording -> "recording"
    engineActive -> "active"
    serviceRunning && !accessKeySet -> "waiting for AccessKey"
    serviceRunning -> "service ready"
    else -> "off"
}

private fun WakeWordStatus.lastDetectionLabel(): String {
    if (lastDetection <= 0L) return "none"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        .format(Date(lastDetection))
}

private fun requestPermissions(
    context: Context,
    launch: (Array<String>) -> Unit,
) {
    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
    launch(permissions)
}

private fun hasRuntimePermissions(context: Context): Boolean {
    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun startWakeService(context: Context) {
    val intent = Intent(context, WakeWordService::class.java)
        .setAction(WakeWordService.ACTION_START)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopWakeService(context: Context) {
    context.startService(
        Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP),
    )
}

private fun simulateDetection(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, WakeWordService::class.java)
            .setAction(WakeWordService.ACTION_SIMULATE_DETECTION),
    )
}

private fun openBatterySettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
        .onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        }
}

private fun runRootCommand(context: Context, command: String) {
    runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        WakeWordConfig.setRootResult(
            context,
            if (code == 0) "OK: $command" else "Failed ($code): ${output.ifBlank { command }}",
        )
    }.onFailure {
        WakeWordConfig.setRootResult(context, "Root unavailable: ${it.message}")
    }
}

@Preview(showBackground = true)
@Composable
private fun HeyMScreenPreview() {
    WakewordpocTheme {
        HeyMScreen()
    }
}
