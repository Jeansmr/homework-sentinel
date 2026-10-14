package com.homesentinel.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 极简 SharedPreferences 封装，避免到处写 getSharedPreferences。 */
public final class Prefs {

    private static final String NAME = "hw_sentinel";
    private static volatile SharedPreferences instance;

    private Prefs() {}

    public static SharedPreferences get() {
        return get(null);
    }

    public static SharedPreferences get(Context ctx) {
        if (instance == null) {
            synchronized (Prefs.class) {
                if (instance == null) {
                    Context c = (ctx != null) ? ctx.getApplicationContext() : AppContext.get();
                    instance = c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
                }
            }
        }
        return instance;
    }
}
