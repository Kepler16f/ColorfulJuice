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

class Widget1x3 : AppWidgetProvider() {

    companion object {
        const val ACTION_CONFIGURE = "com.colorfuljuice.bluetoothbattery.ACTION_CONFIGURE_WIDGET_1X3"
        private const val SCOPE = "1x3"
        private const val TAG = "Widget1x3"
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 第一个 1x3 上桌:启动心跳闹钟。进程被"墓碑"冻结后,只有 AlarmManager 能把它叫醒。
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
        // 系统触发(上桌 / updatePeriodMillis / 桌面进程重启):无条件渲染,
        // 不做签名跳过,否则桌面可能一直停在 initialLayout。
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
        // 系统的 ACTION_APPWIDGET_UPDATE 由 super 分发到 onUpdate(),不要再手动刷一遍
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_CONFIGURE -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (widgetId != -1) {
                    val address = intent.getStringExtra("device_address")
                    WidgetHelper.saveDeviceAddress(context, widgetId, address)
                    WidgetHelper.saveWidgetType(context, widgetId, "1x3")
                    val manager = AppWidgetManager.getInstance(context)
                    updateWidget(context, manager, widgetId, forceSystem = true)
                    WidgetRefreshScheduler.scheduleNext(context)
                }
            }
            // 周期心跳:强制读系统蓝牙缓存,值没变就跳过重绘
            WidgetHelper.ACTION_WIDGET_TICK -> {
                val manager = AppWidgetManager.getInstance(context)
                val skip = intent.getBooleanExtra(WidgetHelper.EXTRA_SKIP_UNCHANGED, true)
                for (id in manager.getAppWidgetIds(ComponentName(context, Widget1x3::class.java))) {
                    updateWidget(context, manager, id, forceSystem = true, skipIfUnchanged = skip)
                }
            }
            // App 侧主动同步:缓存刚写过,直接信任它
            WidgetHelper.ACTION_APP_REFRESH -> {
                val manager = AppWidgetManager.getInstance(context)
                val skip = intent.getBooleanExtra(WidgetHelper.EXTRA_SKIP_UNCHANGED, true)
                for (id in manager.getAppWidgetIds(ComponentName(context, Widget1x3::class.java))) {
                    updateWidget(context, manager, id, skipIfUnchanged = skip)
                }
            }
            // 手动点刷新:立刻强制重绘一次,再补一次深度读取(系统反射读不到的设备)
            WidgetHelper.ACTION_REFRESH_NOW -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, Widget1x3::class.java))
                for (id in ids) {
                    updateWidget(context, manager, id, forceSystem = true)
                }
                deepRefresh(context, manager, ids)
            }
        }
    }

    /**
     * 系统反射读不到电量的设备(典型是键盘/鼠标)必须真连一次 BLE 才有值。
     * 用 goAsync() 把广播的存活时间撑到读取完成,读到就回写缓存并再渲染一次。
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
        val connected = address != null && WidgetHelper.isDeviceConnected(context, address)
        val type: DeviceType = address?.let { WidgetHelper.getDeviceType(context, it) } ?: DeviceType.OTHER

        // 渲染签名:tick 高频触发,内容没变就别去打扰桌面进程
        val signature = "$address|$name|$battery|$charging|$type|$connected"
        if (skipIfUnchanged && WidgetHelper.getRenderSignature(context, SCOPE, widgetId) == signature) {
            Log.d(TAG, "skip unchanged render for widget $widgetId")
            return
        }

        val views = RemoteViews(context.packageName, R.layout.widget_1x3)

        if (address == null) {
            // Show placeholder
            views.setTextViewText(R.id.widget_device_name, context.getString(R.string.widget_tap_to_select))
            views.setTextViewText(R.id.widget_battery_text, "--")
            views.setViewVisibility(R.id.widget_battery_bar, View.INVISIBLE)
            views.setViewVisibility(R.id.widget_charging_icon, View.GONE)
            views.setImageViewResource(R.id.widget_device_icon, R.drawable.ic_device_other)
            // 未选设备时,refresh 按钮无意义,隐藏
            views.setViewVisibility(R.id.widget_refresh_btn, View.GONE)
            views.setViewVisibility(R.id.widget_conn_dot, View.GONE)
        } else {
            // 文字颜色始终按电量等级;白色只用于电量条
            val color = WidgetHelper.getBatteryColor(battery)

            views.setTextViewText(R.id.widget_device_name, name)
            views.setTextViewText(R.id.widget_battery_text, if (battery != null) "$battery%" else "?")
            views.setTextColor(R.id.widget_battery_text, color)
            views.setViewVisibility(R.id.widget_charging_icon, if (charging) View.VISIBLE else View.GONE)

            // 连接状态小圆点:绿=已连接,灰=未连接(放仓充电/离开范围时一目了然)
            views.setViewVisibility(R.id.widget_conn_dot, View.VISIBLE)
            views.setInt(R.id.widget_conn_dot, "setColorFilter", WidgetHelper.getConnDotColor(connected))

            WidgetHelper.setRemoteImageView(context, views, R.id.widget_device_icon,
                WidgetHelper.getDeviceTypeIconRes(type))

            if (battery != null) {
                views.setViewVisibility(R.id.widget_battery_bar, View.VISIBLE)
                WidgetHelper.setBatteryBar(context, views, R.id.widget_battery_bar, battery, battery, charging)
            } else {
                views.setViewVisibility(R.id.widget_battery_bar, View.INVISIBLE)
            }
            views.setViewVisibility(R.id.widget_refresh_btn, View.VISIBLE)
        }

        // 整个卡片 click:已配置则打开 App,未配置则去选择
        val clickIntent = if (address == null) {
            Intent(context, WidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra("widget_mode", "1x3")
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
        views.setOnClickPendingIntent(R.id.widget_1x3_root, pendingIntent)

        // 右上角 refresh 按钮:发广播给本 provider,触发强制本地刷新
        if (address != null) {
            val refreshIntent = Intent(context, Widget1x3::class.java).apply {
                action = WidgetHelper.ACTION_REFRESH_NOW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                widgetId + 1000, // 用偏移量避免和根 click 的 requestCode 冲突
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)
        }

        manager.updateAppWidget(widgetId, views)
        WidgetHelper.setRenderSignature(context, SCOPE, widgetId, signature)
    }
}
