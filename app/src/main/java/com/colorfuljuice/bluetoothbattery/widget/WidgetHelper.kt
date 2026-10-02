package com.colorfuljuice.bluetoothbattery.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import com.colorfuljuice.bluetoothbattery.R
import com.colorfuljuice.bluetoothbattery.utils.DeviceType
import com.colorfuljuice.bluetoothbattery.utils.SystemBatteryInfo

object WidgetHelper {
    private const val TAG = "WidgetHelper"
    private const val PREFS_NAME = "widget_prefs"
    private const val CACHE_PREFS_NAME = "widget_battery_cache"

    /**
     * Widget 卡片上"手动刷新"按钮触发的自定义广播。
     * WidgetProvider 在 onReceive 里捕获这个 action 后重新读缓存并 updateAppWidget,
     * 不打开 App、不连 BLE,完全本地刷新。
     */
    const val ACTION_REFRESH_NOW = "com.colorfuljuice.bluetoothbattery.WIDGET_REFRESH_NOW"

    /**
     * App 侧触发的全量刷新广播,会同时触发三个 WidgetProvider 重绘。
     */
    const val ACTION_APP_REFRESH = "com.colorfuljuice.bluetoothbattery.WIDGET_APP_REFRESH"

    /**
     * 定时闹钟触发的周期刷新(WidgetTickReceiver)。
     * 与 [ACTION_REFRESH_NOW] 的区别:tick 强制读系统,但"数值没变就跳过 updateAppWidget",
     * 避免每分钟无谓地重绘桌面。
     */
    const val ACTION_WIDGET_TICK = "com.colorfuljuice.bluetoothbattery.WIDGET_WIDGET_TICK"

    /**
     * 附在刷新 intent 上的"显示内容没变就跳过重绘"开关,由 [refreshAllWidgets] 写入。
     * Provider 缺省按各自语义决定(tick / App 同步默认跳过,手动刷新不跳过)。
     */
    const val EXTRA_SKIP_UNCHANGED = "skip_unchanged"

    /**
     * App 侧写入的电量缓存的有效期。
     * 超过这个时间就认为缓存已经"冻住"了(典型场景:App 进程被回收,不再有人写缓存),
     * 此时 widget 不再无条件信任缓存,而是自己反射去读系统蓝牙缓存。
     */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getCachePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveDeviceAddress(context: Context, widgetId: Int, address: String?) {
        getPrefs(context).edit().putString("widget_$widgetId", address).apply()
    }

    fun getDeviceAddress(context: Context, widgetId: Int): String? {
        return getPrefs(context).getString("widget_$widgetId", null)
    }

    fun saveWidgetType(context: Context, widgetId: Int, type: String) {
        getPrefs(context).edit().putString("widget_type_$widgetId", type).apply()
    }

    fun removeWidgetConfig(context: Context, widgetId: Int) {
        // 2x2 双设备 widget 用的是 widget_<id>_1 / widget_<id>_2,也要一并清掉
        getPrefs(context).edit()
            .remove("widget_$widgetId")
            .remove("widget_${widgetId}_1")
            .remove("widget_${widgetId}_2")
            .remove("widget_type_$widgetId")
            .remove(renderSignatureKey("1x3", widgetId))
            .remove(renderSignatureKey("2x2_single", widgetId))
            .remove(renderSignatureKey("2x2_dual", widgetId))
            .apply()
    }

    // ----- 渲染签名 -----
    // 周期 tick 时如果显示内容一点没变,就跳过 updateAppWidget:
    // 省电、也避免桌面因为"内容相同"的 RemoteViews 反复重排。
    // 注意只在 tick / app 主动同步时使用;系统触发的 onUpdate 必须无条件渲染,
    // 否则桌面进程重启后可能一直停留在 initialLayout。

    private fun renderSignatureKey(scope: String, widgetId: Int) = "render_sig_${scope}_$widgetId"

    fun getRenderSignature(context: Context, scope: String, widgetId: Int): String? {
        return getPrefs(context).getString(renderSignatureKey(scope, widgetId), null)
    }

    fun setRenderSignature(context: Context, scope: String, widgetId: Int, signature: String) {
        // commit:tick 进程随时可能被冻结,apply 的异步写可能丢
        getPrefs(context).edit().putString(renderSignatureKey(scope, widgetId), signature).commit()
    }

    // ----- 跨进程电量缓存 -----
    // 桌面 widget 在自己的进程里无法直接读 BluetoothBatteryService 的 StateFlow,
    // 所以 Service 在电量变化时把最新值写到这里;Widget 读取时优先从这里拿,
    // fallback 到反射读 Android 系统蓝牙缓存。
    // 同时记录更新时间戳,可在 UI 上提示"数据可能过期"。

