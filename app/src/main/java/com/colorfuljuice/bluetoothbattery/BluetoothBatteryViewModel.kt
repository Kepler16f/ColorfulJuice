package com.colorfuljuice.bluetoothbattery

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.colorfuljuice.bluetoothbattery.utils.BluetoothBatteryService
import com.colorfuljuice.bluetoothbattery.utils.BluetoothDeviceWithBattery
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    private val _refreshCompleted = MutableStateFlow(false)
    val refreshCompleted: StateFlow<Boolean> = _refreshCompleted.asStateFlow()

    private val _hiddenDeviceAddresses = MutableStateFlow<Set<String>>(emptySet())
    val hiddenDeviceAddresses: StateFlow<Set<String>> = _hiddenDeviceAddresses.asStateFlow()

    val visibleDevices: StateFlow<List<BluetoothDeviceWithBattery>> = combine(
        bluetoothService.devices,
        _hiddenDeviceAddresses
    ) { allDevices, hidden ->
        allDevices.filter { it.address !in hidden }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private val _currentLanguage = MutableStateFlow("system")
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    private val _connectionTimeout = MutableStateFlow(12)
    val connectionTimeout: StateFlow<Int> = _connectionTimeout.asStateFlow()

    private val _batteryRefreshInterval = MutableStateFlow(0)
    val batteryRefreshInterval: StateFlow<Int> = _batteryRefreshInterval.asStateFlow()

    private val _themeMode = MutableStateFlow(0)
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    private val _useDynamicColor = MutableStateFlow(true)
    val useDynamicColor: StateFlow<Boolean> = _useDynamicColor.asStateFlow()

    companion object {
        private val KEY_HIDDEN_DEVICES = stringPreferencesKey("hidden_devices")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_CONNECTION_TIMEOUT = intPreferencesKey("connection_timeout")
        private val KEY_BATTERY_REFRESH_INTERVAL = intPreferencesKey("battery_refresh_interval")
        private val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        private val KEY_USE_DYNAMIC_COLOR = stringPreferencesKey("use_dynamic_color")
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
            }
        }
        viewModelScope.launch {
            dataStore.data.map { preferences ->
                preferences[KEY_LANGUAGE] ?: "system"
            }.collect { lang ->
                _currentLanguage.value = lang
            }
        }
        viewModelScope.launch {
            dataStore.data.map { preferences ->
                preferences[KEY_CONNECTION_TIMEOUT] ?: 12
            }.collect { timeout ->
                _connectionTimeout.value = timeout
                bluetoothService.setConnectionTimeout(timeout)
            }
        }
        viewModelScope.launch {
            dataStore.data.map { preferences ->
                preferences[KEY_BATTERY_REFRESH_INTERVAL] ?: 0
            }.collect { interval ->
                _batteryRefreshInterval.value = interval
                bluetoothService.setBatteryRefreshInterval(interval)
            }
        }
        viewModelScope.launch {
            dataStore.data.map { preferences ->
                preferences[KEY_THEME_MODE] ?: 0
            }.collect { mode ->
                _themeMode.value = mode
            }
        }
        viewModelScope.launch {
            dataStore.data.map { preferences ->
                preferences[KEY_USE_DYNAMIC_COLOR] ?: "true"
            }.collect { value ->
                _useDynamicColor.value = value == "true"
            }
        }
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

    fun setConnectionTimeout(timeout: Int) {
        viewModelScope.launch {
            _connectionTimeout.value = timeout
            bluetoothService.setConnectionTimeout(timeout)
            dataStore.edit { preferences ->
                preferences[KEY_CONNECTION_TIMEOUT] = timeout
            }
        }
    }

    fun setBatteryRefreshInterval(interval: Int) {
        viewModelScope.launch {
            _batteryRefreshInterval.value = interval
            bluetoothService.setBatteryRefreshInterval(interval)
            dataStore.edit { preferences ->
                preferences[KEY_BATTERY_REFRESH_INTERVAL] = interval
            }
        }
    }

    fun setThemeMode(mode: Int) {
        viewModelScope.launch {
            _themeMode.value = mode
            dataStore.edit { preferences ->
                preferences[KEY_THEME_MODE] = mode
            }
        }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            _useDynamicColor.value = enabled
            dataStore.edit { preferences ->
                preferences[KEY_USE_DYNAMIC_COLOR] = enabled.toString()
            }
        }
    }

    fun checkBluetoothState() {
        _isBluetoothEnabled.value = bluetoothService.isBluetoothEnabled()
    }

    fun loadPairedDevices() {
        viewModelScope.launch {
            _isLoading.value = true
            _refreshCompleted.value = false
            bluetoothService.loadPairedDevices()
            _isLoading.value = false
            _refreshCompleted.value = true
        }
    }

    fun consumeRefreshCompleted() {
        _refreshCompleted.value = false
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
                if (!device.isConnecting) {
                    connectToDevice(device)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothService.destroy()
    }
}
