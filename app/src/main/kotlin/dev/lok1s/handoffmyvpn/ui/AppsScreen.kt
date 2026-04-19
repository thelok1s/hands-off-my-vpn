package dev.lok1s.handoffmyvpn.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.lok1s.handoffmyvpn.LogReceiver
import dev.lok1s.handoffmyvpn.R
import dev.lok1s.handoffmyvpn.ui.theme.HOMVTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isInstalled: Boolean,
    val launchIntent: Intent?
)

@Composable
fun AppsScreen() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }

    val loaded = apps
    if (loaded == null) {
        Box(modifier = Modifier.fillMaxSize())
    } else {
        AppsScreenContent(loaded)
    }
}

@Composable
fun AppsScreenContent(apps: List<AppInfo>) {
    val context = LocalContext.current

    if (apps.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SettingsApplications,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    text = stringResource(R.string.no_apps_in_scope),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        val installed = apps.count { it.isInstalled }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = if (apps.size != 1)
                        stringResource(R.string.apps_installed_count, installed, apps.size)
                    else
                        stringResource(R.string.apps_installed_count_singular, installed, apps.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(apps, key = { it.packageName }) { app ->
                AppItemCard(app) {
                    app.launchIntent?.let { context.startActivity(it) }
                }
            }
        }
    }
}

@Composable
private fun AppItemCard(app: AppInfo, onOpen: () -> Unit) {
    Card(
        onClick = if (app.launchIntent != null) onOpen else { {} },
        modifier = Modifier
            .fillMaxWidth()
            .alpha(1f),
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

            if (app.launchIntent != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = "Open ${app.appName}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Merges the static recommended scope (arrays.xml) with packages dynamically
 * discovered via the hook broadcast. The static scope gives immediate results on
 * first launch; the dynamic set captures any extra apps the user enabled beyond
 * the recommendation.
 */
private fun loadApps(context: Context): List<AppInfo> {
    val pm = context.packageManager

    val scopePackages = context.resources.getStringArray(R.array.xposed_scope).toSet()
    val dynamicPackages = context.getSharedPreferences(
        LogReceiver.PREFS_ENABLED_APPS, Context.MODE_PRIVATE
    ).all.keys

    return (scopePackages + dynamicPackages).mapNotNull { pkg ->
        try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            AppInfo(
                packageName = pkg,
                appName = pm.getApplicationLabel(appInfo).toString(),
                icon = pm.getApplicationIcon(appInfo),
                isInstalled = true,
                launchIntent = pm.getLaunchIntentForPackage(pkg)
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }.sortedWith(compareByDescending<AppInfo> { it.appName })
}

@Preview(showBackground = true)
@Composable
private fun AppsScreenPreview() {
    HOMVTheme {
        AppsScreenContent(
            apps = listOf(
                AppInfo("com.google.android.youtube", "YouTube", null, true,
                    Intent("android.intent.action.MAIN")),
                AppInfo("com.netflix.mediaclient", "Netflix", null, true, null),
                AppInfo("com.spotify.music", "Spotify", null, false, null),
            )
        )
    }
}
