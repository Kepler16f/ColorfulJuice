package com.colorfuljuice.bluetoothbattery.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.colorfuljuice.bluetoothbattery.BluetoothBatteryViewModel
import com.colorfuljuice.bluetoothbattery.R
import com.colorfuljuice.bluetoothbattery.ui.theme.BatteryHigh
import com.colorfuljuice.bluetoothbattery.ui.theme.ChargingGreen
import com.colorfuljuice.bluetoothbattery.ui.theme.ConnectedGreen
import com.colorfuljuice.bluetoothbattery.utils.BluetoothDeviceWithBattery
import com.colorfuljuice.bluetoothbattery.utils.DeviceType

/**
 * 设备详情页:连接状态、充电状态、主/子电量一览,以及一次手动 GATT 深度读取。
 * 从设备列表卡片点进来,地址经导航参数传入。
 */
@Composable
fun DeviceDetailScreen(
    viewModel: BluetoothBatteryViewModel,
    address: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val devices by viewModel.devices.collectAsState()
    val device = devices.find { it.address == address }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null
                )
            }
            Text(
                text = stringResource(R.string.detail_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (device == null) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.detail_device_missing),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        Spacer(modifier = Modifier.height(8.dp))
        DeviceHeaderCard(device = device)

        Spacer(modifier = Modifier.height(12.dp))
        BatteryCard(device = device)

        Spacer(modifier = Modifier.height(12.dp))
        StateCard(device = device)

        Spacer(modifier = Modifier.height(16.dp))
        DeepReadButton(device = device, onConnect = { viewModel.connectToDevice(device) })
    }
}

@Composable
private fun DeviceHeaderCard(device: BluetoothDeviceWithBattery) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        if (device.isConnected) ConnectedGreen.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                DeviceTypeIcon(
                    deviceType = device.deviceType,
                    modifier = Modifier.size(28.dp),
                    alpha = if (device.isConnected) 1f else 0.4f
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ConnectionStatusText(device = device)
        }
    }
}

@Composable
private fun ConnectionStatusText(device: BluetoothDeviceWithBattery) {
    val (text, color) = when {
        device.isConnecting -> stringResource(R.string.detail_status_connecting) to
                MaterialTheme.colorScheme.onSurfaceVariant
        device.isConnected -> stringResource(R.string.detail_status_connected) to ConnectedGreen
        else -> stringResource(R.string.detail_status_disconnected) to
                MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = color
    )
}

@Composable
private fun BatteryCard(device: BluetoothDeviceWithBattery) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (device.deviceType == DeviceType.HEADPHONE &&
                (device.batteryLeft != null || device.batteryRight != null || device.batteryCase != null)
            ) {
                HeadphoneBatteryIndicator(device = device)
            } else {
                BatteryIndicator(batteryLevel = device.batteryLevel, isCharging = device.isCharging)
            }
        }
    }
}

@Composable
private fun StateCard(device: BluetoothDeviceWithBattery) {
    val chargingText = when {
        !device.chargingKnown -> stringResource(R.string.device_battery_unknown)
        device.isCharging -> stringResource(R.string.charging)
        else -> stringResource(R.string.battery_not_charging)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_charging_state),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (device.isCharging) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = ChargingGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = chargingText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (device.isCharging) BatteryHigh else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DeepReadButton(device: BluetoothDeviceWithBattery, onConnect: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (device.isConnecting) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        } else {
            Button(onClick = onConnect, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.detail_deep_read))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.detail_deep_read_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
