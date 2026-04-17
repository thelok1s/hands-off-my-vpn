package dev.lok1s.handoffmyvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lok1s.handoffmyvpn.BuildConfig
import dev.lok1s.handoffmyvpn.R


@Composable
fun StatusScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Module Status",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_info_details),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

                val isLoaded = com.highcapable.yukihookapi.YukiHookAPI.Status.isModuleActive
                if (isLoaded) {
                    StatusRow("State", "Active", MaterialTheme.colorScheme.primary)
                    StatusRow("Java Hooks", "Loaded", MaterialTheme.colorScheme.secondary)
                    StatusRow("Native Hooks", "Loaded via Dobby", MaterialTheme.colorScheme.secondary)
                } else {
                    StatusRow("State", "Inactive", MaterialTheme.colorScheme.error)
                    Text(
                        text = stringResource(R.string.module_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Build Information",
                    style = MaterialTheme.typography.titleMedium
                )
                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                StatusRow("Version Name", BuildConfig.VERSION_NAME)
                StatusRow("Version Code", BuildConfig.VERSION_CODE.toString())
                StatusRow("Build Type", BuildConfig.BUILD_TYPE)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}
