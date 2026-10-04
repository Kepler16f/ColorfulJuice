package com.colorfuljuice.bluetoothbattery.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.colorfuljuice.bluetoothbattery.MainActivity
import com.colorfuljuice.bluetoothbattery.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 低电量提醒。
 *
 * 触发点有两处:
 *  1. [com.colorfuljuice.bluetoothbattery.widget.WidgetTickReceiver] 的周期心跳 ——
 *     App 完全没开(进程被冻结)也能由闹钟拉起执行提醒检查;
 *  2. ViewModel 的设备变化回调 —— App 在前台时实时提醒。
 *
 * 数据全部来自系统反射读蓝牙缓存(不走 GATT,毫秒级开销);阈值和隐藏设备列表由
 * ViewModel 镜像进 SharedPreferences —— 广播接收器里 suspend 读 DataStore 不可靠。
 *
 * 去重策略:同一设备两次提醒至少间隔 [RE_ALERT_COOLDOWN_MS],或电量再降
 * [RE_DROP_STEP]% 才立刻重提醒;电量回到阈值以上时清除记录,下次跌破立即可提醒。
 */
object LowBatteryAlert {

    private const val TAG = "LowBatteryAlert"
    private const val PREFS_NAME = "low_battery_alerts"
    private const val MIRROR_PREFS_NAME = "settings_mirror"
    const val CHANNEL_ID = "low_battery"

    private const val RE_ALERT_COOLDOWN_MS = 60 * 60_000L
    private const val RE_DROP_STEP = 5

    // ----- 设置镜像(ViewModel 写,这里读;避免在广播接收器里 suspend 读 DataStore) -----

    fun getThreshold(context: Context): Int {
        return context.getSharedPreferences(MIRROR_PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("low_battery_threshold", 0)
    }

    fun setThreshold(context: Context, value: Int) {
        context.getSharedPreferences(MIRROR_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("low_battery_threshold", value).apply()
    }

    fun setHiddenDevices(context: Context, json: String) {
        context.getSharedPreferences(MIRROR_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("hidden_devices", json).apply()
    }

    /** 扫一遍已配对设备的系统缓存电量,该提醒的提醒。任何异常都不能炸掉调用方(心跳路径)。 */
    @SuppressLint("MissingPermission")
    fun check(context: Context) {
        try {
            val threshold = getThreshold(context)
            if (threshold <= 0) return

            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return

            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return
            // 蓝牙关着时读到的都是过期缓存,不提醒
            if (!adapter.isEnabled) return

            val connectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
            if (!connectGranted) return

            createChannel(context)
            val hidden = readHiddenDevices(context)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()

            for (device in adapter.bondedDevices) {
                val address = device.address ?: continue
                if (address in hidden) continue

                val level = try {
                    val l = device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int
                    if (l != null && l in 0..100) l else continue
                } catch (_: Exception) {
                    continue
                }
                val charging = try {
                    device.javaClass.getMethod("getIsCharging").invoke(device) as? Boolean ?: false
                } catch (_: Exception) {
                    false
                }
                // 回到阈值以上:清除记录,下次跌破立即可提醒
                if (level >= threshold) {
                    if (prefs.contains("last_$address")) {
                        prefs.edit().remove("last_$address").remove("ts_$address").apply()
                    }
                    continue
                }
                if (charging) continue

                val lastLevel = prefs.getInt("last_$address", -1)
                val lastTs = prefs.getLong("ts_$address", 0L)
                val shouldAlert = lastLevel == -1 ||
                        level <= lastLevel - RE_DROP_STEP ||
                        (now - lastTs > RE_ALERT_COOLDOWN_MS && level < lastLevel)
                if (!shouldAlert) continue

                val name = try { device.name ?: address } catch (_: Exception) { address }
                notifyDevice(context, address, name, level, threshold)
                prefs.edit()
                    .putInt("last_$address", level)
                    .putLong("ts_$address", now)
                    .apply()
                Log.d(TAG, "low battery alert for $address: $level% < $threshold%")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "low battery check failed", t)
        }
    }

    private fun readHiddenDevices(context: Context): Set<String> {
        val json = context.getSharedPreferences(MIRROR_PREFS_NAME, Context.MODE_PRIVATE)
            .getString("hidden_devices", null) ?: return emptySet()
        return try {
            Gson().fromJson(json, object : TypeToken<Set<String>>() {}.type) ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun notifyDevice(context: Context, address: String, name: String, level: Int, threshold: Int) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentIntent = PendingIntent.getActivity(
            context,
            address.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(context.getString(R.string.alert_low_battery_title, name))
            .setContentText(context.getString(R.string.alert_low_battery_text, level, threshold))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(address, address.hashCode(), notification)
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.low_battery_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }
}
