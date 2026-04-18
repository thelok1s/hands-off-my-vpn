package dev.lok1s.handoffmyvpn.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.highcapable.yukihookapi.YukiHookAPI
import dev.lok1s.handoffmyvpn.BuildConfig
import dev.lok1s.handoffmyvpn.R
import dev.lok1s.handoffmyvpn.ui.theme.HOMVTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val GITHUB_URL = "https://github.com/lok1s/hands-off-my-vpn"
private const val RELEASES_API = "https://api.github.com/repos/lok1s/hands-off-my-vpn/releases/latest"

data class UpdateInfo(val tagName: String, val htmlUrl: String)


@Composable
private fun UpdateCard(update: UpdateInfo) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, update.htmlUrl.toUri())) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(24.dp),
                )
                Column {
                    Text(
                        text = "Update available",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = update.tagName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun StatusScreen() {
    val isActive = YukiHookAPI.Status.isModuleActive

    val executorName = if (isActive) YukiHookAPI.Status.Executor.name else ""
    val executorVersion = if (isActive) resolveExecutorVersion() else ""

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) { updateInfo = checkForUpdates() }

    StatusScreenContent(
        isModuleActive = isActive,
        executorName = executorName,
        executorVersion = executorVersion,
        updateInfo = updateInfo,
    )
}

private fun resolveExecutorVersion(): String {
    val name = YukiHookAPI.Status.Executor.versionName
    if (name.isNotBlank() && name != "unsupported" && name != "unknown") return name
    val api = YukiHookAPI.Status.Executor.apiLevel
    if (api > 0) return "API $api"
    val code = YukiHookAPI.Status.Executor.versionCode
    if (code > 0) return "build $code"
    return ""
}


private suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 6_000
        conn.readTimeout = 6_000
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val tag = body.substringAfter("\"tag_name\":\"", "").substringBefore("\"")
        val url = body.substringAfter("\"html_url\":\"", "").substringBefore("\"")
        if (tag.isBlank()) return@withContext null

        val latest = tag.trimStart('v')
        if (isNewer(latest, BuildConfig.VERSION_NAME)) UpdateInfo(tag, url) else null
    } catch (_: Throwable) { null }
}

private fun isNewer(latest: String, current: String): Boolean {
    val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
    val c = current.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(l.size, c.size)) {
        val lv = l.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (lv > cv) return true
        if (lv < cv) return false
    }
    return false
}

@Composable
fun StatusScreenContent(
    isModuleActive: Boolean,
    executorName: String = "",
    executorVersion: String = "",
    updateInfo: UpdateInfo? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusHeroCard(isModuleActive)
        if (isModuleActive) FrameworkInfoCard(executorName, executorVersion)
        BuildInfoCard()
        GitHubCard()
        if (updateInfo != null) UpdateCard(updateInfo)
    }
}


@Composable
private fun StatusHeroCard(isModuleActive: Boolean) {
    val containerColor = if (isModuleActive)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.errorContainer

    val contentColor = if (isModuleActive)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer

    val icon = if (isModuleActive) Icons.Rounded.VerifiedUser else Icons.Rounded.GppBad
    val headline = if (isModuleActive) "Module Active" else "Module Inactive"
    val subtext = if (isModuleActive)
        stringResource(R.string.module_active_desc)
    else
        stringResource(R.string.module_disabled)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = contentColor,
            )
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            Text(
                text = subtext,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun FrameworkInfoCard(executorName: String, executorVersion: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "LSPosed Framework",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            InfoRow("Name", executorName.ifBlank { "Unknown" },
                MaterialTheme.colorScheme.onSecondaryContainer)
            if (executorVersion.isNotBlank()) {
                InfoRow("Version", executorVersion,
                    MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun BuildInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "App info",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("Type", BuildConfig.BUILD_TYPE)
        }
    }
}

@Composable
private fun GitHubCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri())) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "View on GitHub",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}



@Preview(showBackground = true)
@Composable
private fun StatusActivePreview() {
    HOMVTheme {
        StatusScreenContent(
            isModuleActive = true,
            executorName = "LSPosed",
            executorVersion = "API 93",
            updateInfo = UpdateInfo("v2.4.0", ""),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusInactivePreview() {
    HOMVTheme { StatusScreenContent(isModuleActive = false) }
}
