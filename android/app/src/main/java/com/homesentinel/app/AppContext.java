package com.homesentinel.app;

import android.app.Application;
import android.content.Context;

/** 持有 Application 级别的 Context，供广播接收器等没有 Context 的场景使用。 */
public class AppContext extends Application {

    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        NotificationHelper.ensureChannels(this);
    }

    public static Context get() {
        return appContext;
    }
}
