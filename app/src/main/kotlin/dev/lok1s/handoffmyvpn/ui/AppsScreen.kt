package dev.lok1s.handoffmyvpn.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.lok1s.handoffmyvpn.R
import dev.lok1s.handoffmyvpn.ui.theme.HOMVTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isInstalled: Boolean
)

@Composable
fun AppsScreen() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadScopedApps(context) }
        isLoading = false
    }

    AppsScreenContent(apps = apps, isLoading = isLoading)
}

@Composable
fun AppsScreenContent(apps: List<AppInfo>, isLoading: Boolean) {
    when {
        isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        apps.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SettingsApplications,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        "No target apps installed",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "${apps.size} app${if (apps.size != 1) "s" else ""} protected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(apps) { app -> AppItemCard(app) }
            }
        }
    }
}

@Composable
private fun AppItemCard(app: AppInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            app.icon?.let { drawable ->
                Image(
                    bitmap = drawable.toBitmap().asImageBitmap(),
                    contentDescription = app.appName,
                    modifier = Modifier.size(48.dp)
                )
            } ?: Icon(
                imageVector = Icons.Rounded.SettingsApplications,
                contentDescription = app.appName,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = "Protected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun loadScopedApps(context: android.content.Context): List<AppInfo> {
    val pm = context.packageManager
    return context.resources.getStringArray(R.array.xposed_scope).mapNotNull { pkg ->
        try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            AppInfo(
                packageName = pkg,
                appName = pm.getApplicationLabel(appInfo).toString(),
                icon = pm.getApplicationIcon(appInfo),
                isInstalled = true
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }.sortedBy { it.appName }
}

@Preview(showBackground = true)
@Composable
private fun AppsScreenPreview() {
    HOMVTheme {
        AppsScreenContent(
            apps = listOf(
                AppInfo("com.google.android.youtube", "YouTube", null, true),
                AppInfo("com.netflix.mediaclient", "Netflix", null, true),
                AppInfo("com.spotify.music", "Spotify", null, true),
            ),
            isLoading = false
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AppsScreenLoadingPreview() {
    HOMVTheme { AppsScreenContent(apps = emptyList(), isLoading = true) }
}

@Preview(showBackground = true)
@Composable
private fun AppsScreenEmptyPreview() {
    HOMVTheme { AppsScreenContent(apps = emptyList(), isLoading = false) }
}
