package dev.lok1s.handoffmyvpn.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.lok1s.handoffmyvpn.R
import dev.lok1s.handoffmyvpn.hook.DetectionLog
import dev.lok1s.handoffmyvpn.ui.theme.HOMVTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
@Composable
fun LogScreen() {
    val state by DetectionLog.stateFlow.collectAsState()
    LogScreenContent(
        entries = state.entries,
        onClear = { DetectionLog.clear() }
    )
}

@Composable
private fun LogScreenContent(
    entries: List<DetectionLog.Entry>,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Assessment,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    text = stringResource(R.string.no_events_intercepted),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (entries.size != 1)
                            stringResource(R.string.events_intercepted_plural, entries.size)
                        else
                            stringResource(R.string.events_intercepted_singular, entries.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row {
                        val exportSubject = stringResource(R.string.export_log_subject)
                        val exportChooser = stringResource(R.string.export_log_chooser)

                        val logHeader = stringResource(R.string.export_log_header)
                        val header = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                        val logExported = stringResource(R.string.export_log_exported, header)
                        val logTotal = stringResource(R.string.export_log_total, entries.size)

                        IconButton(
                            onClick = {
                                val text = formatExport(logHeader, logExported, logTotal, entries)
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, text)
                                            putExtra(Intent.EXTRA_SUBJECT, exportSubject)
                                        },
                                        exportChooser
                                    )
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.export_log),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = onClear) {
                            Text(stringResource(R.string.clear), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            items(entries, key = { "${it.timestamp}-${it.method}" }) { entry ->
                LogItemCard(entry)
            }
        }
    }
}

@Composable
private fun LogItemCard(entry: DetectionLog.Entry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = entry.formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = "${entry.method}  →  ${entry.action}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (entry.detail.isNotBlank()) {
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatExport(
    logHeader: String,
    logExported: String,
    logTotal: String,
    entries: List<DetectionLog.Entry>
): String {
    return buildString {
        appendLine(logHeader)
        appendLine(logExported)
        appendLine(logTotal)
        appendLine()
        entries.forEach { e ->
            append("[${e.formattedTime}] ${e.packageName}")
            appendLine()
            append("  ${e.method} → ${e.action}")
            if (e.detail.isNotBlank()) append("  (${e.detail})")
            appendLine()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LogScreenPreview() {
    HOMVTheme {
        LogScreenContent(
            entries = listOf(
                DetectionLog.Entry(
                    timestamp = System.currentTimeMillis(),
                    packageName = "com.example.app",
                    method = "getNetworkInfo",
                    action = "Spoofed DISCONNECTED",
                    detail = "NetworkType: WIFI"
                ),
                DetectionLog.Entry(
                    timestamp = System.currentTimeMillis() - 10000,
                    packageName = "com.another.vpn",
                    method = "isVpnConnected",
                    action = "Returned FALSE"
                )
            ),
            onClear = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LogScreenEmptyPreview() {
    HOMVTheme {
        LogScreenContent(
            entries = emptyList(),
            onClear = {}
        )
    }
}
