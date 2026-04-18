package dev.lok1s.handoffmyvpn.hook

import android.app.Application
import android.content.Intent

/**
 * Sends a log entry broadcast from the target app's process to the module
 * app's LogReceiver. Uses reflection to obtain Application context via
 * ActivityThread.currentApplication() — works from any hook callback.
 */
object LogDispatcher {

    private const val ACTION = "dev.lok1s.handoffmyvpn.ACTION_LOG"
    private const val MODULE_PKG = "dev.lok1s.handoffmyvpn"

    fun notifyLoaded() {
        try {
            val app = currentApplication() ?: return
            val intent = Intent(ACTION).apply {
                setPackage(MODULE_PKG)
                putExtra("pkg",    app.packageName ?: "unknown")
                putExtra("method", "__loaded__")
                putExtra("action", "module active")
                putExtra("detail", "")
                putExtra("ts",     System.currentTimeMillis())
            }
            app.sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    fun dispatch(method: String, action: String, detail: String = "") {
        try {
            val app = currentApplication() ?: return
            val intent = Intent(ACTION).apply {
                setPackage(MODULE_PKG)
                putExtra("pkg",    app.packageName ?: "unknown")
                putExtra("method", method)
                putExtra("action", action)
                putExtra("detail", detail)
                putExtra("ts",     System.currentTimeMillis())
            }
            app.sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    private fun currentApplication(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Application
    } catch (_: Throwable) { null }
}
