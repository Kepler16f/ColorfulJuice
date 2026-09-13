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
import com.colorfuljuice.bluetoothbattery.utils.DeviceType

class Widget2x2Single : AppWidgetProvider() {

    companion object {
        const val ACTION_CONFIGURE = "com.colorfuljuice.bluetoothbattery.ACTION_CONFIGURE_WIDGET_2X2_SINGLE"
        const val ACTION_REFRESH = "com.colorfuljuice.bluetoothbattery.WIDGET_REFRESH_2X2_SINGLE"
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
                WidgetHelper.saveWidgetType(context, widgetId, "2x2_single")
                val manager = AppWidgetManager.getInstance(context)
                updateWidget(context, manager, widgetId)
            }
        }
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
            intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Widget2x2Single::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_2x2_single)
        val address = WidgetHelper.getDeviceAddress(context, widgetId)

        if (address == null) {
            views.setTextViewText(R.id.widget_ring_device_name, context.getString(R.string.widget_tap_to_select))
            views.setTextViewText(R.id.widget_ring_battery_pct, "--")
            views.setTextViewText(R.id.widget_fallback_pct, "--")
            views.setImageViewResource(R.id.widget_ring_icon, R.drawable.ic_device_other)
            views.setImageViewResource(R.id.widget_ring_bg, R.drawable.widget_ring_battery_unknown)
            views.setInt(R.id.widget_ring_bg, "setImageLevel", 0)
        } else {
            val name = WidgetHelper.getDeviceName(context, address)
            val battery = WidgetHelper.getBatteryLevel(context, address)
            val type = WidgetHelper.getDeviceType(context, address)
            val color = WidgetHelper.getBatteryColor(battery)

            // Ring section
            views.setTextViewText(R.id.widget_ring_device_name, name)
            views.setTextViewText(R.id.widget_ring_battery_pct, if (battery != null) "$battery%" else "?")
            views.setTextColor(R.id.widget_ring_battery_pct, color)

            // Ring color based on battery level
            val ringRes = when {
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
            if (battery != null) {
                views.setProgressBar(R.id.widget_fallback_bar, 100, battery, false)
                views.setTextViewText(R.id.widget_fallback_pct, "$battery%")
                views.setTextColor(R.id.widget_fallback_pct, color)
            }

            // Show sub-battery rows for headphones
            if (type == DeviceType.HEADPHONE) {
                views.setViewVisibility(R.id.widget_sub_batteries, View.GONE)
            }
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

        manager.updateAppWidget(widgetId, views)
    }
}
