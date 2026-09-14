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

class Widget2x2Dual : AppWidgetProvider() {

    companion object {
        const val ACTION_CONFIGURE = "com.colorfuljuice.bluetoothbattery.ACTION_CONFIGURE_WIDGET_2X2_DUAL"
        const val ACTION_REFRESH = "com.colorfuljuice.bluetoothbattery.WIDGET_REFRESH_2X2_DUAL"
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
                val address1 = intent.getStringExtra("device_address_1")
                val address2 = intent.getStringExtra("device_address_2")
                WidgetHelper.getPrefs(context).edit()
                    .putString("widget_${widgetId}_1", address1)
                    .putString("widget_${widgetId}_2", address2)
                    .apply()
                WidgetHelper.saveWidgetType(context, widgetId, "2x2_dual")
                val manager = AppWidgetManager.getInstance(context)
                updateWidget(context, manager, widgetId)
            }
        }
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
            intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Widget2x2Dual::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_2x2_dual)
        val prefs = WidgetHelper.getPrefs(context)
        val address1 = prefs.getString("widget_${widgetId}_1", null)
        val address2 = prefs.getString("widget_${widgetId}_2", null)

        // Device 1
        if (address1 != null) {
            val name1 = WidgetHelper.getDeviceName(context, address1)
            val battery1 = WidgetHelper.getBatteryLevel(context, address1)
            val type1 = WidgetHelper.getDeviceType(context, address1)
            val color1 = WidgetHelper.getBatteryColor(battery1)

            views.setTextViewText(R.id.widget_dual_name1, name1)
            views.setTextViewText(R.id.widget_dual_pct1, if (battery1 != null) "$battery1%" else "?")
            views.setTextColor(R.id.widget_dual_pct1, color1)
            WidgetHelper.setRemoteImageView(context, views, R.id.widget_dual_icon1,
                WidgetHelper.getDeviceTypeIconRes(type1))

            if (battery1 != null) {
                WidgetHelper.setBatteryBar(context, views, R.id.widget_dual_bar1, battery1, battery1)
            } else {
                WidgetHelper.setBatteryBar(context, views, R.id.widget_dual_bar1, 0, null)
            }
        } else {
            views.setTextViewText(R.id.widget_dual_name1, context.getString(R.string.widget_tap_to_select))
            views.setTextViewText(R.id.widget_dual_pct1, "--")
        }

        // Device 2
        if (address2 != null) {
            val name2 = WidgetHelper.getDeviceName(context, address2)
            val battery2 = WidgetHelper.getBatteryLevel(context, address2)
            val type2 = WidgetHelper.getDeviceType(context, address2)
            val color2 = WidgetHelper.getBatteryColor(battery2)

            views.setTextViewText(R.id.widget_dual_name2, name2)
            views.setTextViewText(R.id.widget_dual_pct2, if (battery2 != null) "$battery2%" else "?")
            views.setTextColor(R.id.widget_dual_pct2, color2)
            WidgetHelper.setRemoteImageView(context, views, R.id.widget_dual_icon2,
                WidgetHelper.getDeviceTypeIconRes(type2))

            if (battery2 != null) {
                WidgetHelper.setBatteryBar(context, views, R.id.widget_dual_bar2, battery2, battery2)
            } else {
                WidgetHelper.setBatteryBar(context, views, R.id.widget_dual_bar2, 0, null)
            }
        } else {
            views.setTextViewText(R.id.widget_dual_name2, context.getString(R.string.widget_tap_to_select))
            views.setTextViewText(R.id.widget_dual_pct2, "--")
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

        manager.updateAppWidget(widgetId, views)
    }
}
