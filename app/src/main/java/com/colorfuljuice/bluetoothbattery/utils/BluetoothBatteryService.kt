package com.colorfuljuice.bluetoothbattery.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _devices = MutableStateFlow<List<BluetoothDeviceWithBattery>>(emptyList())
    val devices: StateFlow<List<BluetoothDeviceWithBattery>> = _devices.asStateFlow()

    private val gattConnections = mutableMapOf<String, BluetoothGatt>()

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun loadPairedDevices() {
        val pairedDevices = getBondedDevices()
        val deviceList = pairedDevices.map { device ->
            BluetoothDeviceWithBattery(
                device = device,
                name = device.alias ?: device.name ?: "Unknown Device",
                address = device.address
            )
        }
        _devices.value = deviceList
    }

    @SuppressLint("MissingPermission")
    fun connectAndReadBattery(deviceWithBattery: BluetoothDeviceWithBattery) {
        val device = deviceWithBattery.device
        val address = deviceWithBattery.address

        updateDevice(deviceWithBattery.copy(isConnecting = true, error = null))

        try {
            val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "Connected to ${deviceWithBattery.name}")
                            updateDevice(deviceWithBattery.copy(isConnecting = false, isConnected = true))
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "Disconnected from ${deviceWithBattery.name}")
                            updateDevice(deviceWithBattery.copy(isConnecting = false, isConnected = false))
                            gatt.close()
                            gattConnections.remove(address)
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
                        if (batteryService != null) {
                            val batteryCharacteristic = batteryService.getCharacteristic(BATTERY_LEVEL_UUID)
                            if (batteryCharacteristic != null) {
                                gatt.readCharacteristic(batteryCharacteristic)
                            } else {
                                updateDevice(deviceWithBattery.copy(
                                    isConnected = true,
                                    error = "Battery level characteristic not found"
                                ))
                            }
                        } else {
                            updateDevice(deviceWithBattery.copy(
                                isConnected = true,
                                error = "Battery service not available"
                            ))
                        }
                    } else {
                        updateDevice(deviceWithBattery.copy(
                            isConnected = true,
                            error = "Failed to discover services"
                        ))
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_UUID) {
                        val batteryLevel = value[0].toInt() and 0xFF
                        Log.d(TAG, "Battery level for ${deviceWithBattery.name}: $batteryLevel%")
                        updateDevice(deviceWithBattery.copy(
                            batteryLevel = batteryLevel,
                            isConnected = true
                        ))
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_UUID) {
                        val batteryLevel = characteristic.value[0].toInt() and 0xFF
                        Log.d(TAG, "Battery level for ${deviceWithBattery.name}: $batteryLevel%")
                        updateDevice(deviceWithBattery.copy(
                            batteryLevel = batteryLevel,
                            isConnected = true
                        ))
                    }
                }
            })
            gattConnections[address] = gatt
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to ${deviceWithBattery.name}", e)
            updateDevice(deviceWithBattery.copy(
                isConnecting = false,
                error = "Connection failed: ${e.message}"
            ))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice(address: String) {
        gattConnections[address]?.close()
        gattConnections.remove(address)
        val currentDevices = _devices.value.toMutableList()
        val index = currentDevices.indexOfFirst { it.address == address }
        if (index != -1) {
            currentDevices[index] = currentDevices[index].copy(isConnected = false, isConnecting = false)
            _devices.value = currentDevices
        }
    }

    fun disconnectAll() {
        gattConnections.values.forEach { it.close() }
        gattConnections.clear()
        _devices.value = _devices.value.map {
            it.copy(isConnected = false, isConnecting = false)
        }
    }

    private fun updateDevice(updatedDevice: BluetoothDeviceWithBattery) {
        val currentDevices = _devices.value.toMutableList()
        val index = currentDevices.indexOfFirst { it.address == updatedDevice.address }
        if (index != -1) {
            currentDevices[index] = updatedDevice
            _devices.value = currentDevices
        }
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
}
