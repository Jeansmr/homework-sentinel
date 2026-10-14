package com.homesentinel.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 闹钟到点时被系统唤醒，负责弹出通知。
 * 这里不依赖 App 是否在前台，所以关掉 App 也能准时提醒。
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_REMIND = "com.homesentinel.app.REMIND";
    public static final String EXTRA_KEY = "key";

    @Override
    public void onReceive(Context context, Intent intent) {
        String key = intent.getStringExtra(EXTRA_KEY);
        if (key == null || key.isEmpty()) return;

        ReminderStore.Item item = ReminderStore.find(key);

        String title = (item != null) ? item.title : "作业提醒";
        String body = (item != null) ? item.body : "有一份作业快到截止时间了";
        boolean urgent = true;

        NotificationHelper.show(
                context.getApplicationContext(),
                ReminderStore.notificationId(key),
                key, title, body, urgent);
    }
}
