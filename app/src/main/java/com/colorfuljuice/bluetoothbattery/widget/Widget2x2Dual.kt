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

class Widget2x2Dual : AppWidgetProvider() {

    companion object {
        const val ACTION_CONFIGURE = "com.colorfuljuice.bluetoothbattery.ACTION_CONFIGURE_WIDGET_2X2_DUAL"
        private const val SCOPE = "2x2_dual"
        private const val TAG = "Widget2x2Dual"
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 第一个 2x2 双设备 widget 上桌:启动心跳闹钟(冻结进程只有它能叫醒)
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
                    val address1 = intent.getStringExtra("device_address_1")
                    val address2 = intent.getStringExtra("device_address_2")
                    WidgetHelper.getPrefs(context).edit()
                        .putString("widget_${widgetId}_1", address1)
                        .putString("widget_${widgetId}_2", address2)
                        .commit()
                    WidgetHelper.saveWidgetType(context, widgetId, "2x2_dual")
                    val manager = AppWidgetManager.getInstance(context)
                    updateWidget(context, manager, widgetId, forceSystem = true)
                    WidgetRefreshScheduler.scheduleNext(context)
                }
            }
            // 周期心跳:强制读系统缓存,值没变就跳过重绘
            WidgetHelper.ACTION_WIDGET_TICK -> {
                val manager = AppWidgetManager.getInstance(context)
                val skip = intent.getBooleanExtra(WidgetHelper.EXTRA_SKIP_UNCHANGED, true)
                for (id in manager.getAppWidgetIds(ComponentName(context, Widget2x2Dual::class.java))) {
                    updateWidget(context, manager, id, forceSystem = true, skipIfUnchanged = skip)
                }
            }
            // App 侧主动同步:信任刚写入的缓存
            WidgetHelper.ACTION_APP_REFRESH -> {
                val manager = AppWidgetManager.getInstance(context)
                val skip = intent.getBooleanExtra(WidgetHelper.EXTRA_SKIP_UNCHANGED, true)
                for (id in manager.getAppWidgetIds(ComponentName(context, Widget2x2Dual::class.java))) {
                    updateWidget(context, manager, id, skipIfUnchanged = skip)
                }
            }
            // 手动点刷新:立刻强制重绘,再补一次深度读取
            WidgetHelper.ACTION_REFRESH_NOW -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, Widget2x2Dual::class.java))
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
        val prefs = WidgetHelper.getPrefs(context)
        val addresses = linkedSetOf<String>()
        for (id in ids) {
            prefs.getString("widget_${id}_1", null)?.takeIf { it.isNotBlank() }?.let { addresses.add(it) }
            prefs.getString("widget_${id}_2", null)?.takeIf { it.isNotBlank() }?.let { addresses.add(it) }
        }
        if (addresses.isEmpty()) return
        val pendingResult = goAsync()
        WidgetDeepRefresh.start(context, addresses.toList()) {
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
        val prefs = WidgetHelper.getPrefs(context)
        val address1 = prefs.getString("widget_${widgetId}_1", null)
        val address2 = prefs.getString("widget_${widgetId}_2", null)

        val name1 = address1?.let { WidgetHelper.getDeviceName(context, it) }
        val info1 = address1?.let { WidgetHelper.getBatteryInfo(context, it, forceSystem) }
        val battery1 = info1?.level
        val charging1 = info1?.isCharging == true
        val type1: DeviceType = address1?.let { WidgetHelper.getDeviceType(context, it) } ?: DeviceType.OTHER

        val name2 = address2?.let { WidgetHelper.getDeviceName(context, it) }
        val info2 = address2?.let { WidgetHelper.getBatteryInfo(context, it, forceSystem) }
        val battery2 = info2?.level
        val charging2 = info2?.isCharging == true
        val type2: DeviceType = address2?.let { WidgetHelper.getDeviceType(context, it) } ?: DeviceType.OTHER

        // 渲染签名:内容没变就别去打扰桌面进程
        val signature = "$address1|$name1|$battery1|$charging1|$type1|$address2|$name2|$battery2|$charging2|$type2"
        if (skipIfUnchanged && WidgetHelper.getRenderSignature(context, SCOPE, widgetId) == signature) {
            Log.d(TAG, "skip unchanged render for widget $widgetId")
            return
        }

        val views = RemoteViews(context.packageName, R.layout.widget_2x2_dual)

        // Device 1
        if (address1 != null) {
            // 文字颜色始终按电量等级;白色只用于电量条
            val color1 = WidgetHelper.getBatteryColor(battery1)

            views.setTextViewText(R.id.widget_dual_name1, name1)
            views.setTextViewText(R.id.widget_dual_pct1, if (battery1 != null) "$battery1%" else "?")
            views.setTextColor(R.id.widget_dual_pct1, color1)
            views.setViewVisibility(R.id.widget_dual_charging1, if (charging1) View.VISIBLE else View.GONE)
            WidgetHelper.setRemoteImageView(context, views, R.id.widget_dual_icon1,
                WidgetHelper.getDeviceTypeIconRes(type1))

            if (battery1 != null) {
                WidgetHelper.setBatteryBar(context, views, R.id.widget_dual_bar1, battery1, battery1, charging1)
            } else {
                WidgetHelper.setBatteryBar(context, views, R.id.widget_dual_bar1, 0, null)
            }
        } else {
            views.setTextViewText(R.id.widget_dual_name1, context.getString(R.string.widget_tap_to_select))
            views.setTextViewText(R.id.widget_dual_pct1, "--")
            views.setViewVisibility(R.id.widget_dual_charging1, View.GONE)
        }

        // Device 2
        if (address2 != null) {
            // 文字颜色始终按电量等级;白色只用于电量条
            val color2 = WidgetHelper.getBatteryColor(battery2)

            views.setTextViewText(R.id.widget_dual_name2, name2)
            views.setTextViewText(R.id.widget_dual_pct2, if (battery2 != null) "$battery2%" else "?")
            views.setTextColor(R.id.widget_dual_pct2, color2)
            views.setViewVisibility(R.id.widget_dual_charging2, if (charging2) View.VISIBLE else View.GONE)
            WidgetHelper.setRemoteImageView(context, views, R.id.widget_dual_icon2,
                WidgetHelper.getDeviceTypeIconRes(type2))

            if (battery2 != null) {
                WidgetHelper.setBatteryBar(context, views, R.id.widget_dual_bar2, battery2, battery2, charging2)
            } else {
                WidgetHelper.setBatteryBar(context, views, R.id.widget_dual_bar2, 0, null)
            }
        } else {
            views.setTextViewText(R.id.widget_dual_name2, context.getString(R.string.widget_tap_to_select))
            views.setTextViewText(R.id.widget_dual_pct2, "--")
            views.setViewVisibility(R.id.widget_dual_charging2, View.GONE)
        }

        // 两个设备都配齐了 refresh 按钮才有意义
        if (!address1.isNullOrBlank() && !address2.isNullOrBlank()) {
            views.setViewVisibility(R.id.widget_refresh_btn, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_refresh_btn, View.GONE)
        }

        // Click: select devices if not configured, otherwise open app
        val clickIntent = if (address1.isNullOrBlank() || address2.isNullOrBlank()) {
            Intent(context, WidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra("widget_mode", "2x2_dual")
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
        views.setOnClickPendingIntent(R.id.widget_2x2_dual_root, pendingIntent)

        // 右上角 refresh 按钮:发广播给本 provider,触发强制本地刷新
        if (!address1.isNullOrBlank() && !address2.isNullOrBlank()) {
            val refreshIntent = Intent(context, Widget2x2Dual::class.java).apply {
                action = WidgetHelper.ACTION_REFRESH_NOW
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                widgetId + 3000, // 用偏移量避免和根 click 的 requestCode 冲突
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)
        }

        manager.updateAppWidget(widgetId, views)
        WidgetHelper.setRenderSignature(context, SCOPE, widgetId, signature)
    }
}
