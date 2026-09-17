package com.colorfuljuice.bluetoothbattery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.colorfuljuice.bluetoothbattery.R
import com.colorfuljuice.bluetoothbattery.utils.UpdateError
import com.colorfuljuice.bluetoothbattery.utils.UpdateManager
import com.colorfuljuice.bluetoothbattery.utils.UpdateStatus

/**
 * 应用内更新的统一对话框，覆盖检查中 / 已最新 / 有新版本 / 下载中 / 待安装 / 失败六种状态。
 */
@Composable
fun UpdateDialog(
    status: UpdateStatus,
    currentVersion: String,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val downloading = status is UpdateStatus.Downloading

    val title = when (status) {
        is UpdateStatus.Checking -> stringResource(R.string.settings_update_checking)
        is UpdateStatus.UpToDate -> stringResource(R.string.update_up_to_date)
        is UpdateStatus.Available -> stringResource(R.string.update_available_title)
        is UpdateStatus.Downloading -> stringResource(R.string.update_downloading_title)
        is UpdateStatus.Ready -> stringResource(R.string.update_ready_title)
        is UpdateStatus.Failed -> stringResource(R.string.update_failed_title)
        UpdateStatus.Idle -> stringResource(R.string.settings_check_update)
    }

    AlertDialog(
        // 下载过程中不允许点击外部/返回键关闭，避免状态丢失
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text(text = title, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (status) {
                    UpdateStatus.Idle,
                    is UpdateStatus.Checking -> CheckingContent()

                    is UpdateStatus.UpToDate -> Text(
                        text = stringResource(R.string.settings_update_latest),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    is UpdateStatus.Available -> AvailableContent(status, currentVersion)

                    is UpdateStatus.Downloading -> DownloadingContent(status.percent)

                    is UpdateStatus.Ready -> ReadyContent(status.version, context)

                    is UpdateStatus.Failed -> Text(
                        text = stringResource(errorTextRes(status.error)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (status) {
                is UpdateStatus.Available -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_download))
                }

                is UpdateStatus.Ready -> TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.update_install))
                }

                is UpdateStatus.Failed -> TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.update_retry))
                }

                is UpdateStatus.Downloading -> TextButton(onClick = onCancelDownload) {
                    Text(stringResource(R.string.update_cancel))
                }

                else -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_close))
                }
            }
        },
        dismissButton = {
            when (status) {
                is UpdateStatus.Available,
                is UpdateStatus.Ready -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_later))
                }

                is UpdateStatus.Failed -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_close))
                }

                else -> Unit
            }
        }
    )
}

@Composable
private fun CheckingContent() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.settings_update_checking),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun AvailableContent(status: UpdateStatus.Available, currentVersion: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        VersionLine(stringResource(R.string.update_current_version), currentVersion)
        VersionLine(stringResource(R.string.update_latest_version), status.version)

        if (status.sizeBytes > 0) {
            VersionLine(stringResource(R.string.update_size), formatSize(status.sizeBytes))
        }

        val notes = status.releaseNotes.trim()
        if (notes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.update_release_notes),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun DownloadingContent(percent: Int) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LinearProgressIndicator(
            progress = percent / 100f,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.update_downloading, percent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReadyContent(version: String, context: android.content.Context) {
    val canInstall = remember { UpdateManager.canRequestPackageInstalls(context) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.update_ready, version),
            style = MaterialTheme.typography.bodyMedium
        )
        if (!canInstall) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.update_install_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun VersionLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun errorTextRes(error: UpdateError): Int = when (error) {
    UpdateError.NETWORK -> R.string.update_failed_network
    UpdateError.NO_ASSET -> R.string.update_failed_no_asset
    UpdateError.DOWNLOAD -> R.string.update_failed_download
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "--"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f MB", mb)
    } else {
        String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
