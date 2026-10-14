package com.homesentinel.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 提醒注册表：保存所有待触发的提醒，供系统闹钟和「重启后恢复」使用。
 *
 * 数据流：
 *   网页层计算出提醒计划（planReminders）→ 通过 JS 桥 syncReminders(json) 传进来
 *   → 存进 SharedPreferences → 逐个登记到 AlarmManager
 *   → 到点由 ReminderReceiver 弹出通知
 *
 * 这样即使 App 没有打开、甚至被系统清理掉，提醒依然会准时出现。
 */
public final class ReminderStore {

    private static final String TAG = "ReminderStore";
    private static final String PREF = "hw_sentinel_reminders";
    private static final String KEY_ITEMS = "items";

    private ReminderStore() {}

    /** 一条待触发的提醒 */
    public static final class Item {
        public final String key;
        public final String title;
        public final String body;
        public final long at;

        public Item(String key, String title, String body, long at) {
            this.key = key;
            this.title = title;
            this.body = body;
            this.at = at;
        }
    }

    /**
     * 解析网页传过来的 JSON。
     * 入参形如：[{"key":"hw_x@d1","title":"…","body":"…","at":1735689600000}]
     */
    public static List<Item> parse(String json) {
        List<Item> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String key = o.optString("key", "");
                long at = o.optLong("at", 0L);
                if (key.isEmpty() || at <= 0L) continue;
                out.add(new Item(key, o.optString("title", "作业提醒"), o.optString("body", ""), at));
            }
        } catch (Exception e) {
            Log.w(TAG, "解析提醒计划失败: " + e.getMessage());
        }
        return out;
    }

    public static void save(String json) {
        Prefs.get().edit().putString(KEY_ITEMS, json == null ? "[]" : json).apply();
    }

    /** 按 key 找到某条提醒（闹钟到点时用来取标题和正文） */
    public static Item find(String key) {
        if (key == null) return null;
        for (Item it : parse(loadRaw())) {
            if (key.equals(it.key)) return it;
        }
        return null;
    }

    /** 读取上次保存的原始 JSON（重启后交给调度器重新登记） */
    public static String loadRaw() {
        return Prefs.get().getString(KEY_ITEMS, "[]");
    }

    /** 稳定的 32 位请求码，用于 PendingIntent 的区分与撤销 */
    public static int requestCode(String key) {
        return key.hashCode() & 0x7FFFFFFF;
    }

    /** 稳定的通知 id，同一个提醒重复触发时只更新同一条通知 */
    public static int notificationId(String key) {
        return (key.hashCode() & 0x7FFFFFFF) % 100000 + 1;
    }
}
