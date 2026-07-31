package com.quandzv23.gamespace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * Foreground service chạy nền, poll app đang hiển thị mỗi giây qua
 * UsageStatsManager (cần quyền "Usage access" cấp thủ công 1 lần trong Cài đặt).
 * Khi app foreground nằm trong GameListStore -> bật profile hiệu năng + bubble.
 * Khi rời game -> khôi phục mặc định + tắt bubble.
 */
class GameWatcherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentGamePackage: String? = null
    private var bubbleShown = false

    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        @Suppress("DEPRECATION")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (state == TelephonyManager.CALL_STATE_RINGING &&
                currentGamePackage != null &&
                SettingsStore.isCallBlockEnabled(this@GameWatcherService)
            ) {
                rejectIncomingCall()
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        PerfProfileManager.captureCurrentAsDefault()
        startForeground(NOTIF_ID, buildNotification())
        handler.post(pollRunnable)
        registerCallListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterCallListener()
        handler.removeCallbacks(pollRunnable)
        if (currentGamePackage != null) {
            PerfProfileManager.restoreDefault()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkForegroundApp() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 2000
        val events = usm.queryEvents(begin, end)
        var lastPackage: String? = null
        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPackage = event.packageName
            }
        }
        if (lastPackage == null || lastPackage == packageName) return

        val isGame = GameListStore.isGame(this, lastPackage)
        if (isGame && lastPackage != currentGamePackage) {
            currentGamePackage = lastPackage
            PerfProfileManager.applyGameProfile(PerfProfileManager.Profile.PERFORMANCE)
            showEnterAnimation()
            showBubble()
        } else if (!isGame && currentGamePackage != null && !SettingsStore.getQuickApps(this).contains(lastPackage)) {
            currentGamePackage = null
            PerfProfileManager.restoreDefault()
            hideBubble()
        }
    }

    private fun showEnterAnimation() {
        val intent = Intent(this, GameEnterSplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        startActivity(intent)
    }

    private fun showBubble() {
        if (bubbleShown) return
        bubbleShown = true
        val intent = Intent(this, OverlayBubbleService::class.java)
        intent.putExtra("target_package", currentGamePackage)
        startService(intent)
    }

    private fun hideBubble() {
        if (!bubbleShown) return
        bubbleShown = false
        stopService(Intent(this, OverlayBubbleService::class.java))
    }

@Suppress("DEPRECATION")
    private fun registerCallListener() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            // Chưa cấp quyền READ_PHONE_STATE — bỏ qua, tính năng chặn cuộc gọi sẽ không hoạt động
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterCallListener() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) { }
    }

    private fun rejectIncomingCall() {
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.endCall()
        } catch (e: SecurityException) {
            // Chưa cấp quyền ANSWER_PHONE_CALLS
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "gamespace_watcher"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Qspace", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("Qspace đang theo dõi")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 42
    }
}
