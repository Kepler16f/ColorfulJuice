package com.colorfuljuice.bluetoothbattery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.colorfuljuice.bluetoothbattery.MainActivity
import com.colorfuljuice.bluetoothbattery.R
import com.colorfuljuice.bluetoothbattery.utils.DeviceType

class Widget2x2Single : AppWidgetProvider() {

    companion object {
        const val ACTION_CONFIGURE = "com.colorfuljuice.bluetoothbattery.ACTION_CONFIGURE_WIDGET_2X2_SINGLE"
        private const val SCOPE = "2x2_single"
        private const val TAG = "Widget2x2Single"
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 第一个 2x2 上桌:启动心跳闹钟(进程被冻结后只有 AlarmManager 能叫醒它)
        WidgetRefreshScheduler.scheduleNext(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        if (!WidgetHelper.hasAnyWidget(context)) {
            WidgetRefreshScheduler.cancel(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 系统触发:无条件渲染,不做签名跳过
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            WidgetHelper.removeWidgetConfig(context, widgetId)
        }
        super.onDeleted(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 系统的 ACTION_APPWIDGET_UPDATE 由 super 分发到 onUpdate(),不要再手动刷一遍
        when (intent.action) {
            ACTION_CONFIGURE -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (widgetId != -1) {
                    val address = intent.getStringExtra("device_address")
                    WidgetHelper.saveDeviceAddress(context, widgetId, address)
                    WidgetHelper.saveWidgetType(context, widgetId, "2x2_single")
                    val manager = AppWidgetManager.getInstance(context)
                    updateWidget(context, manager, widgetId, forceSystem = true)
                    WidgetRefreshScheduler.scheduleNext(context)
                }
            }
            // 周期心跳:强制读系统缓存,值没变就跳过重绘
            WidgetHelper.ACTION_WIDGET_TICK -> {
                val manager = AppWidgetManager.getInstance(context)
                val skip = intent.getBooleanExtra(WidgetHelper.EXTRA_SKIP_UNCHANGED, true)
                for (id in manager.getAppWidgetIds(ComponentName(context, Widget2x2Single::class.java))) {
                    updateWidget(context, manager, id, forceSystem = true, skipIfUnchanged = skip)
                }
            }
            // App 侧主动同步:信任刚写入的缓存
            WidgetHelper.ACTION_APP_REFRESH -> {
                val manager = AppWidgetManager.getInstance(context)
                val skip = intent.getBooleanExtra(WidgetHelper.EXTRA_SKIP_UNCHANGED, true)
                for (id in manager.getAppWidgetIds(ComponentName(context, Widget2x2Single::class.java))) {
                    updateWidget(context, manager, id, skipIfUnchanged = skip)
                }
            }
            // 手动点刷新:立刻强制重绘,再补一次深度读取
            WidgetHelper.ACTION_REFRESH_NOW -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, Widget2x2Single::class.java))
                for (id in ids) {
                    updateWidget(context, manager, id, forceSystem = true)
                }
                deepRefresh(context, manager, ids)
            }
        }
    }

    /**
     * 系统反射读不到电量的设备必须真连一次 BLE 才有值。
     * 用 goAsync() 把广播存活时间撑到读取完成,读到就回写缓存并再渲染一次。
     */
    private fun deepRefresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val addresses = ids.toList().mapNotNull { WidgetHelper.getDeviceAddress(context, it) }
        if (addresses.isEmpty()) return
        val pendingResult = goAsync()
        WidgetDeepRefresh.start(context, addresses) {
            try {
                for (id in ids) {
                    updateWidget(context, manager, id)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * @param forceSystem 跳过 App 缓存新鲜度判断,强制反射读系统蓝牙缓存
     * @param skipIfUnchanged 显示内容和上一帧完全一致时不调用 updateAppWidget
     */
    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        forceSystem: Boolean = false,
        skipIfUnchanged: Boolean = false
    ) {
        val address = WidgetHelper.getDeviceAddress(context, widgetId)
        val name = address?.let { WidgetHelper.getDeviceName(context, it) }
        val info = address?.let { WidgetHelper.getBatteryInfo(context, it, forceSystem) }
        val battery = info?.level
        val charging = info?.isCharging == true
        val type: DeviceType = address?.let { WidgetHelper.getDeviceType(context, it) } ?: DeviceType.OTHER

        // 渲染签名:内容没变就别去打扰桌面进程
        val signature = "$address|$name|$battery|$charging|$type"
        if (skipIfUnchanged && WidgetHelper.getRenderSignature(context, SCOPE, widgetId) == signature) {
            Log.d(TAG, "skip unchanged render for widget $widgetId")
            return
        }

        val views = RemoteViews(context.packageName, R.layout.widget_2x2_single)

        if (address == null) {
            views.setTextViewText(R.id.widget_ring_device_name, context.getString(R.string.widget_tap_to_select))
            views.setTextViewText(R.id.widget_ring_battery_pct, "--")
            views.setTextViewText(R.id.widget_fallback_pct, "--")
            views.setViewVisibility(R.id.widget_ring_charging_icon, View.GONE)
            views.setViewVisibility(R.id.widget_charging_icon, View.GONE)
            views.setImageViewResource(R.id.widget_ring_icon, R.drawable.ic_device_other)
            views.setImageViewResource(R.id.widget_ring_bg, R.drawable.widget_ring_battery_unknown)
            views.setInt(R.id.widget_ring_bg, "setImageLevel", 0)
            views.setViewVisibility(R.id.widget_refresh_btn, View.GONE)
        } else {
            // 文字颜色始终按电量等级;白色只用于电量条/圆环
            val color = WidgetHelper.getBatteryColor(battery)

            // Ring section
            views.setTextViewText(R.id.widget_ring_device_name, name)
            views.setTextViewText(R.id.widget_ring_battery_pct, if (battery != null) "$battery%" else "?")
            views.setTextColor(R.id.widget_ring_battery_pct, color)
            views.setViewVisibility(R.id.widget_ring_charging_icon, if (charging) View.VISIBLE else View.GONE)

            // Ring color based on battery level (white while charging)
            val ringRes = when {
                charging -> R.drawable.widget_ring_battery_charging
                battery == null -> R.drawable.widget_ring_battery_unknown
                battery <= 20 -> R.drawable.widget_ring_battery_low
                battery <= 50 -> R.drawable.widget_ring_battery_medium
                else -> R.drawable.widget_ring_battery_high
            }
            views.setImageViewResource(R.id.widget_ring_bg, ringRes)
            // Ring fill level: level range 0-10000, battery is 0-100
            views.setInt(R.id.widget_ring_bg, "setImageLevel", (battery ?: 0) * 100)

            WidgetHelper.setRemoteImageView(context, views, R.id.widget_ring_icon,
                WidgetHelper.getDeviceTypeIconRes(type))

            // Single battery bar
            views.setViewVisibility(R.id.widget_charging_icon, if (charging) View.VISIBLE else View.GONE)
            if (battery != null) {
                WidgetHelper.setBatteryBar(context, views, R.id.widget_fallback_bar, battery, battery, charging)
                views.setTextViewText(R.id.widget_fallback_pct, "$battery%")
                views.setTextColor(R.id.widget_fallback_pct, color)
            } else {
                WidgetHelper.setBatteryBar(context, views, R.id.widget_fallback_bar, 0, null)
            }

            // Show sub-battery rows for headphones
            if (type == DeviceType.HEADPHONE) {
                views.setViewVisibility(R.id.widget_sub_batteries, View.GONE)
            }
            views.setViewVisibility(R.id.widget_refresh_btn, View.VISIBLE)
        }

        // Click: select device if not configured, otherwise open app
        val clickIntent = if (address == null) {
            Intent(context, WidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra("widget_mode", "2x2_single")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, widgetId, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_2x2_single_root, pendingIntent)

        // 右上角 refresh 按钮:发广播给本 provider,触发强制本地刷新
        if (address != null) {
            val refreshIntent = Intent(context, Widget2x2Single::class.java).apply {
                action = WidgetHelper.ACTION_REFRESH_NOW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                widgetId + 2000, // 用偏移量避免和根 click 的 requestCode 冲突
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)
        }

        manager.updateAppWidget(widgetId, views)
        WidgetHelper.setRenderSignature(context, SCOPE, widgetId, signature)
    }
}
