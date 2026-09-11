package com.colorfuljuice.bluetoothbattery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.colorfuljuice.bluetoothbattery.utils.BluetoothBatteryService
import com.colorfuljuice.bluetoothbattery.utils.BluetoothDeviceWithBattery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BluetoothBatteryViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothService = BluetoothBatteryService(application)

    val devices: StateFlow<List<BluetoothDeviceWithBattery>> = bluetoothService.devices

    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        checkBluetoothState()
    }

    fun checkBluetoothState() {
        _isBluetoothEnabled.value = bluetoothService.isBluetoothEnabled()
    }

    fun loadPairedDevices() {
        viewModelScope.launch {
            _isLoading.value = true
            bluetoothService.loadPairedDevices()
            _isLoading.value = false
        }
    }

    fun connectToDevice(device: BluetoothDeviceWithBattery) {
        bluetoothService.connectAndReadBattery(device)
    }

    fun disconnectDevice(address: String) {
        bluetoothService.disconnectDevice(address)
    }

    fun disconnectAll() {
        bluetoothService.disconnectAll()
    }

    fun connectAllDevices() {
        viewModelScope.launch {
            devices.value.forEach { device ->
                if (!device.isConnected && !device.isConnecting) {
                    connectToDevice(device)
                }
            }
        }
    }
}
