package com.homesentinel.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 手机重启或 App 更新后，把之前登记的提醒重新登记一遍。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            ReminderScheduler.rescheduleAll(context.getApplicationContext());
        }
    }
}
