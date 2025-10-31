package com.leohao.android.alistlite.util;

import android.content.Context;
import android.content.SharedPreferences;

import static com.leohao.android.alistlite.AlistLiteApplication.applicationContext;

/**
 * 持久化存储工具类
 *
 * @author LeoHao
 */
public class SharedDataHelper {
    private static SharedDataHelper instance = null;
    private final SharedPreferences sharedMap;

    private SharedDataHelper(Context context) {
        sharedMap = context.getSharedPreferences(Constants.ANDROID_SHARED_DATA_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public synchronized static SharedDataHelper getInstance() {
        if (instance == null) {
            instance = new SharedDataHelper(applicationContext);
        }
        return instance;
    }

    public String getStringShareData(String key) {
        return sharedMap.getString(key, null);
    }

    public boolean getBoolShareData(String key, boolean defaultValue) {
        return sharedMap.getBoolean(key, defaultValue);
    }

    public void putSharedData(String key, Object value) {
        if (value instanceof Boolean) {
            sharedMap.edit().putBoolean(key, (Boolean) value).apply();
        } else if (value instanceof Integer || value instanceof Byte) {
            sharedMap.edit().putInt(key, (Integer) value).apply();
        } else if (value instanceof Long) {
            sharedMap.edit().putLong(key, (Long) value).apply();
        } else if (value instanceof Float) {
            sharedMap.edit().putFloat(key, (Float) value).apply();
        } else if (value instanceof String) {
            sharedMap.edit().putString(key, (String) value).apply();
        } else {
            sharedMap.edit().putString(key, value.toString()).apply();
        }
    }

    /**
     * 静态方法：获取布尔值
     *
     * @param key          键
     * @param defaultValue 默认值
     * @return 布尔值
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        return getInstance().getBoolShareData(key, defaultValue);
    }

    /**
     * 静态方法：保存布尔值
     *
     * @param key   键
     * @param value 值
     */
    public static void putBoolean(String key, boolean value) {
        getInstance().putSharedData(key, value);
    }
}
