package com.colorfuljuice.bluetoothbattery.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 桌面 widget 的定时刷新闹钟。
 *
 * 为什么不用进程内协程/Handler:开了"墓碑"(缓存进程冻结)之后,App 进程里的一切定时器
 * 都会停摆 —— 协程不跑、Handler 不派发,widget 就永远停在冻结前那一帧。
 * AlarmManager 的闹钟由 system_server 保管,到点由系统把进程重新拉起来投递,
 * 因此是冻结场景下唯一可靠的节拍来源。
 *
 * 每一跳只做两件事:反射读一遍系统蓝牙电量 + 值没变就跳过重绘,开销可以忽略。
 */
object WidgetRefreshScheduler {

    private const val TAG = "WidgetRefreshScheduler"
    private const val REQUEST_CODE = 0x5742 // 'W','T'

    /**
     * widget 心跳周期。取 60 秒而不是更长,权衡如下:
     *
     *  - 亮屏使用中:每 60 秒反射读一遍系统蓝牙缓存,单次开销毫秒级,
     *    值没变就跳过重绘,几乎无感;
     *  - 息屏深度 Doze:系统对 `setExactAndAllowWhileIdle` 有每 App 约 9 分钟一次的节流,
     *    写 60 秒实际仍按节流间隔触发,不会造成密集唤醒;
     *  - 关键收益:被节流搁置的那一跳在亮屏退出 Doze 时会被系统立刻补发,
     *    所以"锁屏进 Doze → 再亮屏"后 widget 几十秒内就能拿到新电量,
     *    而不是像旧版 15 分钟周期那样干等一刻钟。
     *
     * 每一跳只做两件事:反射读一遍系统蓝牙电量 + 值没变就跳过重绘,开销可以忽略。
     */
    const val TICK_INTERVAL_MS = 60_000L

    /**
     * 排下一次 tick。自续式(每次触发后由 [WidgetTickReceiver] 再调一次),
     * 这样间隔设置变了、或者某次闹钟丢了,下一跳都能自我修正。
     */
    fun scheduleNext(context: Context) {
        if (!WidgetHelper.hasAnyWidget(context)) {
            cancel(context)
            return
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = tickPendingIntent(context)
        val triggerAt = System.currentTimeMillis() + TICK_INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Android 14+ 起 SCHEDULE_EXACT_ALARM 默认不授予
                throw SecurityException("SCHEDULE_EXACT_ALARM not granted")
            }
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            Log.d(TAG, "scheduled exact tick in ${TICK_INTERVAL_MS}ms")
        } catch (e: SecurityException) {
            // 退化为非精确闹钟:进程照样会被唤醒,只是触发时机有抖动
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                Log.w(TAG, "exact alarm unavailable, scheduled inexact tick instead")
            } catch (e2: Exception) {
                Log.e(TAG, "failed to schedule inexact widget tick", e2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to schedule widget tick", e)
        }
    }

    /** 闹钟还在不在。FLAG_NO_CREATE:不存在时返回 null,而不是新建一个。 */
    fun isScheduled(context: Context): Boolean {
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, WidgetTickReceiver::class.java).setAction(WidgetHelper.ACTION_WIDGET_TICK),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) != null
    }

    /**
     * 闹钟丢了就补排一个(不覆盖已有闹钟的触发时间)。
     * 用于 App 冷启动兜底:用户手动 force-stop、或某些厂商的内存清理会清空 AlarmManager,
     * 而此时 widget 还在桌面上,不补的话心跳就永久断线。
     */
    fun ensureScheduled(context: Context) {
        if (isScheduled(context)) return
        val has = WidgetHelper.hasAnyWidget(context)
        Log.d(TAG, "ensureScheduled: alarmMissing=true, hasWidget=$has")
        if (has) scheduleNext(context) else cancel(context)
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(tickPendingIntent(context))
        Log.d(TAG, "cancelled widget tick")
    }

    private fun tickPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetTickReceiver::class.java)
            .setAction(WidgetHelper.ACTION_WIDGET_TICK)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
