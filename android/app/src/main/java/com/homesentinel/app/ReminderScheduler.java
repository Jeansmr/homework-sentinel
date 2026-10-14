package com.homesentinel.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把网页层算好的提醒计划登记到系统闹钟。
 *
 * 为什么要走系统闹钟？
 *   网页里的定时器在 App 被关掉后就不跑了；只有系统的 AlarmManager
 *   才能在指定时刻把进程唤醒，弹出一条通知。这是「关掉 App 也能按时提醒」的关键。
 */
public final class ReminderScheduler {

    private static final String TAG = "ReminderScheduler";

    private ReminderScheduler() {}

    /** 已经登记过的提醒：key -> 触发时间，用于跳过重复登记 */
    private static final Map<String, Long> REGISTERED = new HashMap<>();

    /**
     * 用新的计划替换旧计划。
     * 会先撤销所有已登记的闹钟，再重新登记，保证与网页层完全一致。
     */
    public static void rescheduleAll(Context ctx) {
        List<ReminderStore.Item> items = ReminderStore.parse(ReminderStore.loadRaw());
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        long now = System.currentTimeMillis();

        // 1) 撤销旧的
        for (Map.Entry<String, Long> e : REGISTERED.entrySet()) {
            PendingIntent pi = buildIntent(ctx, e.getKey());
            if (pi != null) am.cancel(pi);
        }
        REGISTERED.clear();

        // 2) 登记新的
        int scheduled = 0;
        for (ReminderStore.Item item : items) {
            if (item.at <= now) continue;
            PendingIntent pi = buildIntent(ctx, item.key);
            if (pi == null) continue;
            if (setAlarm(am, item.at, pi)) {
                REGISTERED.put(item.key, item.at);
                scheduled++;
            }
        }
        Log.i(TAG, "已登记 " + scheduled + " 条提醒");
    }

    private static PendingIntent buildIntent(Context ctx, String key) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.setAction(ReminderReceiver.ACTION_REMIND + "." + key);
        i.putExtra(ReminderReceiver.EXTRA_KEY, key);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, ReminderStore.requestCode(key), i, flags);
    }

    /** 登记一个精确闹钟；系统不允许精确闹钟时退化为「尽量准时」。 */
    private static boolean setAlarm(AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return true;
            }
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            return true;
        } catch (SecurityException e) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return true;
            } catch (Exception e2) {
                Log.w(TAG, "登记闹钟失败: " + e2.getMessage());
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "登记闹钟异常: " + e.getMessage());
            return false;
        }
    }

    /** 立刻发一条通知（用于「测试通知」按钮） */
    public static void notifyNow(Context ctx, String title, String body) {
        NotificationHelper.show(ctx, 900001, "test", title, body, false);
    }
}
