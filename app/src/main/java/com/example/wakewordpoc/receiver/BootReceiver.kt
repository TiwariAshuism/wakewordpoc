package com.example.wakewordpoc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.wakewordpoc.WakeWordConfig
import com.example.wakewordpoc.service.WakeWordService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!WakeWordConfig.autoStart(context)) return

        val action = intent.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WakeWordConfig.setError(
                    context,
                    "Boot received. Open Hey M once to arm microphone listener on Android 14+.",
                )
                BootStartNotifier.show(context)
                return
            }

            val serviceIntent = Intent(context, WakeWordService::class.java)
                .setAction(WakeWordService.ACTION_START)
            runCatching {
                ContextCompat.startForegroundService(context, serviceIntent)
            }.onFailure {
                WakeWordConfig.setError(context, "Boot start failed: ${it.message}")
                BootStartNotifier.show(context)
            }
        }
    }
}
