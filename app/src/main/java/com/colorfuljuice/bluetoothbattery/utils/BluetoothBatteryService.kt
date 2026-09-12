package com.colorfuljuice.bluetoothbattery.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class BluetoothDeviceWithBattery(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val batteryLevel: Int? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null
)

class BluetoothBatteryService(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothBatteryService"
        private val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _devices = MutableStateFlow<List<BluetoothDeviceWithBattery>>(emptyList())
    val devices: StateFlow<List<BluetoothDeviceWithBattery>> = _devices.asStateFlow()

    private val gattConnections = mutableMapOf<String, BluetoothGatt>()
    private val connectionJobs = mutableMapOf<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connectionTimeoutMs = 12000L
    private var batteryRefreshIntervalSec = 0
    private var batteryRefreshJob: Job? = null

    private var headsetProfile: BluetoothHeadset? = null
    private var a2dpProfile: BluetoothA2dp? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.HEADSET -> {
                    headsetProfile = proxy as BluetoothHeadset
                    refreshConnectedState()
                }
                BluetoothProfile.A2DP -> {
                    a2dpProfile = proxy as BluetoothA2dp
                    refreshConnectedState()
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.HEADSET -> headsetProfile = null
                BluetoothProfile.A2DP -> a2dpProfile = null
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            @Suppress("DEPRECATION")
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            when (action) {
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val batteryLevel = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (device != null && batteryLevel in 0..100) {
                        Log.d(TAG, "Broadcast battery level for ${device.address}: $batteryLevel%")
                        updateDeviceByAddress(device.address) {
                            it.copy(
                                batteryLevel = batteryLevel,
                                isConnected = true,
                                isConnecting = false,
                                error = null
                            )
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (device != null) {
                        Log.d(TAG, "Device connected ACL: ${device.address}")
                        val sysBattery = getBatteryLevelFromSystem(device)
                        updateDeviceByAddress(device.address) {
                            it.copy(
                                isConnected = true,
                                isConnecting = false,
                                batteryLevel = sysBattery ?: it.batteryLevel,
                                error = null
                            )
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (device != null) {
                        Log.d(TAG, "Device disconnected ACL: ${device.address}")
                        updateDeviceByAddress(device.address) {
                            it.copy(
                                isConnected = false,
                                isConnecting = false
                            )
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    loadPairedDevices()
                }
            }
        }
    }

    init {
        registerReceivers()
        initProfileProxies()
    }

    fun setConnectionTimeout(seconds: Int) {
        connectionTimeoutMs = seconds * 1000L
        Log.d(TAG, "Connection timeout set to ${seconds}s")
    }

    fun setBatteryRefreshInterval(seconds: Int) {
        batteryRefreshIntervalSec = seconds
        batteryRefreshJob?.cancel()
        if (seconds > 0) {
            startBatteryRefreshTimer()
            Log.d(TAG, "Battery auto-refresh interval set to ${seconds}s")
        } else {
            Log.d(TAG, "Battery auto-refresh disabled")
        }
    }

    private fun startBatteryRefreshTimer() {
        batteryRefreshJob?.cancel()
        batteryRefreshJob = scope.launch {
            while (true) {
                delay(batteryRefreshIntervalSec * 1000L)
                refreshAllConnectedBatteries()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshAllConnectedBatteries() {
        val connectedDevices = _devices.value.filter { it.isConnected }
        if (connectedDevices.isEmpty()) return
        Log.d(TAG, "Auto-refreshing battery for ${connectedDevices.size} connected devices")
        connectedDevices.forEach { item ->
            val sysBattery = getBatteryLevelFromSystem(item.device)
            if (sysBattery != null) {
                updateDeviceByAddress(item.address) {
                    it.copy(batteryLevel = sysBattery)
                }
            }
        }
    }

    private fun registerReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_BATTERY_LEVEL_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(bluetoothReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register broadcast receiver", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun initProfileProxies() {
        try {
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
            bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get profile proxies", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun loadPairedDevices() {
        val pairedDevices = getBondedDevices()
        val deviceList = pairedDevices.map { device ->
            val isConnected = isDeviceCurrentlyConnected(device)
            val batteryLevel = getBatteryLevelFromSystem(device)
            val name = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    device.alias ?: device.name ?: "Unknown Device"
                } else {
                    device.name ?: "Unknown Device"
                }
            } catch (e: Exception) {
                device.name ?: "Unknown Device"
            }

            BluetoothDeviceWithBattery(
                device = device,
                name = name,
                address = device.address,
                batteryLevel = batteryLevel,
                isConnected = isConnected
            )
        }
        _devices.value = deviceList
    }

    @SuppressLint("MissingPermission")
    private fun refreshConnectedState() {
        val currentList = _devices.value.map { item ->
            val connected = isDeviceCurrentlyConnected(item.device)
            val battery = if (item.batteryLevel == null) getBatteryLevelFromSystem(item.device) else item.batteryLevel
            item.copy(
                isConnected = connected,
                batteryLevel = battery
            )
        }
        _devices.value = currentList
    }

    @SuppressLint("MissingPermission")
    fun isDeviceCurrentlyConnected(device: BluetoothDevice): Boolean {
        try {
            val isConnectedMethod = device.javaClass.getMethod("isConnected")
            val connected = isConnectedMethod.invoke(device) as? Boolean
            if (connected == true) return true
        } catch (_: Exception) {}

        try {
            if (headsetProfile?.getConnectedDevices()?.any { it.address == device.address } == true) {
                return true
            }
            if (a2dpProfile?.getConnectedDevices()?.any { it.address == device.address } == true) {
                return true
            }
        } catch (_: Exception) {}

        try {
            if (bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED) {
                return true
            }
            if (bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    @SuppressLint("MissingPermission")
    fun getBatteryLevelFromSystem(device: BluetoothDevice): Int? {
        try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val level = method.invoke(device) as? Int
            if (level != null && level in 0..100) {
                return level
            }
        } catch (_: Exception) {}
        return null
    }

    @SuppressLint("MissingPermission")
    fun connectAndReadBattery(deviceWithBattery: BluetoothDeviceWithBattery) {
        val device = deviceWithBattery.device
        val address = deviceWithBattery.address

        val sysBattery = getBatteryLevelFromSystem(device)
        val sysConnected = isDeviceCurrentlyConnected(device)

        if (sysBattery != null) {
            Log.d(TAG, "Obtained battery from system reflection for $address: $sysBattery%")
            updateDeviceByAddress(address) {
                it.copy(
                    batteryLevel = sysBattery,
                    isConnected = true,
                    isConnecting = false,
                    error = null
                )
            }
        }

        updateDeviceByAddress(address) {
            it.copy(
                isConnecting = true,
                error = null,
                batteryLevel = sysBattery ?: it.batteryLevel,
                isConnected = sysConnected || it.isConnected
            )
        }

        connectionJobs[address]?.cancel()
        gattConnections[address]?.disconnect()
        gattConnections[address]?.close()
        gattConnections.remove(address)

        connectionJobs[address] = scope.launch {
            delay(connectionTimeoutMs)
            val current = _devices.value.find { it.address == address }
            if (current != null && current.isConnecting) {
                Log.w(TAG, "Connection timed out for $address")
                disconnectDevice(address)
                updateDeviceByAddress(address) {
                    it.copy(
                        isConnecting = false,
                        error = if (it.batteryLevel != null) null else "Connection timed out (连接超时)"
                    )
                }
            }
        }

        try {
            val callback = object : BluetoothGattCallback() {

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "onConnectionStateChange for $address, status=$status, newState=$newState")

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "GATT connection failed with status $status for $address")
                        connectionJobs[address]?.cancel()
                        gatt.close()
                        gattConnections.remove(address)

                        val hasBattery = _devices.value.find { it.address == address }?.batteryLevel != null
                        val isStillConnected = isDeviceCurrentlyConnected(device)
                        updateDeviceByAddress(address) {
                            it.copy(
                                isConnecting = false,
                                isConnected = isStillConnected,
                                error = if (hasBattery) null else "GATT error: $status"
                            )
                        }
                        return
                    }

                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "GATT Connected to $address")
                            updateDeviceByAddress(address) {
                                it.copy(
                                    isConnecting = false,
                                    isConnected = true,
                                    error = null
                                )
                            }
                            mainHandler.postDelayed({
                                try {
                                    gatt.discoverServices()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error discovering services for $address", e)
                                }
                            }, 300)
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "GATT Disconnected from $address")
                            connectionJobs[address]?.cancel()
                            gatt.close()
                            gattConnections.remove(address)
                            val isStillConnected = isDeviceCurrentlyConnected(device)
                            updateDeviceByAddress(address) {
                                it.copy(
                                    isConnecting = false,
                                    isConnected = isStillConnected
                                )
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    Log.d(TAG, "onServicesDiscovered status=$status for $address")
                    connectionJobs[address]?.cancel()

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
                        if (batteryService != null) {
                            val batteryCharacteristic = batteryService.getCharacteristic(BATTERY_LEVEL_UUID)
                            if (batteryCharacteristic != null) {
                                gatt.setCharacteristicNotification(batteryCharacteristic, true)
                                val descriptor = batteryCharacteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
                                if (descriptor != null) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                        @Suppress("DEPRECATION")
                                        gatt.writeDescriptor(descriptor)
                                    }
                                }
                                gatt.readCharacteristic(batteryCharacteristic)
                            } else {
                                handleMissingBatteryService(address, "Battery Characteristic not found")
                            }
                        } else {
                            handleMissingBatteryService(address, "Battery Service (0x180F) not found")
                        }
                    } else {
                        handleMissingBatteryService(address, "Failed to discover services (status $status)")
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    handleCharacteristicBattery(characteristic.uuid, value, status, address)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    handleCharacteristicBattery(characteristic.uuid, characteristic.value, status, address)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    handleCharacteristicBattery(characteristic.uuid, value, BluetoothGatt.GATT_SUCCESS, address)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    handleCharacteristicBattery(characteristic.uuid, characteristic.value, BluetoothGatt.GATT_SUCCESS, address)
                }
            }

            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_AUTO)
            } else {
                device.connectGatt(context, false, callback)
            }

            if (gatt != null) {
                gattConnections[address] = gatt
            } else {
                throw IllegalStateException("connectGatt returned null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to $address", e)
            connectionJobs[address]?.cancel()
            val hasBattery = _devices.value.find { it.address == address }?.batteryLevel != null
            updateDeviceByAddress(address) {
                it.copy(
                    isConnecting = false,
                    error = if (hasBattery) null else "Connection failed: ${e.message}"
                )
            }
        }
    }

    private fun handleCharacteristicBattery(uuid: UUID, value: ByteArray?, status: Int, address: String) {
        if (status == BluetoothGatt.GATT_SUCCESS && uuid == BATTERY_LEVEL_UUID && value != null && value.isNotEmpty()) {
            val batteryLevel = value[0].toInt() and 0xFF
            Log.d(TAG, "Read GATT battery level for $address: $batteryLevel%")
            updateDeviceByAddress(address) {
                it.copy(
                    batteryLevel = batteryLevel,
                    isConnected = true,
                    isConnecting = false,
                    error = null
                )
            }
        }
    }

    private fun handleMissingBatteryService(address: String, reason: String) {
        val device = _devices.value.find { it.address == address }?.device
        val sysBattery = if (device != null) getBatteryLevelFromSystem(device) else null
        updateDeviceByAddress(address) {
            it.copy(
                isConnecting = false,
                isConnected = true,
                batteryLevel = sysBattery ?: it.batteryLevel,
                error = if (sysBattery != null || it.batteryLevel != null) null else reason
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice(address: String) {
        connectionJobs[address]?.cancel()
        connectionJobs.remove(address)
        try {
            gattConnections[address]?.disconnect()
            gattConnections[address]?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing gatt for $address", e)
        }
        gattConnections.remove(address)

        updateDeviceByAddress(address) {
            it.copy(isConnected = false, isConnecting = false)
        }
    }

    fun disconnectAll() {
        connectionJobs.values.forEach { it.cancel() }
        connectionJobs.clear()
        gattConnections.values.forEach {
            try {
                it.disconnect()
                it.close()
            } catch (_: Exception) {}
        }
        gattConnections.clear()
        _devices.value = _devices.value.map {
            it.copy(isConnected = false, isConnecting = false)
        }
    }

    fun updateDeviceByAddress(address: String, transform: (BluetoothDeviceWithBattery) -> BluetoothDeviceWithBattery) {
        val currentDevices = _devices.value.toMutableList()
        val index = currentDevices.indexOfFirst { it.address == address }
        if (index != -1) {
            currentDevices[index] = transform(currentDevices[index])
            _devices.value = currentDevices
        }
    }

    fun destroy() {
        batteryRefreshJob?.cancel()
        disconnectAll()
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
        try {
            if (headsetProfile != null) {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, headsetProfile)
            }
            if (a2dpProfile != null) {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile)
            }
        } catch (_: Exception) {}
    }
}
