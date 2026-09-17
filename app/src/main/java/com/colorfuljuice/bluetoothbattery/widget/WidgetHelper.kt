package com.colorfuljuice.bluetoothbattery.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
        getPrefs(context).edit()
            .remove("widget_$widgetId")
            .remove("widget_type_$widgetId")
            .apply()
    }

    // ----- 跨进程电量缓存 -----
    // 桌面 widget 在自己的进程里无法直接读 BluetoothBatteryService 的 StateFlow,
    // 所以 Service 在电量变化时把最新值写到这里;Widget 读取时优先从这里拿,
    // fallback 到反射读 Android 系统蓝牙缓存。
    // 同时记录更新时间戳,可在 UI 上提示"数据可能过期"。

    /** 把单条 (address -> level) 写入缓存。如果 level == null 表示不可用,缓存为 -1。 */
    fun saveBatteryToCache(context: Context, address: String, level: Int?) {
        val safe = level ?: -1
        getCachePrefs(context).edit()
            .putInt("bat_$address", safe)
            .putLong("bat_${address}_ts", System.currentTimeMillis())
            .apply()
    }

    /** 批量写入,在 WidgetHelper 内统一打批,避免频繁 commit。 */
    fun saveBatterySnapshotToCache(context: Context, snapshot: Map<String, Int?>) {
        if (snapshot.isEmpty()) return
        val editor = getCachePrefs(context).edit()
        val now = System.currentTimeMillis()
        for ((address, level) in snapshot) {
            editor.putInt("bat_$address", level ?: -1)
            editor.putLong("bat_${address}_ts", now)
        }
        editor.apply()
    }

    /** 读缓存。返回 null 表示没有任何记录。 */
    fun getCachedBatteryLevel(context: Context, address: String): Int? {
        if (!getCachePrefs(context).contains("bat_$address")) return null
        val v = getCachePrefs(context).getInt("bat_$address", -1)
        return if (v < 0) null else v
    }

    fun getCachedBatteryTimestamp(context: Context, address: String): Long {
        return getCachePrefs(context).getLong("bat_${address}_ts", 0L)
    }

    /**
     * 主动给所有已添加的 widget 触发一次 updateAppWidget。
     * 用 setComponent + sendBroadcast 把广播精确送到三个 WidgetProvider,
     * 它们各自在 onReceive 处理 ACTION_APP_REFRESH 即可。
     */
    fun refreshAllWidgets(context: Context) {
        try {
            val providers = listOf(
                Class.forName(context.packageName + ".widget.Widget1x3"),
                Class.forName(context.packageName + ".widget.Widget2x2Single"),
                Class.forName(context.packageName + ".widget.Widget2x2Dual")
            )
            for (provider in providers) {
                val intent = Intent(context, provider).apply {
                    action = ACTION_APP_REFRESH
                }
                context.sendBroadcast(intent)
            }
            Log.d(TAG, "refreshAllWidgets: dispatched APP_REFRESH to 3 providers")
        } catch (e: Exception) {
            Log.e(TAG, "refreshAllWidgets failed", e)
        }
    }

    fun updateWidgetById(context: Context, widgetId: Int, mode: String) {
        val providerClass = when (mode) {
            "2x2_dual" -> Widget2x2Dual::class.java
            "2x2_single" -> Widget2x2Single::class.java
            else -> Widget1x3::class.java
        }
        try {
            val intent = Intent(context, providerClass).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widget $widgetId", e)
        }
    }

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

    @SuppressLint("MissingPermission")
    fun getBatteryLevel(context: Context, address: String): Int? {
        // 1) 优先用我们自己缓存的最新值,这样即使 widget 进程独立运行、Service 进程不在,
        //    也能拿到 App 最近一次刷新到的电量。
        getCachedBatteryLevel(context, address)?.let { return it }

        // 2) fallback: 反射读系统蓝牙缓存。前提是本应用曾通过 GATT 主动连接过该设备,
        //    或者设备主动发了 BATTERY_LEVEL_CHANGED 广播(不是所有设备都支持)。
        val device = findDeviceByName(context, address) ?: return null
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val level = method.invoke(device) as? Int
            if (level != null && level in 0..100) level else null
        } catch (e: Exception) {
            null
        }
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

    fun getBatteryColor(level: Int?): Int {
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

    fun setBatteryBar(context: Context, views: RemoteViews, viewId: Int, progress: Int, level: Int?) {
        val density = context.resources.displayMetrics.density
        val widthPx = (300 * density).toInt()
        val heightPx = (6 * density).toInt()
        val color = getBatteryColor(level)
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

    fun setBatteryBarSmall(context: Context, views: RemoteViews, viewId: Int, progress: Int, level: Int?) {
        val density = context.resources.displayMetrics.density
        val widthPx = (300 * density).toInt()
        val heightPx = (5 * density).toInt()
        val color = getBatteryColor(level)
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
