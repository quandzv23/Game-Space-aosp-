package com.quandzv23.gamespace

import android.content.Context

object SettingsStore {
    private const val PREFS = "qspace_settings"
    private const val KEY_TOUCH_LOCK = "touch_lock_enabled"
    private const val KEY_CALL_BLOCK = "call_block_enabled"
    private const val KEY_WIFI_OPT = "wifi_optimize_enabled"
    private const val KEY_QUICK_APPS = "quick_apps"

    fun isTouchLockEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TOUCH_LOCK, false)

    fun setTouchLockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_TOUCH_LOCK, enabled).apply()
    }

    fun isCallBlockEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_BLOCK, false)

    fun setCallBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CALL_BLOCK, enabled).apply()
    }

    fun isWifiOptimizeEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WIFI_OPT, false)

    fun setWifiOptimizeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIFI_OPT, enabled).apply()
    }

    fun getQuickApps(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_QUICK_APPS, emptySet()) ?: emptySet()

    fun addQuickApp(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getQuickApps(context).toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_QUICK_APPS, current).apply()
    }

    fun removeQuickApp(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getQuickApps(context).toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_QUICK_APPS, current).apply()
    }
}
