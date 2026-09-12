package com.colorfuljuice.bluetoothbattery

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.colorfuljuice.bluetoothbattery.utils.BluetoothBatteryService
import com.colorfuljuice.bluetoothbattery.utils.BluetoothDeviceWithBattery
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class BluetoothBatteryViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothService = BluetoothBatteryService(application)
    private val dataStore = application.dataStore
    private val gson = Gson()

    val devices: StateFlow<List<BluetoothDeviceWithBattery>> = bluetoothService.devices

    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _hiddenDeviceAddresses = MutableStateFlow<Set<String>>(emptySet())
    val hiddenDeviceAddresses: StateFlow<Set<String>> = _hiddenDeviceAddresses.asStateFlow()

    private val _visibleDevices = MutableStateFlow<List<BluetoothDeviceWithBattery>>(emptyList())
    val visibleDevices: StateFlow<List<BluetoothDeviceWithBattery>> = _visibleDevices.asStateFlow()

    private val _currentLanguage = MutableStateFlow("system")
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    companion object {
        private val KEY_HIDDEN_DEVICES = stringPreferencesKey("hidden_devices")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
    }

    init {
        checkBluetoothState()
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            dataStore.data.map { preferences ->
                preferences[KEY_HIDDEN_DEVICES] ?: "[]"
            }.collect { json ->
                val type = object : TypeToken<Set<String>>() {}.type
                val hidden: Set<String> = try {
                    gson.fromJson(json, type) ?: emptySet()
                } catch (e: Exception) {
                    emptySet()
                }
                _hiddenDeviceAddresses.value = hidden
                updateVisibleDevices()
            }
        }
        viewModelScope.launch {
            dataStore.data.map { preferences ->
                preferences[KEY_LANGUAGE] ?: "system"
            }.collect { lang ->
                _currentLanguage.value = lang
            }
        }
    }

    private fun updateVisibleDevices() {
        val hidden = _hiddenDeviceAddresses.value
        _visibleDevices.value = devices.value.filter { it.address !in hidden }
    }

    fun isDeviceHidden(address: String): Boolean {
        return address in _hiddenDeviceAddresses.value
    }

    fun toggleDeviceVisibility(address: String) {
        viewModelScope.launch {
            val current = _hiddenDeviceAddresses.value.toMutableSet()
            if (current.contains(address)) {
                current.remove(address)
            } else {
                current.add(address)
            }
            _hiddenDeviceAddresses.value = current
            dataStore.edit { preferences ->
                preferences[KEY_HIDDEN_DEVICES] = gson.toJson(current)
            }
            updateVisibleDevices()
        }
    }

    fun setLanguage(language: String) {
        viewModelScope.launch {
            _currentLanguage.value = language
            dataStore.edit { preferences ->
                preferences[KEY_LANGUAGE] = language
            }
        }
    }

    fun checkBluetoothState() {
        _isBluetoothEnabled.value = bluetoothService.isBluetoothEnabled()
    }

    fun loadPairedDevices() {
        viewModelScope.launch {
            _isLoading.value = true
            bluetoothService.loadPairedDevices()
            _isLoading.value = false
            updateVisibleDevices()
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