    /**
     * 把单条 (address -> level + charging) 写入缓存。如果 level == null 表示不可用,缓存为 -1。
     * [chargingKnown]=false 表示这个充电状态只是占位值(反射没读到),此时保留缓存里的旧值,
     * 避免把 GATT 读到的"充电中"冲掉。
     */
    fun saveBatteryToCache(
        context: Context,
        address: String,
        level: Int?,
        isCharging: Boolean,
        chargingKnown: Boolean = true
    ) {
        val safe = level ?: -1
        val charging = if (chargingKnown) isCharging else getCachedCharging(context, address)
        // commit 而非 apply:widget 经常被系统拉起到新进程里读这份缓存,
        // apply 只是排队异步落盘,进程被回收时那次写入可能根本没落地。
        getCachePrefs(context).edit()
            .putInt("bat_$address", safe)
            .putBoolean("charging_$address", charging)
            .putLong("bat_${address}_ts", System.currentTimeMillis())
            .commit()
    }

    /** 批量写入,在 WidgetHelper 内统一打批,避免频繁 commit。充电态未知时保留缓存旧值。 */
    fun saveBatterySnapshotToCache(context: Context, snapshot: Map<String, SystemBatteryInfo>) {
        if (snapshot.isEmpty()) return
        val cachePrefs = getCachePrefs(context)
        val editor = cachePrefs.edit()
        val now = System.currentTimeMillis()
        for ((address, info) in snapshot) {
            val level = if (info.level < 0) null else info.level
            editor.putInt("bat_$address", level ?: -1)
            val charging = if (info.chargingKnown) info.isCharging
                           else cachePrefs.getBoolean("charging_$address", false)
            editor.putBoolean("charging_$address", charging)
            editor.putLong("bat_${address}_ts", now)
        }
        editor.commit()
    }

    /** 读缓存。返回 null 表示没有任何记录。 */
    fun getCachedBatteryLevel(context: Context, address: String): Int? {
        if (!getCachePrefs(context).contains("bat_$address")) return null
        val v = getCachePrefs(context).getInt("bat_$address", -1)
        return if (v < 0) null else v
    }

    fun getCachedCharging(context: Context, address: String): Boolean {
        return getCachePrefs(context).getBoolean("charging_$address", false)
    }

    fun getCachedBatteryTimestamp(context: Context, address: String): Long {
        return getCachePrefs(context).getLong("bat_${address}_ts", 0L)
    }

    /**
     * 主动让三个 WidgetProvider 全部重绘一次。
     *
     * 这里直接实例化 provider 并调用 [AppWidgetProvider.onReceive],而不是 sendBroadcast:
     * App 进程本来就在运行,走广播要经过系统队列(后台/冻结进程时还会被延后或合并),
     * 直接调用是同步的,点了就立刻画。
     *
     * @param force true = 跳过缓存新鲜度判断,强制读系统蓝牙缓存(tick / 手动刷新语义);
     *              false = 信任 App 刚写进去的电量缓存(App 主动同步语义)。
     * @param skipUnchanged true = 显示内容一点没变就跳过 updateAppWidget(省电);
     *                      false = 无条件重绘(用于重启/升级后强制回写)。
     */
    fun refreshAllWidgets(context: Context, force: Boolean = false, skipUnchanged: Boolean = true) {
        val action = if (force) ACTION_WIDGET_TICK else ACTION_APP_REFRESH
        val intent = Intent().setAction(action).putExtra(EXTRA_SKIP_UNCHANGED, skipUnchanged)
        val providers = listOf(Widget1x3(), Widget2x2Single(), Widget2x2Dual())
        for (provider in providers) {
            try {
                provider.onReceive(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "refreshAllWidgets failed for ${provider.javaClass.simpleName}", e)
            }
        }
        Log.d(TAG, "refreshAllWidgets: rendered 3 providers (force=$force, skip=$skipUnchanged)")
    }

