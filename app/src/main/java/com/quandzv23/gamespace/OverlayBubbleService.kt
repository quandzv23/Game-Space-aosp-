package com.quandzv23.gamespace

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class OverlayBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var panelExpanded = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addBubble()
    }

    private fun addBubble() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_bubble, null)
        bubbleView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        // Kéo-thả bong bóng
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        val handle = view.findViewById<View>(R.id.bubble_handle)
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - touchX) > 12 ||
                        kotlin.math.abs(event.rawY - touchY) > 12
                    if (!moved) togglePanel(view)
                    true
                }
                else -> false
            }
        }

        view.findViewById<View>(R.id.btn_dnd).setOnClickListener { toggleDnd() }
        view.findViewById<View>(R.id.btn_perf).setOnClickListener {
            PerfProfileManager.applyGameProfile(PerfProfileManager.Profile.PERFORMANCE)
        }
        view.findViewById<View>(R.id.btn_battery).setOnClickListener {
            PerfProfileManager.applyGameProfile(PerfProfileManager.Profile.BATTERY_SAVER)
        }

        windowManager.addView(view, params)
    }

    private fun togglePanel(view: View) {
        panelExpanded = !panelExpanded
        view.findViewById<View>(R.id.bubble_panel).visibility =
            if (panelExpanded) View.VISIBLE else View.GONE
    }

    private fun toggleDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        val newFilter = if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
            NotificationManager.INTERRUPTION_FILTER_NONE
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        nm.setInterruptionFilter(newFilter)
    }

    override fun onDestroy() {
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
