package dev.lok1s.handoffmyvpn.hook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory log of VPN detection attempts intercepted by hooks.
 *
 * Architecture: hooks run in the TARGET app's process; the module UI runs in
 * the MODULE app's process — separate JVM instances. Entries reach this object
 * via broadcasts from [LogDispatcher] received by [dev.lok1s.handoffmyvpn.LogReceiver].
 */
object DetectionLog {

    private const val MAX_ENTRIES = 500

    data class Entry(
        val timestamp: Long,
        val packageName: String,
        val method: String,
        val action: String,
        val detail: String = ""
    ) {
        val formattedTime: String
            get() = TIME_FORMAT.format(Date(timestamp))
    }

    data class State(
        val entries: List<Entry> = emptyList(),
        val totalCount: Long = 0L
    )

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _state = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _state.asStateFlow()

    val entries: List<Entry>
        get() = _state.value.entries

    val totalCount: Long
        get() = _state.value.totalCount

    fun record(
        packageName: String,
        method: String,
        action: String,
        detail: String = "",
        timestamp: Long = System.currentTimeMillis()
    ) {
        val entry = Entry(timestamp, packageName, method, action, detail)
        synchronized(this) {
            val current = _state.value
            val prev = current.entries
            val newEntries = if (prev.size >= MAX_ENTRIES) {
                listOf(entry) + prev.dropLast(1)
            } else {
                listOf(entry) + prev
            }
            _state.value = State(entries = newEntries, totalCount = current.totalCount + 1)
        }
    }

    fun clear() {
        synchronized(this) { _state.value = State() }
    }
}