    fun updateWidgetById(context: Context, widgetId: Int, mode: String) {
        val provider = when (mode) {
            "2x2_dual" -> Widget2x2Dual()
            "2x2_single" -> Widget2x2Single()
            else -> Widget1x3()
        }
        try {
            provider.onReceive(
                context,
                Intent().setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widget $widgetId", e)
        }
        // 配置/换设备后把心跳闹钟补上(闹钟是跨重启自续的,重复排一次无害)
        WidgetRefreshScheduler.scheduleNext(context)
    }

    /** 当前桌面上是否还有本应用的任意一个 widget。 */
    fun hasAnyWidget(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return providerClasses(context).any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    /** 所有已配置 widget 绑定的设备地址(去重)。 */
    fun getWidgetAddresses(context: Context): List<String> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyList()
        val prefs = getPrefs(context)
        val result = linkedSetOf<String>()
        for (cls in providerClasses(context)) {
            for (id in manager.getAppWidgetIds(ComponentName(context, cls))) {
                getDeviceAddress(context, id)?.takeIf { it.isNotBlank() }?.let { result.add(it) }
                prefs.getString("widget_${id}_1", null)?.takeIf { it.isNotBlank() }?.let { result.add(it) }
                prefs.getString("widget_${id}_2", null)?.takeIf { it.isNotBlank() }?.let { result.add(it) }
            }
        }
        return result.toList()
    }

    private fun providerClasses(context: Context): List<Class<*>> = listOf(
        Widget1x3::class.java,
        Widget2x2Single::class.java,
        Widget2x2Dual::class.java
    )

    @SuppressLint("MissingPermission")
    fun findDeviceByName(context: Context, address: String): BluetoothDevice? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return null
        return try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding device $address", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun getDeviceName(context: Context, address: String): String {
        val device = findDeviceByName(context, address) ?: return "Unknown"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                device.alias ?: device.name ?: "Unknown"
            } else {
                device.name ?: "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * 读电量+充电状态,优先级:
     *  1) App 侧写入的缓存,且未超过 [CACHE_TTL_MS](说明 App 还在正常喂数据);
     *  2) 自己反射读系统蓝牙缓存 —— 这一步不需要 App 进程存活、不需要建立 BLE 连接,
     *     是"App 没打开 / 进程被回收后 widget 仍能拿到新数据"的关键;
     *  3) 系统也读不到时,才退回旧缓存(至少还能显示上次的值)。
     *
     * @param forceSystem 跳过第 1 步,直接读系统。手动刷新按钮和周期 tick 必须走这条路:
     *                    否则 App 被"墓碑"冻住后的 5 分钟里缓存仍然"新鲜",
     *                    点刷新拿到的还是旧值 —— 表现就是"点了没反应"。
     */
    @SuppressLint("MissingPermission")
    @JvmOverloads
    fun getBatteryInfo(context: Context, address: String, forceSystem: Boolean = false): SystemBatteryInfo? {
        val cached = getCachedBatteryLevel(context, address)
        val isFresh = System.currentTimeMillis() - getCachedBatteryTimestamp(context, address) < CACHE_TTL_MS

        if (!forceSystem && cached != null && isFresh) {
            return SystemBatteryInfo(cached, getCachedCharging(context, address))
        }

        readSystemBattery(context, address)?.let { live ->
            // 反射拿不到充电态时保留缓存旧值(可能是 GATT 读到的"充电中")
            var charging = if (live.chargingKnown) live.isCharging else getCachedCharging(context, address)
            var chargingKnown = live.chargingKnown
            // 充电态从未真正读到过时,用电量涨跌推断:涨=充电中,跌=未充电。
            // 手写笔等外设不暴露任何充电特征,这是它们唯一的充电检测途径;
            // 上下涨落是电量报告的物理约束,推断可靠,且之后电量的真实读数(特征/反射)随时可以纠正。
            if (!chargingKnown && cached != null && live.level != cached) {
                charging = live.level > cached
                chargingKnown = true
            }
            saveBatteryToCache(context, address, live.level, charging, chargingKnown = true)
            return SystemBatteryInfo(live.level, charging)
        }

        return cached?.let { SystemBatteryInfo(it, getCachedCharging(context, address)) }
    }

    /** 反射读系统蓝牙缓存里的电量与充电状态(隐藏 API,失败返回 null)。 */
    @SuppressLint("MissingPermission")
    private fun readSystemBattery(context: Context, address: String): SystemBatteryInfo? {
        val device = findDeviceByName(context, address) ?: return null
        var level: Int? = null
        var charging = false
        var chargingKnown = false
        try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val l = method.invoke(device) as? Int
            if (l != null && l in 0..100) level = l
        } catch (_: Exception) {}
        try {
            val method = device.javaClass.getMethod("getIsCharging")
            val c = method.invoke(device) as? Boolean
            if (c != null) {
                charging = c
                chargingKnown = true
            }
        } catch (_: Exception) {}
        return level?.let { SystemBatteryInfo(it, charging, chargingKnown) }
    }

    @SuppressLint("MissingPermission")
    fun getDeviceType(context: Context, address: String): DeviceType {
        val device = findDeviceByName(context, address) ?: return DeviceType.OTHER
        val nameLower = try { device.name?.lowercase() ?: "" } catch (_: Exception) { "" }
        if (nameLower.contains("pen") || nameLower.contains("stylus") || nameLower.contains("pencil"))
            return DeviceType.PEN
        if (nameLower.contains("mouse") || nameLower.contains("\u9f20\u6807"))
            return DeviceType.MOUSE
        if (nameLower.contains("keyboard") || nameLower.contains("\u952e\u76d8") ||
            nameLower.contains("keychron") || nameLower.contains("logitech k"))
            return DeviceType.KEYBOARD
        try {
            val clazz = device.bluetoothClass ?: return DeviceType.OTHER
            when (clazz.majorDeviceClass) {
                android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> return DeviceType.HEADPHONE
                android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> {
                    val deviceClass = clazz.deviceClass
                    return when (deviceClass) {
                        android.bluetooth.BluetoothClass.Device.PERIPHERAL_KEYBOARD,
                        android.bluetooth.BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING -> DeviceType.KEYBOARD
                        android.bluetooth.BluetoothClass.Device.PERIPHERAL_POINTING,
                        android.bluetooth.BluetoothClass.Device.PERIPHERAL_NON_KEYBOARD_NON_POINTING -> DeviceType.MOUSE
                        else -> DeviceType.KEYBOARD
                    }
                }
            }
        } catch (_: Exception) {}
        return DeviceType.OTHER
    }

    fun getDeviceTypeIconRes(type: DeviceType): Int {
        return when (type) {
            DeviceType.HEADPHONE -> R.drawable.ic_device_headphone
            DeviceType.PEN -> R.drawable.ic_device_pen
            DeviceType.KEYBOARD -> R.drawable.ic_device_keyboard
            DeviceType.MOUSE -> R.drawable.ic_device_mouse
            DeviceType.OTHER -> R.drawable.ic_device_other
        }
    }

    fun getBatteryColor(level: Int?, isCharging: Boolean = false): Int {
        if (isCharging) return 0xFFFFFFFF.toInt()
        if (level == null) return 0xFF9E9E9E.toInt()
        return when {
            level > 60 -> 0xFF4CAF50.toInt()
            level > 20 -> 0xFFFFC107.toInt()
            else -> 0xFFF44336.toInt()
        }
    }

    fun setRemoteImageView(context: Context, views: RemoteViews, viewId: Int, drawableRes: Int) {
        val drawable = context.getDrawable(drawableRes) ?: return
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        views.setImageViewBitmap(viewId, bitmap)
    }

    fun setBatteryBar(context: Context, views: RemoteViews, viewId: Int, progress: Int, level: Int?, isCharging: Boolean = false) {
        val density = context.resources.displayMetrics.density
        val widthPx = (300 * density).toInt()
        val heightPx = (6 * density).toInt()
        val color = getBatteryColor(level, isCharging)
        val bgColor = 0xFFE0E0E0.toInt()
        val cornerRadius = 4 * density

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
        }
        val bgRect = android.graphics.RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        val progressWidth = (widthPx * progress.coerceIn(0, 100) / 100f)
        if (progressWidth > 0f) {
            val fgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
            }
            val fgRect = android.graphics.RectF(0f, 0f, progressWidth, heightPx.toFloat())
            canvas.drawRoundRect(fgRect, cornerRadius, cornerRadius, fgPaint)
        }

        views.setImageViewBitmap(viewId, bitmap)
    }

    fun setBatteryBarSmall(context: Context, views: RemoteViews, viewId: Int, progress: Int, level: Int?, isCharging: Boolean = false) {
        val density = context.resources.displayMetrics.density
        val widthPx = (300 * density).toInt()
        val heightPx = (5 * density).toInt()
        val color = getBatteryColor(level, isCharging)
        val bgColor = 0xFFE8E8E8.toInt()
        val cornerRadius = 2 * density

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
        }
        val bgRect = android.graphics.RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        val progressWidth = (widthPx * progress.coerceIn(0, 100) / 100f)
        if (progressWidth > 0f) {
            val fgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
            }
            val fgRect = android.graphics.RectF(0f, 0f, progressWidth, heightPx.toFloat())
            canvas.drawRoundRect(fgRect, cornerRadius, cornerRadius, fgPaint)
        }

        views.setImageViewBitmap(viewId, bitmap)
    }
}
