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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
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
        } else if (!isGame && currentGamePackage != null) {
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
        startService(Intent(this, OverlayBubbleService::class.java))
    }

    private fun hideBubble() {
        if (!bubbleShown) return
        bubbleShown = false
        stopService(Intent(this, OverlayBubbleService::class.java))
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
