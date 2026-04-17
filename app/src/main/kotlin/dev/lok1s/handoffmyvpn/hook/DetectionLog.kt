package dev.lok1s.handoffmyvpn.hook

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * # DetectionLog
 *
 * In-memory ring buffer that records VPN detection attempts intercepted by hooks.
 * Each hook calls [record] when it spoofs a result. The module UI reads [entries]
 * to display the detection log.
 *
 * Thread-safe via synchronized access to the backing list.
 *
 * Note: This log lives in-process. In an Xposed context each hooked app has its
 * own copy. For cross-process visibility, a SharedPreferences bridge is needed
 * (future enhancement).
 */
object DetectionLog {

    /** Maximum entries kept in memory. */
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

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _entries = ArrayDeque<Entry>(MAX_ENTRIES)

    /** Snapshot of current log entries (newest first). */
    val entries: List<Entry>
        get() = synchronized(_entries) { _entries.toList().asReversed() }

    /** Total number of events recorded since process start. */
    @Volatile
    var totalCount: Long = 0L
        private set

    /**
     * Record a detection event.
     *
     * @param packageName The target app package name.
     * @param method The API method that was hooked (e.g., "hasTransport(TRANSPORT_VPN)").
     * @param action What the hook did (e.g., "→ false (spoofed)").
     * @param detail Optional extra detail.
     */
    fun record(packageName: String, method: String, action: String, detail: String = "") {
        val entry = Entry(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            method = method,
            action = action,
            detail = detail
        )
        synchronized(_entries) {
            if (_entries.size >= MAX_ENTRIES) {
                _entries.removeFirst()
            }
            _entries.addLast(entry)
            totalCount++
        }
    }

    /** Clear all entries. */
    fun clear() {
        synchronized(_entries) {
            _entries.clear()
            totalCount = 0
        }
    }
}
