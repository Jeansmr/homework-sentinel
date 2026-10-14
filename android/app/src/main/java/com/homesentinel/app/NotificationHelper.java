package com.homesentinel.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 统一负责通知渠道与通知的发出。
 *
 * 这里刻意只用 Android 平台自带的 API（不引 androidx），
 * 这样整个工程除了 Android Gradle 插件外没有任何第三方依赖，编译快、体积小。
 */
@SuppressWarnings("deprecation")
public final class NotificationHelper {

    /** 作业提醒（重要，有声音和震动） */
    public static final String CHANNEL_REMIND = "hw_remind";
    /** 普通提示 */
    public static final String CHANNEL_NORMAL = "hw_normal";

    private NotificationHelper() {}

    private static NotificationManager nm(Context ctx) {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager m = nm(ctx);
        if (m == null) return;

        if (m.getNotificationChannel(CHANNEL_REMIND) == null) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_REMIND, "作业提醒", NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("作业即将截止、以及到点时的提醒");
            c.enableVibration(true);
            c.setShowBadge(true);
            m.createNotificationChannel(c);
        }

        if (m.getNotificationChannel(CHANNEL_NORMAL) == null) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_NORMAL, "一般通知", NotificationManager.IMPORTANCE_DEFAULT);
            c.setDescription("测试通知与状态提示");
            m.createNotificationChannel(c);
        }
    }

    /** 是否已经拿到发通知的权限 */
    public static boolean canPost(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        NotificationManager m = nm(ctx);
        return m == null || m.areNotificationsEnabled();
    }

    private static PendingIntent openAppIntent(Context ctx, int requestCode, String key) {
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (key != null && !key.isEmpty()) open.putExtra(MainActivity.EXTRA_FOCUS_KEY, key);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, requestCode, open, flags);
    }

    /**
     * 弹出一条通知。
     *
     * @param id     通知 id（同一 id 会覆盖旧通知，避免刷屏）
     * @param key    提醒标识，点击后回传给网页层做定位
     * @param urgent 是否走高优先级渠道
     */
    public static void show(Context ctx, int id, String key, String title, String body, boolean urgent) {
        ensureChannels(ctx);
        if (!canPost(ctx)) return;

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, urgent ? CHANNEL_REMIND : CHANNEL_NORMAL)
                : new Notification.Builder(ctx);

        b.setSmallIcon(R.drawable.ic_stat_homework)
         .setContentTitle(title == null ? "作业提醒" : title)
         .setContentText(body == null ? "" : body)
         .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
         .setAutoCancel(true)
         .setWhen(System.currentTimeMillis())
         .setShowWhen(true)
         .setContentIntent(openAppIntent(ctx, id + 1, key));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(urgent ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
        }
        if (urgent) {
            b.setDefaults(Notification.DEFAULT_ALL);
        }

        try {
            NotificationManager m = nm(ctx);
            if (m != null) m.notify(id, b.build());
        } catch (SecurityException ignored) {
            // 用户没给通知权限，静默忽略
        }
    }
}
