package com.colorfuljuice.bluetoothbattery.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
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

enum class DeviceType {
    HEADPHONE, PEN, KEYBOARD, MOUSE, OTHER
}

data class BluetoothDeviceWithBattery(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val batteryLevel: Int? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
    val deviceType: DeviceType = DeviceType.OTHER,
    val batteryLeft: Int? = null,
    val batteryRight: Int? = null,
    val batteryCase: Int? = null
)

class BluetoothBatteryService(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothBatteryService"
        private val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Headphone-specific battery UUIDs (left/right/case)
        private val HEADSET_SERVICE_UUID: UUID = UUID.fromString("00001804-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_STATE_UUID: UUID = UUID.fromString("00002a1a-0000-1000-8000-00805f9b34fb")

        // Vendor-specific services for TWS earbuds
        private val APPLE_HEADPHONE_UUID: UUID = UUID.fromString("7dfc7d04-0b06-11e6-b3f4-0002a5d5c51b")
        private val LEFT_EAR_UUID: UUID = UUID.fromString("7dfc7d07-0b06-11e6-b3f4-0002a5d5c51b")
        private val RIGHT_EAR_UUID: UUID = UUID.fromString("7dfc7d08-0b06-11e6-b3f4-0002a5d5c51b")
        private val CASE_BATTERY_UUID: UUID = UUID.fromString("7dfc7d09-0b06-11e6-b3f4-0002a5d5c51b")

        // 0x1804 Headset service for some devices
        private val HEADSET_BATTERY_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

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

    @SuppressLint("MissingPermission")
    private fun detectDeviceType(device: BluetoothDevice): DeviceType {
        val nameLower = try { device.name?.lowercase() ?: "" } catch (_: Exception) { "" }

        if (nameLower.contains("pen") || nameLower.contains("stylus") || nameLower.contains("pencil")) {
            return DeviceType.PEN
        }
        if (nameLower.contains("mouse") || nameLower.contains("鼠标")) {
            return DeviceType.MOUSE
        }
        if (nameLower.contains("keyboard") || nameLower.contains("键盘") ||
            nameLower.contains("keychron") || nameLower.contains("logitech k")) {
            return DeviceType.KEYBOARD
        }

        try {
            val clazz = device.bluetoothClass ?: return DeviceType.OTHER
            when (clazz.majorDeviceClass) {
                BluetoothClass.Device.Major.AUDIO_VIDEO -> return DeviceType.HEADPHONE
                BluetoothClass.Device.Major.COMPUTER -> {
                    val deviceClass = clazz.deviceClass
                    return when (deviceClass) {
                        BluetoothClass.Device.PERIPHERAL_KEYBOARD,
                        BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING -> DeviceType.KEYBOARD
                        BluetoothClass.Device.PERIPHERAL_POINTING,
                        BluetoothClass.Device.PERIPHERAL_NON_KEYBOARD_NON_POINTING -> DeviceType.MOUSE
                        else -> DeviceType.KEYBOARD
                    }
                }
                BluetoothClass.Device.Major.PERIPHERAL -> {
                    val deviceClass = clazz.deviceClass
                    return when {
                        deviceClass == BluetoothClass.Device.PERIPHERAL_KEYBOARD ||
                        deviceClass == BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING -> DeviceType.KEYBOARD
                        deviceClass == BluetoothClass.Device.PERIPHERAL_POINTING -> DeviceType.MOUSE
                        else -> DeviceType.OTHER
                    }
                }
            }
        } catch (_: Exception) {}

        return DeviceType.OTHER
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
            val type = detectDeviceType(device)

            BluetoothDeviceWithBattery(
                device = device,
                name = name,
                address = device.address,
                batteryLevel = batteryLevel,
                isConnected = isConnected,
                deviceType = type
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
                        // Try standard battery service first
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

                        // Try reading headphone-specific left/right/case battery
                        readHeadphoneSubBattery(gatt, address)
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
                // For headphone-type devices, try reading sub-battery after a delay
                val devType = _devices.value.find { it.address == address }?.deviceType
                if (devType == DeviceType.HEADPHONE) {
                    mainHandler.postDelayed({
                        readHeadphoneSubBatteryDirect(device, address)
                    }, 2000)
                }
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
    private fun readHeadphoneSubBattery(gatt: BluetoothGatt, address: String) {
        // Try Apple-style left/right/case battery
        val appleService = gatt.getService(APPLE_HEADPHONE_UUID)
        if (appleService != null) {
            Log.d(TAG, "Found Apple headphone service for $address")
            listOf(LEFT_EAR_UUID to "left", RIGHT_EAR_UUID to "right", CASE_BATTERY_UUID to "case").forEach { (uuid, label) ->
                val ch = appleService.getCharacteristic(uuid)
                if (ch != null) {
                    gatt.setCharacteristicNotification(ch, true)
                    val desc = ch.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
                    if (desc != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(desc)
                        }
                    }
                    gatt.readCharacteristic(ch)
                    Log.d(TAG, "Reading $label battery from Apple service for $address")
                }
            }
        }

        // Try 0x1804 headset service
        val headsetService = gatt.getService(HEADSET_SERVICE_UUID)
        if (headsetService != null) {
            val batteryCh = headsetService.getCharacteristic(HEADSET_BATTERY_UUID)
            if (batteryCh != null) {
                gatt.setCharacteristicNotification(batteryCh, true)
                val desc = batteryCh.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
                if (desc != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(desc)
                    }
                }
                gatt.readCharacteristic(batteryCh)
                Log.d(TAG, "Reading battery from headset service for $address")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readHeadphoneSubBatteryDirect(device: BluetoothDevice, address: String) {
        // Try direct GATT connection for headphone sub-batteries
        try {
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        mainHandler.postDelayed({
                            try { gatt.discoverServices() } catch (_: Exception) {}
                        }, 300)
                    } else {
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        readHeadphoneSubBattery(gatt, address)
                    } else {
                        gatt.close()
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    handleSubBatteryCharacteristic(characteristic.uuid, value, status, address)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    handleSubBatteryCharacteristic(characteristic.uuid, characteristic.value, status, address)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    handleSubBatteryCharacteristic(characteristic.uuid, value, BluetoothGatt.GATT_SUCCESS, address)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    handleSubBatteryCharacteristic(characteristic.uuid, characteristic.value, BluetoothGatt.GATT_SUCCESS, address)
                }
            }

            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_AUTO)
            } else {
                device.connectGatt(context, false, callback)
            }

            // Close after timeout
            mainHandler.postDelayed({
                try { gatt?.close() } catch (_: Exception) {}
            }, connectionTimeoutMs + 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading sub-battery for $address", e)
        }
    }

    private fun handleSubBatteryCharacteristic(uuid: UUID, value: ByteArray?, status: Int, address: String) {
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.isEmpty()) return
        val level = value[0].toInt() and 0xFF
        if (level !in 0..100) return

        when (uuid) {
            LEFT_EAR_UUID -> {
                Log.d(TAG, "Left ear battery for $address: $level%")
                updateDeviceByAddress(address) { it.copy(batteryLeft = level) }
            }
            RIGHT_EAR_UUID -> {
                Log.d(TAG, "Right ear battery for $address: $level%")
                updateDeviceByAddress(address) { it.copy(batteryRight = level) }
            }
            CASE_BATTERY_UUID -> {
                Log.d(TAG, "Case battery for $address: $level%")
                updateDeviceByAddress(address) { it.copy(batteryCase = level) }
            }
            HEADSET_BATTERY_UUID, BATTERY_LEVEL_UUID -> {
                // Some headsets report sub-battery via this UUID
                Log.d(TAG, "Headset sub-battery for $address: $level%")
                updateDeviceByAddress(address) {
                    // If we don't have split data yet, treat as main battery
                    if (it.batteryLeft == null && it.batteryRight == null && it.batteryCase == null) {
                        it.copy(batteryLevel = level)
                    } else {
                        it.copy(batteryCase = level)
                    }
                }
            }
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
            val before = currentDevices[index]
            val after = transform(before)
            currentDevices[index] = after
            _devices.value = currentDevices

            // 电量 / 子电量 / 连接状态 真变化时通知监听者(由 ViewModel 决定要不要刷 widget)
            val changed = before.batteryLevel != after.batteryLevel ||
                    before.batteryLeft != after.batteryLeft ||
                    before.batteryRight != after.batteryRight ||
                    before.batteryCase != after.batteryCase ||
                    before.isConnected != after.isConnected
            if (changed) {
                onDeviceChangedListener?.invoke(after)
            }
        }
    }

    /** 当前是否已注册的电量变化监听器。 */
    private var onDeviceChangedListener: ((BluetoothDeviceWithBattery) -> Unit)? = null

    /**
     * 注册/注销监听器。每当某个设备的电量、子电量、连接状态真变化时,
     * 会回调 [listener](传最新一帧的 [BluetoothDeviceWithBattery])。
     * 用于把"App 内电量更新"广播给桌面 widget。
     */
    fun setOnDeviceChangedListener(listener: ((BluetoothDeviceWithBattery) -> Unit)?) {
        this.onDeviceChangedListener = listener
    }

    /** 一次性获取当前所有设备的电量快照(address -> level)。 */
    fun getBatterySnapshot(): Map<String, Int?> {
        return _devices.value.associate { it.address to it.batteryLevel }
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
