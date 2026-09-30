package com.colorfuljuice.bluetoothbattery.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.colorfuljuice.bluetoothbattery.utils.BluetoothBatteryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 点击 widget 刷新按钮时的"深度读取"。
 *
 * 桌面侧反射读系统蓝牙缓存对耳机/手环这类设备有效,但对键盘/鼠标等 HID 设备常常返回 null ——
 * 表现就是 widget 一直显示 "?"、点刷新也没变化。这类设备必须真正建立一次 GATT 连接,
 * 读 Battery Service 才拿得到值。App 内本来就有这套逻辑([BluetoothBatteryService]),
 * 这里把它在 widget 刷新场景下按需调一次:
 *
 *  - 只在用户手动点刷新时触发,周期 tick 不做(每分钟建一次连接太吵);
 *  - 只针对"系统反射读不到电量"的设备;
 *  - 只把"真读到值"的结果写回缓存,失败保持原样(否则缓存被写成 -1,widget 反而变 "?")。
 *
 * 注意:本调用发生在广播接收器里,用 [android.content.BroadcastReceiver.goAsync] 撑住超时窗口。
 */
object WidgetDeepRefresh {

    private const val TAG = "WidgetDeepRefresh"

    /** 单次深度读取的最长等待时间。 */
    private const val MAX_WAIT_MS = 6_000L

    /** 全局只允许一次在跑,避免连点刷新按钮起多个 GATT 连接。 */
    private val running = AtomicBoolean(false)

    /**
     * @param addresses widget 绑定的设备地址
     * @param onFinished 回调(可能在 IO 线程),changed=true 表示确实读到了新电量
     */
    fun start(context: Context, addresses: List<String>, onFinished: (changed: Boolean) -> Unit) {
        if (addresses.isEmpty() || !running.compareAndSet(false, true)) {
            onFinished(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            onFinished(false)
            return
        }

        val appContext = context.applicationContext
        val targets0 = addresses.toSet()

        CoroutineScope(Dispatchers.IO).launch {
            val changed = AtomicBoolean(false)
            var service: BluetoothBatteryService? = null
            try {
                val svc = BluetoothBatteryService(appContext)
                service = svc
                svc.setOnDeviceChangedListener { device ->
                    val level = device.batteryLevel
                    if (device.address in targets0 && level != null && level in 0..100) {
                        WidgetHelper.saveBatteryToCache(appContext, device.address, level, device.isCharging)
                        changed.set(true)
                    }
                }

                svc.loadPairedDevices()
                // 只深度读"系统反射也拿不到"的设备,能反射拿到的早被 getBatteryInfo 写进缓存了
                val targets = svc.devices.value.filter {
                    it.address in targets0 && it.batteryLevel == null
                }

                if (targets.isNotEmpty()) {
                    Log.d(TAG, "deep read for ${targets.size} device(s): ${targets.map { it.address }}")
                    targets.forEach { svc.connectAndReadBattery(it) }

                    val deadline = System.currentTimeMillis() + MAX_WAIT_MS
                    val targetAddresses = targets.map { it.address }
                    while (System.currentTimeMillis() < deadline) {
                        delay(300)
                        val pending = svc.devices.value.filter { it.address in targetAddresses }
                        if (pending.none { it.isConnecting }) {
                            // 已经没有在建的连接;再多等一拍让电量回调落地
                            delay(400)
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "deep refresh failed", t)
            } finally {
                try {
                    service?.destroy()
                } catch (t: Throwable) {
                    Log.e(TAG, "destroy service failed", t)
                }
                running.set(false)
            }
            Log.d(TAG, "deep refresh done, changed=${changed.get()}")
            onFinished(changed.get())
        }
    }
}
