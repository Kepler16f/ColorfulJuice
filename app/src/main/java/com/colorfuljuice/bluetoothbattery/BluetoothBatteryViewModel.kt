package com.colorfuljuice.bluetoothbattery

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.colorfuljuice.bluetoothbattery.utils.BluetoothBatteryService
import com.colorfuljuice.bluetoothbattery.utils.BluetoothDeviceWithBattery
import com.colorfuljuice.bluetoothbattery.utils.LowBatteryAlert
import com.colorfuljuice.bluetoothbattery.utils.UpdateManager
import com.colorfuljuice.bluetoothbattery.utils.UpdateStatus
import com.colorfuljuice.bluetoothbattery.widget.WidgetHelper
import com.colorfuljuice.bluetoothbattery.widget.WidgetRefreshScheduler
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
    private val updateManager = UpdateManager(application)
    private val dataStore = application.dataStore
    private val gson = Gson()

    val devices: StateFlow<List<BluetoothDeviceWithBattery>> = bluetoothService.devices

    // ---- 应用内更新 ----

    val updateStatus: StateFlow<UpdateStatus> = updateManager.status

    val currentVersionName: String
        get() = updateManager.currentVersionName

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

    // "电量变化即刷新 widget":打开后,Service 中任何一个设备的电量真变化,
    // 我们把这一帧写进 SharedPreferences 缓存,并主动给三个 WidgetProvider 发广播。
    // App 不在前台时由系统按 updatePeriodMillis(默认 30 分钟+)兜底。
    private val _batteryChangeRefresh = MutableStateFlow(true)
    val batteryChangeRefresh: StateFlow<Boolean> = _batteryChangeRefresh.asStateFlow()

    // 低电量提醒阈值(%),0 = 关闭。设置变更时镜像进 SharedPreferences,
    // 供心跳/广播接收器里的 LowBatteryAlert 读取(那边不能 suspend 读 DataStore)。
    private val _lowBatteryThreshold = MutableStateFlow(0)
    val lowBatteryThreshold: StateFlow<Int> = _lowBatteryThreshold.asStateFlow()

    companion object {
        private const val TAG = "BluetoothBatteryViewModel"
        private val KEY_HIDDEN_DEVICES = stringPreferencesKey("hidden_devices")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_CONNECTION_TIMEOUT = intPreferencesKey("connection_timeout")
        private val KEY_BATTERY_REFRESH_INTERVAL = intPreferencesKey("battery_refresh_interval")
        private val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        private val KEY_USE_DYNAMIC_COLOR = stringPreferencesKey("use_dynamic_color")
        private val KEY_BATTERY_CHANGE_REFRESH = booleanPreferencesKey("battery_change_refresh")
        private val KEY_LOW_BATTERY_THRESHOLD = intPreferencesKey("low_battery_threshold")

        /** 两次 widget 全量刷新之间的最小间隔,用于合并刷新风暴。 */
        private const val WIDGET_REFRESH_MIN_INTERVAL_MS = 1500L
    }

    // Service 在连接/读取阶段会非常密集地回调(一个设备一次连接就有 连接中→已连接→电量 多次变化,
    // "连接全部"时更是 N 个设备叠加)。每个回调都立刻发广播 + 同步绘制 RemoteViews,
    // 会把主线程消息队列塞满,BroadcastReceiver 排队积压 => widget 看起来"滞后/卡住"。
    // 这里做尾部合并:窗口内的多次请求只留最后一次真正执行。
    private val widgetRefreshHandler = Handler(Looper.getMainLooper())
    private var pendingWidgetRefresh: Runnable? = null
    private var lastWidgetRefreshAt = 0L

    init {
        checkBluetoothState()
        loadPreferences()
        setupWidgetBridge()
    }

    /**
     * 在 Service 和桌面 Widget 之间架桥:
     *  1. 监听 Service 的"设备变化"回调,把最新电量写 SharedPreferences 缓存(供 widget 进程读);
     *  2. 如果用户开启了"电量变化即刷新",立刻给三个 WidgetProvider 发广播,触发 updateAppWidget。
     *  3. App 内 loadPairedDevices() 完成时也会调 [syncWidgets] 兜底一次。
     */
    private fun setupWidgetBridge() {
        // 兜底:"强行停止"会清掉 AlarmManager 里的闹钟,而 widget 可能还挂在桌面上,
        // 不补的话心跳就永久断线。这里用 ensureScheduled 而不是无脑 scheduleNext ——
        // 后者每次冷启动都会把下一次 tick 往后推整整一个周期,频繁开 App 反而让心跳老轮不到。
        WidgetRefreshScheduler.ensureScheduled(getApplication())
        bluetoothService.setOnDeviceChangedListener { device ->
            WidgetHelper.saveBatteryToCache(
                getApplication(),
                device.address,
                device.batteryLevel,
                device.isCharging,
                device.chargingKnown,
                device.batteryLeft,
                device.batteryRight,
                device.batteryCase
            )
            if (_batteryChangeRefresh.value) {
                scheduleWidgetRefresh()
            }
            // App 在前台时的实时低电量提醒(心跳路径兜底 App 没开的场景)
            LowBatteryAlert.check(getApplication())
        }
    }

    /** 合并窗口内的 widget 刷新请求,只执行最后一次。 */
    private fun scheduleWidgetRefresh() {
        pendingWidgetRefresh?.let { widgetRefreshHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingWidgetRefresh = null
            lastWidgetRefreshAt = SystemClock.elapsedRealtime()
            WidgetHelper.refreshAllWidgets(getApplication())
        }
        pendingWidgetRefresh = runnable
        val elapsed = SystemClock.elapsedRealtime() - lastWidgetRefreshAt
        widgetRefreshHandler.postDelayed(runnable, (WIDGET_REFRESH_MIN_INTERVAL_MS - elapsed).coerceAtLeast(0L))
    }

    /** 主动给所有桌面 widget 触发一次重绘,带最新缓存。 */
    fun syncWidgets() {
        val ctx = getApplication<Application>()
        // 先把当前最新电量快照一次性写进缓存(避免单个写入太多次 commit)
        WidgetHelper.saveBatterySnapshotToCache(ctx, bluetoothService.devices.value)
        WidgetHelper.refreshAllWidgets(ctx)
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
                // 镜像给广播接收器侧的低电量提醒用
                LowBatteryAlert.setHiddenDevices(getApplication(), json)
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
        viewModelScope.launch {
            dataStore.data.map { preferences ->
                preferences[KEY_BATTERY_CHANGE_REFRESH] ?: true
            }.collect { enabled ->
                _batteryChangeRefresh.value = enabled
            }
        }
        viewModelScope.launch {
            dataStore.data.map { preferences ->
                preferences[KEY_LOW_BATTERY_THRESHOLD] ?: 0
            }.collect { threshold ->
                _lowBatteryThreshold.value = threshold
                LowBatteryAlert.setThreshold(getApplication(), threshold)
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
            // Also save to SharedPreferences for plain Activities
            getApplication<Application>().getSharedPreferences("settings_theme", android.content.Context.MODE_PRIVATE)
                .edit().putInt("theme_mode", mode).apply()
        }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            _useDynamicColor.value = enabled
            dataStore.edit { preferences ->
                preferences[KEY_USE_DYNAMIC_COLOR] = enabled.toString()
            }
            // Also save to SharedPreferences for plain Activities
            getApplication<Application>().getSharedPreferences("settings_theme", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("use_dynamic_color", enabled).apply()
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
            // 应用内刷新完成后,同步把最新电量快照写进 widget 缓存并触发重绘,
            // 这是修复"应用打开了但桌面 widget 仍显示旧数据"的关键一步。
            syncWidgets()
            _isLoading.value = false
            _refreshCompleted.value = true
        }
    }

    fun setBatteryChangeRefresh(enabled: Boolean) {
        viewModelScope.launch {
            _batteryChangeRefresh.value = enabled
            dataStore.edit { preferences ->
                preferences[KEY_BATTERY_CHANGE_REFRESH] = enabled
            }
        }
    }

    fun setLowBatteryThreshold(threshold: Int) {
        _lowBatteryThreshold.value = threshold
        LowBatteryAlert.setThreshold(getApplication(), threshold)
        viewModelScope.launch {
            dataStore.edit { preferences ->
                preferences[KEY_LOW_BATTERY_THRESHOLD] = threshold
            }
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

    // ---- 应用内更新 ----

    fun checkForUpdates() = updateManager.checkForUpdates()

    fun downloadUpdate() = updateManager.startDownload()

    fun cancelUpdateDownload() = updateManager.cancelDownload()

    /** 返回 true 表示已拉起系统安装器。 */
    fun installUpdate(): Boolean = updateManager.installPending()

    fun dismissUpdate() = updateManager.reset()

    override fun onCleared() {
        super.onCleared()
        pendingWidgetRefresh?.let { widgetRefreshHandler.removeCallbacks(it) }
        pendingWidgetRefresh = null
        bluetoothService.destroy()
    }
}
