package com.colorfuljuice.bluetoothbattery.widget

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
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

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
}
