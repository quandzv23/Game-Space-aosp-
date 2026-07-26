package com.quandzv23.gamespace

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

    // FPS counter
    private var fpsView: TextView? = null
    private var fpsParams: WindowManager.LayoutParams? = null
    private var frameCount = 0
    private var lastFpsTime = 0L
    private val fpsCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000) {
                fpsView?.text = "$frameCount FPS"
                frameCount = 0
                lastFpsTime = now
            }
            if (fpsView != null) android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // Khóa cảm ứng góc (chống chạm nhầm)
    private var touchLockTopLeft: View? = null
    private var touchLockTopRight: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        screenWidthPx = dm.widthPixels
        screenHeightPx = dm.heightPixels
        panelWidthPx = (248 * dm.density).toInt()
        addEdgeTab()
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
        var longPressMode = false
        val longPressHandler = Handler(Looper.getMainLooper())
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            if (!dragging) {
                longPressMode = true
                longPressTriggered = true
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    startTabY = tabParams?.y ?: 260
                    dragging = false
                    longPressMode = false
                    longPressTriggered = false
                    longPressHandler.postDelayed(longPressRunnable, 400)
                    if (!isPanelOpen) ensurePanelAdded()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (!dragging && (abs(dx) > 16 || abs(dy) > 16)) {
                        dragging = true
                        if (!longPressTriggered) longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    if (longPressMode) {
                        // Nhấn giữ: kéo thanh vuốt lên/xuống theo cạnh màn hình
                        tabParams?.let {
                            it.y = (startTabY + dy).toInt().coerceIn(0, screenHeightPx - 96)
                            tabView?.let { tv -> windowManager.updateViewLayout(tv, it) }
                        }
                    } else if (dragging && abs(dx) > abs(dy)) {
                        // Vuốt ngang: kéo panel theo tay
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
                    longPressHandler.removeCallbacks(longPressRunnable)
                    val dx = event.rawX - startTouchX
                    if (longPressMode) {
                        // Vừa kéo xong, không mở/đóng panel
                    } else if (!dragging) {
                        togglePanel()
                    } else {
                        val openedEnough = dx < -panelWidthPx / 2f
                        if (openedEnough) snapPanelOpen() else snapPanelClosed()
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

        populateQuickApps(view)

        // Áp dụng lại trạng thái tiện ích đã lưu mỗi lần panel/overlay được tạo lại
        if (SettingsStore.isFpsEnabled(this)) showFpsOverlay()
        if (SettingsStore.isTouchLockEnabled(this)) showTouchLock()

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
        feedback.text = "Đang áp dụng..."
        thread {
            val success = PerfProfileManager.applyGameProfile(profile)
            Handler(Looper.getMainLooper()).post {
                if (success) {
                    currentProfile = profile
                    updateProfileHighlight(panel)
                    feedback.text = "✓ Đã áp dụng lúc " + timeNow()
                    Toast.makeText(this, "Đã bật ${profileLabel(profile)}", Toast.LENGTH_SHORT).show()
                } else {
                    feedback.text = "✕ Ghi sysfs thất bại — kiểm tra quyền root"
                    Toast.makeText(this, "Không áp dụng được — thiếu quyền root?", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateProfileHighlight(panel: View) {
        val checkBalanced = panel.findViewById<TextView>(R.id.check_balanced)
        val checkPerf = panel.findViewById<TextView>(R.id.check_perf)
        val checkBattery = panel.findViewById<TextView>(R.id.check_battery)
        checkBalanced.text = if (currentProfile == PerfProfileManager.Profile.BALANCED) "✓" else ""
        checkPerf.text = if (currentProfile == PerfProfileManager.Profile.PERFORMANCE) "✓" else ""
        checkBattery.text = if (currentProfile == PerfProfileManager.Profile.BATTERY_SAVER) "✓" else ""
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
        frameCount = 0
        lastFpsTime = System.currentTimeMillis()
        android.view.Choreographer.getInstance().postFrameCallback(fpsCallback)
    }

    private fun hideFpsOverlay() {
        fpsView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        fpsView = null
        fpsParams = null
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

    override fun onDestroy() {
        hideFpsOverlay()
        hideTouchLock()
        tabView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        panelView?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        tabView = null
        panelView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
