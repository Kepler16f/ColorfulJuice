package com.colorfuljuice.bluetoothbattery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.colorfuljuice.bluetoothbattery.MainActivity
import com.colorfuljuice.bluetoothbattery.R

class Widget1x3 : AppWidgetProvider() {

    companion object {
        const val ACTION_CONFIGURE = "com.colorfuljuice.bluetoothbattery.ACTION_CONFIGURE_WIDGET_1X3"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
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
        if (intent.action == ACTION_CONFIGURE) {
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (widgetId != -1) {
                val address = intent.getStringExtra("device_address")
                WidgetHelper.saveDeviceAddress(context, widgetId, address)
                WidgetHelper.saveWidgetType(context, widgetId, "1x3")
                val manager = AppWidgetManager.getInstance(context)
                updateWidget(context, manager, widgetId)
            }
        }
        // Handle refresh: widget 自带的按钮 + App 侧主动广播都走这里
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
            intent.action == WidgetHelper.ACTION_REFRESH_NOW ||
            intent.action == WidgetHelper.ACTION_APP_REFRESH
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Widget1x3::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_1x3)
        val address = WidgetHelper.getDeviceAddress(context, widgetId)

        if (address == null) {
            // Show placeholder
            views.setTextViewText(R.id.widget_device_name, context.getString(R.string.widget_tap_to_select))
            views.setTextViewText(R.id.widget_battery_text, "--")
            views.setViewVisibility(R.id.widget_battery_bar, View.INVISIBLE)
            views.setImageViewResource(R.id.widget_device_icon, R.drawable.ic_device_other)
            // 未选设备时,refresh 按钮无意义,隐藏
            views.setViewVisibility(R.id.widget_refresh_btn, View.GONE)
        } else {
            val name = WidgetHelper.getDeviceName(context, address)
            val battery = WidgetHelper.getBatteryLevel(context, address)
            val type = WidgetHelper.getDeviceType(context, address)
            val color = WidgetHelper.getBatteryColor(battery)

            views.setTextViewText(R.id.widget_device_name, name)
            views.setTextViewText(R.id.widget_battery_text, if (battery != null) "$battery%" else "?")
            views.setTextColor(R.id.widget_battery_text, color)

            WidgetHelper.setRemoteImageView(context, views, R.id.widget_device_icon,
                WidgetHelper.getDeviceTypeIconRes(type))

            if (battery != null) {
                views.setViewVisibility(R.id.widget_battery_bar, View.VISIBLE)
                WidgetHelper.setBatteryBar(context, views, R.id.widget_battery_bar, battery, battery)
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

        // 右上角 refresh 按钮:发广播给本 provider,触发轻量本地刷新
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
    }
}