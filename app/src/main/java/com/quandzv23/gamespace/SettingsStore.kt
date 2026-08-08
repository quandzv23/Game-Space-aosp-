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
    private const val KEY_INTRO_VIDEO_ENABLED = "intro_video_enabled"
    private const val KEY_INTRO_VIDEO_HISTORY = "intro_video_history"

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

    /**
     * Có đang dùng video mở app hay không. false = quay về animation gốc (logo bụp +
     * sóng xung + khép tròn) dù URI video vẫn còn được lưu — bấm "Video: Bật" lại là
     * dùng video ngay, khỏi phải chọn lại file.
     */
    fun isIntroVideoEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_INTRO_VIDEO_ENABLED, true)

    fun setIntroVideoEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_INTRO_VIDEO_ENABLED, enabled).apply()
    }

    /**
     * Danh sách video đã từng thêm (mới nhất ở đầu), mỗi video nhớ riêng góc xoay của nó
     * -> chuyển qua lại giữa các video không bị mất góc xoay đã chỉnh trước đó.
     * Lưu dạng "uri::ROT::độ", mỗi dòng 1 video, tối đa 6 video gần nhất.
     */
    private const val HISTORY_SEPARATOR = "::ROT::"
    private const val HISTORY_MAX = 6

    fun getIntroVideoHistory(context: Context): List<Pair<String, Int>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_INTRO_VIDEO_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { entry ->
            val idx = entry.lastIndexOf(HISTORY_SEPARATOR)
            if (idx < 0) return@mapNotNull null
            val uri = entry.substring(0, idx)
            val deg = entry.substring(idx + HISTORY_SEPARATOR.length).toIntOrNull() ?: 0
            uri to deg
        }
    }

    private fun saveIntroVideoHistory(context: Context, list: List<Pair<String, Int>>) {
        val encoded = list.joinToString("\n") { "${it.first}$HISTORY_SEPARATOR${it.second}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_INTRO_VIDEO_HISTORY, encoded).apply()
    }

    /** Thêm video mới chọn vào đầu danh sách (đưa lên đầu nếu đã có sẵn), giới hạn 6 video gần nhất. */
    fun addIntroVideoToHistory(context: Context, uri: String, rotation: Int) {
        val current = getIntroVideoHistory(context).toMutableList()
        current.removeAll { it.first == uri }
        current.add(0, uri to rotation)
        saveIntroVideoHistory(context, current.take(HISTORY_MAX))
    }

    /** Cập nhật lại góc xoay đã lưu cho 1 video có sẵn trong danh sách, không đổi thứ tự. */
    fun updateIntroVideoRotationInHistory(context: Context, uri: String, rotation: Int) {
        val current = getIntroVideoHistory(context).toMutableList()
        val idx = current.indexOfFirst { it.first == uri }
        if (idx >= 0) {
            current[idx] = uri to rotation
        } else {
            current.add(0, uri to rotation)
        }
        saveIntroVideoHistory(context, current.take(HISTORY_MAX))
    }

}
