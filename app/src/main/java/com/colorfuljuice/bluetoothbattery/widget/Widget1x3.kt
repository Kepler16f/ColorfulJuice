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
        // Handle refresh broadcast
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
            intent.action == "com.colorfuljuice.bluetoothbattery.WIDGET_REFRESH") {
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
        }

        // Click: select device if not configured, otherwise open app
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

        manager.updateAppWidget(widgetId, views)
    }
}
