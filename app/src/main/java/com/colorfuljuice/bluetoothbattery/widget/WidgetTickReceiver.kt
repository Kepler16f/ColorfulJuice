package com.colorfuljuice.bluetoothbattery.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * widget 的"心跳"接收器,由 [WidgetRefreshScheduler] 的闹钟、开机和应用升级广播拉起。
 *
 * 走 manifest 注册 + 显式组件广播,进程被冻结时也能由系统唤醒并投递;
 * 收到后强制读一遍系统电量并重绘,然后排下一跳。
 */
class WidgetTickReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "WidgetTickReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetHelper.ACTION_WIDGET_TICK -> {
                WidgetHelper.refreshAllWidgets(context, force = true)
                WidgetRefreshScheduler.scheduleNext(context)
            }
            // 重启 / 应用升级后 AlarmManager 里的闹钟会丢,这里补一个,
            // 并且无条件重绘一次(签名是持久化的,但布局/样式可能已经随版本变了)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "rescheduling widget tick after ${intent.action}")
                WidgetRefreshScheduler.scheduleNext(context)
                WidgetHelper.refreshAllWidgets(context, force = true, skipUnchanged = false)
            }
        }
    }
}
