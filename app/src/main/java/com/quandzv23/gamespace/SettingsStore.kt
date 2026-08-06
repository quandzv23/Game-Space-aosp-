package com.quandzv23.gamespace

import android.content.Context

object SettingsStore {
    private const val PREFS = "qspace_settings"
    private const val KEY_TOUCH_LOCK = "touch_lock_enabled"
    private const val KEY_CALL_BLOCK = "call_block_enabled"
    private const val KEY_WIFI_OPT = "wifi_optimize_enabled"
    private const val KEY_TAB_Y = "edge_tab_y_position"
    private const val KEY_QUICK_APPS = "quick_apps"
    private const val KEY_INTRO_VIDEO_URI = "intro_video_uri"
    private const val KEY_INTRO_PLAYED = "intro_played"
    private const val KEY_INTRO_VIDEO_ROTATION = "intro_video_rotation"

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

    fun getTabYPosition(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TAB_Y, 260)

    fun setTabYPosition(context: Context, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_TAB_Y, y).apply()
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

    /** URI (dạng String) của video mp4 dùng làm hiệu ứng lúc mở app. Null = dùng animation mặc định. */
    fun getIntroVideoUri(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INTRO_VIDEO_URI, null)

    fun setIntroVideoUri(context: Context, uri: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_INTRO_VIDEO_URI, uri).apply()
    }

    /**
     * Đã phát animation/video mở app trong "phiên" hiện tại chưa.
     * Dùng SharedPreferences (không phải biến static) để không phụ thuộc vào việc
     * process có còn sống hay không — chỉ reset khi GameWatcherService nhận
     * onTaskRemoved (người dùng vuốt app khỏi Recents), xem GameWatcherService.kt.
     */
    fun getIntroPlayed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_INTRO_PLAYED, false)

    fun setIntroPlayed(context: Context, played: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_INTRO_PLAYED, played).apply()
    }

    /**
     * Góc xoay thủ công (0/90/180/270) cho video mở app — dùng cho trường hợp video
     * có nội dung bị nghiêng sẵn trong file (không có cờ rotation metadata để tự phát
     * hiện được), người dùng tự chỉnh qua nút "Xoay video". Mặc định 0 = không xoay,
     * giữ nguyên hành vi tự động phát hiện dọc/ngang như trước.
     */
    fun getIntroVideoRotation(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_INTRO_VIDEO_ROTATION, 0)

    fun setIntroVideoRotation(context: Context, degrees: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_INTRO_VIDEO_ROTATION, degrees).apply()
    }
}
