package com.colorfuljuice.bluetoothbattery.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.colorfuljuice.bluetoothbattery.BluetoothBatteryViewModel
import com.colorfuljuice.bluetoothbattery.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun SettingsScreen(viewModel: BluetoothBatteryViewModel, modifier: Modifier = Modifier) {
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val connectionTimeout by viewModel.connectionTimeout.collectAsState()
    val batteryRefreshInterval by viewModel.batteryRefreshInterval.collectAsState()
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showRefreshIntervalDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.common),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            SettingsItem(
                icon = Icons.Default.Language,
                title = stringResource(R.string.settings_language),
                subtitle = when (currentLanguage) {
                    "zh" -> stringResource(R.string.language_simplified_chinese)
                    "zh-rTW" -> stringResource(R.string.language_traditional_chinese)
                    "en" -> stringResource(R.string.language_english)
                    else -> "System"
                },
                onClick = { showLanguageDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.settings_connection),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                SettingsItem(
                    icon = Icons.Default.Bluetooth,
                    title = stringResource(R.string.settings_connection_timeout),
                    subtitle = "${connectionTimeout}s",
                    onClick = { showTimeoutDialog = true }
                )
                Divider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Default.Update,
                    title = stringResource(R.string.settings_battery_refresh_interval),
                    subtitle = formatRefreshInterval(batteryRefreshInterval, context),
                    onClick = { showRefreshIntervalDialog = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.about),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                SettingsItem(
                    icon = Icons.Default.SystemUpdate,
                    title = stringResource(R.string.settings_version),
                    subtitle = try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.1beta"
                    } catch (e: Exception) {
                        "0.0.1beta"
                    },
                    onClick = {
                        scope.launch {
                            checkForUpdates(context)
                        }
                    }
                )
                Divider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = stringResource(R.string.settings_source_code),
                    subtitle = stringResource(R.string.settings_source_code_desc),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Kepler16f/ColorfulJuice"))
                        context.startActivity(intent)
                    }
                )
            }
        }
    }

    if (showLanguageDialog) {
        LanguageDialog(
            currentLanguage = currentLanguage,
            onDismiss = { showLanguageDialog = false },
            onLanguageSelected = { language ->
                viewModel.setLanguage(language)
                showLanguageDialog = false
            }
        )
    }

    if (showTimeoutDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_connection_timeout),
            options = listOf(
                Triple(3, "3s", "3s"),
                Triple(5, "5s", "5s"),
                Triple(8, "8s", "8s"),
                Triple(12, "12s", "12s"),
                Triple(20, "20s", "20s"),
                Triple(30, "30s", "30s")
            ),
            selectedValue = connectionTimeout,
            onDismiss = { showTimeoutDialog = false },
            onSelected = { value ->
                viewModel.setConnectionTimeout(value)
                showTimeoutDialog = false
            }
        )
    }

    if (showRefreshIntervalDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.settings_battery_refresh_interval),
            options = listOf(
                Triple(0, context.getString(R.string.refresh_off), "off"),
                Triple(30, context.getString(R.string.refresh_30s), "30s"),
                Triple(60, context.getString(R.string.refresh_1min), "1m"),
                Triple(120, context.getString(R.string.refresh_2min), "2m"),
                Triple(300, context.getString(R.string.refresh_5min), "5m"),
                Triple(600, context.getString(R.string.refresh_10min), "10m")
            ),
            selectedValue = batteryRefreshInterval,
            onDismiss = { showRefreshIntervalDialog = false },
            onSelected = { value ->
                viewModel.setBatteryRefreshInterval(value)
                showRefreshIntervalDialog = false
            }
        )
    }
}

private fun formatRefreshInterval(seconds: Int, context: android.content.Context): String {
    return when (seconds) {
        0 -> context.getString(R.string.refresh_off)
        30 -> context.getString(R.string.refresh_30s)
        60 -> context.getString(R.string.refresh_1min)
        120 -> context.getString(R.string.refresh_2min)
        300 -> context.getString(R.string.refresh_5min)
        600 -> context.getString(R.string.refresh_10min)
        else -> "${seconds}s"
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun <T> SingleChoiceDialog(
    title: String,
    options: List<Triple<T, String, String>>,
    selectedValue: T,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label, _) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(value) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (value == selectedValue) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
fun LanguageDialog(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    val languages = listOf(
        Triple("system", "System", "System"),
        Triple("zh", stringResource(R.string.language_simplified_chinese), "简体中文"),
        Triple("zh-rTW", stringResource(R.string.language_traditional_chinese), "繁體中文"),
        Triple("en", stringResource(R.string.language_english), "English")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                languages.forEach { (code, name, _) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(code) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (currentLanguage == code) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

private suspend fun checkForUpdates(context: android.content.Context) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/Kepler16f/ColorfulJuice/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val tagMatch = Regex("\"tag_name\":\"([^\"]+)\"").find(response)
                val latestVersion = tagMatch?.groupValues?.get(1) ?: ""
                val currentVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                } catch (e: Exception) { "" }

                withContext(Dispatchers.Main) {
                    val message = if (latestVersion.isNotEmpty() && latestVersion != currentVersion) {
                        context.getString(R.string.settings_update_available, latestVersion)
                    } else {
                        context.getString(R.string.settings_update_latest)
                    }
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_update_error), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, context.getString(R.string.settings_update_error), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
