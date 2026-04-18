package dev.lok1s.handoffmyvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lok1s.handoffmyvpn.hook.DetectionLog

class LogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg    = intent.getStringExtra("pkg")    ?: return
        val method = intent.getStringExtra("method") ?: return
        val action = intent.getStringExtra("action") ?: return
        val detail = intent.getStringExtra("detail") ?: ""
        val ts     = intent.getLongExtra("ts", System.currentTimeMillis())

        context.getSharedPreferences(PREFS_ENABLED_APPS, Context.MODE_PRIVATE)
            .edit().putLong(pkg, ts).apply()

        if (method == "__loaded__") return
        DetectionLog.record(pkg, method, action, detail, ts)
    }

    companion object {
        const val PREFS_ENABLED_APPS = "enabled_apps"
    }
}
