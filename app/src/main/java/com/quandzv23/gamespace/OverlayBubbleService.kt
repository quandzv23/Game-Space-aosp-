package com.quandzv23.gamespace

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Thanh vuốt cạnh phải màn hình (edge tab) — vuốt vào trong để mở panel
 * hiệu năng/thông báo, giống thanh công cụ Xiaomi Game Turbo, thay cho
 * bong bóng nổi tròn trước đây.
 */
class OverlayBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var tabView: View? = null
    private var panelView: View? = null
    private var tabParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var screenWidthPx = 0
    private var screenHeightPx = 0
    private var panelWidthPx = 0
    private var isPanelOpen = false

    private var currentProfile: PerfProfileManager.Profile = PerfProfileManager.Profile.BALANCED
    private var targetPackage: String? = null

    // FPS counter — đo frame thật của app game (không phải vsync overlay của chính mình)
    private var fpsView: TextView? = null
    private var fpsParams: WindowManager.LayoutParams? = null
    private var fpsHandler: Handler? = null
    private var fpsRunning = false
    private var lastMaxTimestampNanos = 0L
    private val fpsPollRunnable = object : Runnable {
        override fun run() {
            pollRealFps()
            if (fpsRunning) fpsHandler?.postDelayed(this, 1000)
        }
    }

    // Khóa cảm ứng góc (chống chạm nhầm)
    private var touchLockTopLeft: View? = null
    private var touchLockTopRight: View? = null

    // Tối ưu WiFi — giữ radio ở chế độ độ trễ thấp, tránh tụt ping do tiết kiệm pin
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        screenWidthPx = dm.widthPixels
        screenHeightPx = dm.heightPixels
        panelWidthPx = (272 * dm.density).toInt()
        addEdgeTab()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("target_package")?.let { targetPackage = it }
        return START_STICKY
    }

    private fun addEdgeTab() {
        val view = LayoutInflater.from(this).inflate(R.layout.edge_tab, null)
        tabView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidthPx
            y = 260
        }
        tabParams = params

        var startTouchX = 0f
        var startTouchY = 0f
        var startTabY = 0
        var dragging = false
        var verticalDrag = false
        var horizontalDrag = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    startTabY = tabParams?.y ?: 260
                    dragging = false
                    verticalDrag = false
                    horizontalDrag = false
                    if (!isPanelOpen) ensurePanelAdded()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (!dragging && (abs(dx) > 10 || abs(dy) > 10)) {
                        dragging = true
                        // Quyết định hướng kéo NGAY từ lúc vượt ngưỡng, giữ nguyên suốt cử chỉ
                        if (abs(dy) > abs(dx)) verticalDrag = true else horizontalDrag = true
                    }
                    if (verticalDrag) {
                        // Kéo lên/xuống: di chuyển thanh vuốt dọc theo cạnh màn hình
                        tabParams?.let {
                            it.y = (startTabY + dy).toInt().coerceIn(0, screenHeightPx - 150)
                            tabView?.let { tv -> windowManager.updateViewLayout(tv, it) }
                        }
                    } else if (horizontalDrag) {
                        // Kéo ngang: mở/đóng panel theo tay
                        val openX = screenWidthPx - panelWidthPx
                        val hiddenX = screenWidthPx
                        var newX = (hiddenX + dx).toInt()
                        if (newX < openX) newX = openX
                        if (newX > hiddenX) newX = hiddenX
                        panelParams?.let {
                            it.x = newX
                            panelView?.let { pv -> windowManager.updateViewLayout(pv, it) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - startTouchX
                    when {
                        verticalDrag -> { /* đã di chuyển xong, giữ nguyên vị trí mới */ }
                        horizontalDrag -> {
                            val openedEnough = dx < -panelWidthPx / 2f
                            if (openedEnough) snapPanelOpen() else snapPanelClosed()
                        }
                        else -> togglePanel() // chạm nhẹ không kéo: bật/tắt panel
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
    }

    private fun ensurePanelAdded() {
        if (panelView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.sidebar_panel, null)
        panelView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidthPx
            y = 200
        }
        panelParams = params

        view.findViewById<View>(R.id.btn_close_panel).setOnClickListener { snapPanelClosed() }
        view.findViewById<View>(R.id.row_dnd).setOnClickListener { toggleDnd(view) }
        view.findViewById<View>(R.id.btn_balanced).setOnClickListener {
            applyProfile(PerfProfileManager.Profile.BALANCED, view)
        }
        view.findViewById<View>(R.id.btn_perf).setOnClickListener {
            applyProfile(PerfProfileManager.Profile.PERFORMANCE, view)
        }
        view.findViewById<View>(R.id.btn_battery).setOnClickListener {
            applyProfile(PerfProfileManager.Profile.BATTERY_SAVER, view)
        }

        setupToggleRow(
            view.findViewById(R.id.row_fps),
            view.findViewById(R.id.fps_state),
            SettingsStore.isFpsEnabled(this)
        ) { enabled ->
            SettingsStore.setFpsEnabled(this, enabled)
            if (enabled) showFpsOverlay() else hideFpsOverlay()
        }

        setupToggleRow(
            view.findViewById(R.id.row_touchlock),
            view.findViewById(R.id.touchlock_state),
            SettingsStore.isTouchLockEnabled(this)
        ) { enabled ->
            SettingsStore.setTouchLockEnabled(this, enabled)
            if (enabled) showTouchLock() else hideTouchLock()
        }

        setupToggleRow(
            view.findViewById(R.id.row_callblock),
            view.findViewById(R.id.callblock_state),
            SettingsStore.isCallBlockEnabled(this)
        ) { enabled ->
            SettingsStore.setCallBlockEnabled(this, enabled)
        }

        setupToggleRow(
            view.findViewById(R.id.row_wifi),
            view.findViewById(R.id.wifi_state),
            SettingsStore.isWifiOptimizeEnabled(this)
        ) { enabled ->
            SettingsStore.setWifiOptimizeEnabled(this, enabled)
            if (enabled) enableWifiOptimization() else disableWifiOptimization()
        }

        val quickAppsScroll = view.findViewById<View>(R.id.quick_apps_scroll)
        quickAppsScroll.visibility = if (SettingsStore.isMultitaskRowVisible(this)) View.VISIBLE else View.GONE
        setupToggleRow(
            view.findViewById(R.id.row_multitask),
            view.findViewById(R.id.multitask_state),
            SettingsStore.isMultitaskRowVisible(this)
        ) { visible ->
            SettingsStore.setMultitaskRowVisible(this, visible)
            quickAppsScroll.visibility = if (visible) View.VISIBLE else View.GONE
        }

        populateQuickApps(view)

        // Áp dụng lại trạng thái tiện ích đã lưu mỗi lần panel/overlay được tạo lại
        if (SettingsStore.isFpsEnabled(this)) showFpsOverlay()
        if (SettingsStore.isTouchLockEnabled(this)) showTouchLock()
        if (SettingsStore.isWifiOptimizeEnabled(this)) enableWifiOptimization()

        updateProfileHighlight(view)
        windowManager.addView(view, params)
    }

    private fun togglePanel() {
        if (isPanelOpen) snapPanelClosed() else snapPanelOpen()
    }

    private fun snapPanelOpen() {
        ensurePanelAdded()
        val targetX = screenWidthPx - panelWidthPx
        animatePanelX(targetX) { isPanelOpen = true }
    }

    private fun snapPanelClosed() {
        val pv = panelView ?: return
        animatePanelX(screenWidthPx) {
            isPanelOpen = false
            try { windowManager.removeView(pv) } catch (e: Exception) { }
            panelView = null
            panelParams = null
        }
    }

    private fun animatePanelX(targetX: Int, onDone: () -> Unit) {
        val params = panelParams ?: return
        val pv = panelView ?: return
        val startX = params.x
        ValueAnimator.ofInt(startX, targetX).apply {
            duration = 220
            addUpdateListener {
                params.x = it.animatedValue as Int
                try { windowManager.updateViewLayout(pv, params) } catch (e: Exception) { }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onDone()
                }
            })
            start()
        }
    }

    private fun toggleDnd(panel: View) {
        val nm = getSystemService(NotificationManager::class.java)
        val stateLabel = panel.findViewById<TextView>(R.id.dnd_state)
        if (!nm.isNotificationPolicyAccessGranted) {
            Toast.makeText(this, "Chưa cấp quyền truy cập Không làm phiền", Toast.LENGTH_SHORT).show()
            return
        }
        val newFilter = if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
            NotificationManager.INTERRUPTION_FILTER_NONE
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        nm.setInterruptionFilter(newFilter)
        val isOn = newFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        stateLabel.text = if (isOn) "Bật" else "Tắt"
        stateLabel.setTextColor(
            if (isOn) resources.getColor(R.color.accent_amber, theme)
            else resources.getColor(R.color.text_secondary, theme)
        )
    }

    /** Chạy ghi sysfs ở thread nền (tránh treo UI), rồi phản hồi thật lên panel + Toast. */
    private fun applyProfile(profile: PerfProfileManager.Profile, panel: View) {
        val feedback = panel.findViewById<TextView>(R.id.feedback_text)
        val tappedTile = when (profile) {
            PerfProfileManager.Profile.BALANCED -> panel.findViewById<View>(R.id.btn_balanced)
            PerfProfileManager.Profile.PERFORMANCE -> panel.findViewById<View>(R.id.btn_perf)
            PerfProfileManager.Profile.BATTERY_SAVER -> panel.findViewById<View>(R.id.btn_battery)
        }
        feedback.alpha = 1f
        feedback.text = "Đang áp dụng..."
        thread {
            val success = PerfProfileManager.applyGameProfile(profile)
            Handler(Looper.getMainLooper()).post {
                if (success) {
                    currentProfile = profile
                    updateProfileHighlight(panel)
                    playTilePunch(tappedTile)
                    feedback.text = "✓ Đã áp dụng lúc " + timeNow()
                    fadeInFeedback(feedback)
                } else {
                    playTileShake(tappedTile)
                    feedback.text = "✕ Ghi sysfs thất bại — kiểm tra quyền root"
                    fadeInFeedback(feedback)
                }
            }
        }
    }

    /** Ô vừa chọn "nảy" lên nhẹ kèm loé sáng — phản hồi trực quan thay cho Toast chữ. */
    private fun playTilePunch(tile: View) {
        val scaleX = ObjectAnimator.ofFloat(tile, "scaleX", 1f, 1.14f, 1f)
        val scaleY = ObjectAnimator.ofFloat(tile, "scaleY", 1f, 1.14f, 1f)
        android.animation.AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 320
            interpolator = android.view.animation.OvershootInterpolator(2.2f)
            start()
        }
    }

    /** Ghi sysfs thất bại: ô rung ngang báo lỗi trực quan. */
    private fun playTileShake(tile: View) {
        val shake = ObjectAnimator.ofFloat(tile, "translationX", 0f, -10f, 10f, -8f, 8f, -4f, 4f, 0f)
        shake.duration = 380
        shake.start()
    }

    private fun fadeInFeedback(feedback: TextView) {
        feedback.alpha = 0f
        feedback.animate().alpha(1f).setDuration(220).start()
    }

    private fun updateProfileHighlight(panel: View) {
        val tileBalanced = panel.findViewById<View>(R.id.btn_balanced)
        val tilePerf = panel.findViewById<View>(R.id.btn_perf)
        val tileBattery = panel.findViewById<View>(R.id.btn_battery)
        val checkBalanced = panel.findViewById<TextView>(R.id.check_balanced)
        val checkPerf = panel.findViewById<TextView>(R.id.check_perf)
        val checkBattery = panel.findViewById<TextView>(R.id.check_battery)

        checkBalanced.text = if (currentProfile == PerfProfileManager.Profile.BALANCED) "✓" else ""
        checkPerf.text = if (currentProfile == PerfProfileManager.Profile.PERFORMANCE) "✓" else ""
        checkBattery.text = if (currentProfile == PerfProfileManager.Profile.BATTERY_SAVER) "✓" else ""

        tileBalanced.setBackgroundResource(
            if (currentProfile == PerfProfileManager.Profile.BALANCED) R.drawable.tile_bg_active else R.drawable.tile_bg_inactive
        )
        tilePerf.setBackgroundResource(
            if (currentProfile == PerfProfileManager.Profile.PERFORMANCE) R.drawable.tile_bg_active else R.drawable.tile_bg_inactive
        )
        tileBattery.setBackgroundResource(
            if (currentProfile == PerfProfileManager.Profile.BATTERY_SAVER) R.drawable.tile_bg_active else R.drawable.tile_bg_inactive
        )
    }

    private fun profileLabel(profile: PerfProfileManager.Profile): String = when (profile) {
        PerfProfileManager.Profile.PERFORMANCE -> "Hiệu năng cao"
        PerfProfileManager.Profile.BATTERY_SAVER -> "Tiết kiệm pin"
        PerfProfileManager.Profile.BALANCED -> "Cân bằng"
    }

    private fun timeNow(): String {
        val cal = java.util.Calendar.getInstance()
        return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    private fun setupToggleRow(row: View, stateLabel: TextView, initialOn: Boolean, onToggle: (Boolean) -> Unit) {
        var isOn = initialOn
        fun render() {
            stateLabel.text = if (isOn) "Bật" else "Tắt"
            stateLabel.setTextColor(
                if (isOn) resources.getColor(R.color.accent_amber, theme)
                else resources.getColor(R.color.text_secondary, theme)
            )
            row.setBackgroundResource(if (isOn) R.drawable.tile_bg_active else R.drawable.tile_bg_inactive)
        }
        render()
        row.setOnClickListener {
            isOn = !isOn
            render()
            onToggle(isOn)
        }
    }

    private fun showFpsOverlay() {
        if (fpsView != null) return
        val tv = TextView(this).apply {
            text = "-- FPS"
            setTextColor(resources.getColor(R.color.accent_amber, theme))
            textSize = 12f
            setBackgroundResource(R.drawable.panel_row_bg)
            setPadding(16, 8, 16, 8)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 60
        }
        fpsView = tv
        fpsParams = params
        windowManager.addView(tv, params)
        lastMaxTimestampNanos = 0L
        fpsRunning = true
        fpsHandler = Handler(Looper.getMainLooper())
        fpsHandler?.post(fpsPollRunnable)
    }

    private fun hideFpsOverlay() {
        fpsRunning = false
        fpsHandler?.removeCallbacks(fpsPollRunnable)
        fpsHandler = null
        fpsView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        fpsView = null
        fpsParams = null
    }

    /** Đọc FPS thật của đúng app game qua `dumpsys gfxinfo <pkg> framestats` (root) —
     *  đây là API framestats chính thức Android cung cấp để đo hiệu năng render từng app,
     *  khác hẳn cách đếm vsync của overlay (luôn ra khớp tần số quét màn hình, không phản
     *  ánh app đang lag hay không). Chạy ở thread nền vì gọi shell là I/O chặn luồng. */
    private fun pollRealFps() {
        val pkg = targetPackage ?: return
        thread {
            try {
                val result = com.topjohnwu.superuser.Shell.cmd("dumpsys gfxinfo $pkg framestats").exec()
                if (!result.isSuccess) return@thread
                val lines = result.out
                val startIdx = lines.indexOfFirst { it.contains("PROFILEDATA") }
                if (startIdx < 0) return@thread
                // Dòng ngay sau PROFILEDATA đầu tiên là header cột, các dòng sau là số liệu
                val vsyncTimestamps = mutableListOf<Long>()
                for (i in (startIdx + 2) until lines.size) {
                    val line = lines[i]
                    if (line.contains("PROFILEDATA")) break
                    val cols = line.split(",")
                    // Cột thứ 2 (index 1) là Vsync timestamp (nanosecond)
                    if (cols.size > 1) {
                        cols[1].trim().toLongOrNull()?.let { vsyncTimestamps.add(it) }
                    }
                }
                if (vsyncTimestamps.isEmpty()) return@thread
                val maxTs = vsyncTimestamps.max()
                if (maxTs <= lastMaxTimestampNanos) {
                    // Game không vẽ frame mới nào trong 1 giây qua (đứng hình/không ở foreground thật)
                    lastMaxTimestampNanos = maxTs
                    Handler(Looper.getMainLooper()).post { fpsView?.text = "0 FPS" }
                    return@thread
                }
                val oneSecondAgo = maxTs - 1_000_000_000L
                val framesInLastSecond = vsyncTimestamps.count { it in (oneSecondAgo + 1)..maxTs }
                lastMaxTimestampNanos = maxTs
                Handler(Looper.getMainLooper()).post {
                    fpsView?.text = "$framesInLastSecond FPS"
                }
            } catch (e: Exception) {
                // Thiếu quyền root hoặc app không render gì (dumpsys rỗng) — bỏ qua lần đọc này
            }
        }
    }

    /** Overlay chống chạm nhầm 2 góc trên màn hình — chặn thao tác vô tình khi cầm máy chơi game ngang. */
    private fun showTouchLock() {
        if (touchLockTopLeft != null) return
        val size = 90
        val blockingListener = View.OnTouchListener { _, _ -> true } // hấp thụ chạm, không làm gì

        val left = View(this).apply {
            setBackgroundColor(0x22FFB35C)
            setOnTouchListener(blockingListener)
        }
        val leftParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

        val right = View(this).apply {
            setBackgroundColor(0x22FFB35C)
            setOnTouchListener(blockingListener)
        }
        val rightParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 0; y = 0 }

        touchLockTopLeft = left
        touchLockTopRight = right
        windowManager.addView(left, leftParams)
        windowManager.addView(right, rightParams)
    }

    private fun hideTouchLock() {
        touchLockTopLeft?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        touchLockTopRight?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        touchLockTopLeft = null
        touchLockTopRight = null
    }

    /** Dãy icon đa nhiệm nhanh trong panel — bấm để thử mở app dạng cửa sổ nhỏ nổi (freeform),
     *  máy/ROM không hỗ trợ freeform sẽ tự mở toàn màn hình như bình thường. */
    private fun populateQuickApps(panel: View) {
        val row = panel.findViewById<android.widget.LinearLayout>(R.id.quick_apps_row)
        row.removeAllViews()
        val pm = packageManager
        for (pkg in SettingsStore.getQuickApps(this).toList().sorted()) {
            val iconView = LayoutInflater.from(this).inflate(R.layout.item_quick_app, row, false)
            val icon = iconView.findViewById<android.widget.ImageView>(R.id.quick_app_icon)
            try {
                icon.setImageDrawable(pm.getApplicationIcon(pkg))
            } catch (e: Exception) { }
            icon.setOnClickListener { launchQuickApp(pkg) }
            row.addView(iconView)
        }
        if (row.childCount == 0) {
            val empty = TextView(this).apply {
                text = "Chưa có app — thêm trong Qspace"
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 11f
            }
            row.addView(empty)
        }
    }

    private fun launchQuickApp(pkg: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(dm)
            val bounds = android.graphics.Rect(
                dm.widthPixels / 4, dm.heightPixels / 6,
                dm.widthPixels * 3 / 4, dm.heightPixels * 5 / 6
            )
            val options = android.app.ActivityOptions.makeBasic()
            options.launchBounds = bounds
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent, options.toBundle())
        } catch (e: Exception) {
            // Máy/ROM không hỗ trợ launchBounds/freeform — mở bình thường
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
    }

    /** Giữ WiFi radio ở chế độ độ trễ thấp (API chính thức Android dành cho game/VoIP),
     *  tránh việc chip WiFi tự vào chế độ tiết kiệm điện gây tăng ping/giật khi chơi online. */
    private fun enableWifiOptimization() {
        if (wifiLock != null) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val lock = wm.createWifiLock(mode, "qspace:wifi_optimize")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (e: Exception) {
            Toast.makeText(this, "Không bật được tối ưu WiFi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disableWifiOptimization() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    override fun onDestroy() {
        hideFpsOverlay()
        hideTouchLock()
        disableWifiOptimization()
        tabView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        panelView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        tabView = null
        panelView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
