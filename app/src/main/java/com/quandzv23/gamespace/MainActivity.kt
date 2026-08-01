package com.quandzv23.gamespace

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.superuser.Shell
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: GameAdapter
    private var selectedGame: String? = null

    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsRunning = false
    private val statsRunnable = object : Runnable {
        override fun run() {
            refreshTopStats()
            if (statsRunning) statsHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playEntranceAnimation()
        ensurePermissions()
        scanRootAccess()

        val listView = findViewById<RecyclerView>(R.id.game_list)
        listView.layoutManager = LinearLayoutManager(this)
        val games = GameListStore.getGames(this).toList().sorted()
        adapter = GameAdapter(
            this, packageManager, games,
            onRemove = { pkg ->
                GameListStore.removeGame(this, pkg)
                refreshList()
            },
            onSelect = { pkg -> selectGame(pkg) }
        )
        listView.adapter = adapter
        if (games.isNotEmpty()) selectGame(games.first())

        findViewById<TextView>(R.id.btn_add_game).setOnClickListener {
            showInstalledAppsPicker { pkg ->
                GameListStore.addGame(this, pkg)
                refreshList()
                if (selectedGame == null) selectGame(pkg)
            }
        }

        findViewById<TextView>(R.id.btn_start_game).setOnClickListener {
            val pkg = selectedGame
            if (pkg == null) {
                Toast.makeText(this, "Chưa chọn game nào", Toast.LENGTH_SHORT).show()
            } else {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) startActivity(launchIntent)
                else Toast.makeText(this, "Không mở được app này", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.root_status_action).setOnClickListener {
            scanRootAccess()
        }

        refreshQuickApps()
        findViewById<TextView>(R.id.btn_add_quick_app).setOnClickListener {
            showInstalledAppsPicker { pkg ->
                SettingsStore.addQuickApp(this, pkg)
                refreshQuickApps()
            }
        }

        if (intent?.getBooleanExtra("open_add_quick_app", false) == true) {
            showInstalledAppsPicker { pkg ->
                SettingsStore.addQuickApp(this, pkg)
                refreshQuickApps()
            }
        }

        val switch = findViewById<SwitchCompat>(R.id.switch_service)
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startForegroundService(Intent(this, GameWatcherService::class.java))
                Toast.makeText(this, "Qspace đang chạy nền", Toast.LENGTH_SHORT).show()
            } else {
                stopService(Intent(this, GameWatcherService::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        statsRunning = true
        statsHandler.post(statsRunnable)
    }

    override fun onPause() {
        super.onPause()
        statsRunning = false
        statsHandler.removeCallbacks(statsRunnable)
    }

    /** Đưa 1 game lên khung showcase trung tâm. */
    private fun selectGame(pkg: String) {
        selectedGame = pkg
        val icon = findViewById<ImageView>(R.id.showcase_icon)
        val backdrop = findViewById<ImageView>(R.id.showcase_backdrop)
        val name = findViewById<TextView>(R.id.showcase_name)
        try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            val drawable = packageManager.getApplicationIcon(appInfo)
            icon.setImageDrawable(drawable)
            backdrop.setImageDrawable(drawable)
            name.text = packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            icon.setImageDrawable(null)
            backdrop.setImageDrawable(null)
            name.text = pkg
        }
    }

    /** Đọc pin/CPU%/GPU% thật, hiện ở thanh trên cùng — chỉ chạy khi màn hình đang hiển thị. */
    private fun refreshTopStats() {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            findViewById<TextView>(R.id.stat_battery).text = "🔋 $level%"
        } catch (e: Exception) { }

        thread {
            val cpu = PerfProfileManager.readCpuUsagePercent()
            val gpu = PerfProfileManager.readGpuUsagePercent()
            runOnUiThread {
                findViewById<TextView>(R.id.stat_cpu).text = if (cpu >= 0) "$cpu%" else "--%"
                findViewById<TextView>(R.id.stat_gpu).text = if (gpu >= 0) "$gpu%" else "--%"
            }
        }
    }

    /** Animation nhẹ lúc vừa mở app: header + card trượt lên và mờ dần vào. */
    private fun playEntranceAnimation() {
        val overlay = findViewById<android.view.View>(R.id.open_anim_overlay)
        val badge = findViewById<android.view.View>(R.id.open_logo_badge)
        val title = findViewById<android.view.View>(R.id.open_logo_title)
        val rings = listOf(
            findViewById<android.view.View>(R.id.open_ring1),
            findViewById<android.view.View>(R.id.open_ring2),
            findViewById<android.view.View>(R.id.open_ring3)
        )

        // Logo "bụp" vào dứt khoát: từ to hơn 1.3x co lại 1x, không bounce
        val badgeScaleX = ObjectAnimator.ofFloat(badge, "scaleX", 1.3f, 1f)
        val badgeScaleY = ObjectAnimator.ofFloat(badge, "scaleY", 1.3f, 1f)
        val badgeAlpha = ObjectAnimator.ofFloat(badge, "alpha", 0f, 1f)
        val badgeSet = AnimatorSet().apply {
            playTogether(badgeScaleX, badgeScaleY, badgeAlpha)
            duration = 220
            interpolator = DecelerateInterpolator(2.2f)
        }

        val titleAlpha = ObjectAnimator.ofFloat(title, "alpha", 0f, 1f).apply { duration = 200 }

        // 3 vòng sóng xung bung ra lệch nhịp quanh logo
        val ringAnims = rings.mapIndexed { index, ring ->
            val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 0.3f, 2.6f)
            val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 0.3f, 2.6f)
            val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.7f, 0f)
            AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                duration = 620
                startDelay = 100L + index * 150L
                interpolator = DecelerateInterpolator()
            }
        }

        val masterSet = AnimatorSet()
        val allAnims = mutableListOf<android.animation.Animator>(badgeSet, titleAlpha)
        allAnims.addAll(ringAnims)
        masterSet.playTogether(allAnims)
        masterSet.start()

        // Sau khi hiệu ứng chạy xong, khép tròn (iris) lộ giao diện thật ra
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            revealRealUi(overlay)
        }, 850)
    }

    private fun revealRealUi(overlay: android.view.View) {
        val cx = overlay.width / 2
        val cy = overlay.height / 2
        val startRadius = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val anim = android.view.ViewAnimationUtils.createCircularReveal(overlay, cx, cy, startRadius, 0f)
        anim.duration = 360
        anim.interpolator = android.view.animation.AccelerateInterpolator()
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                overlay.visibility = android.view.View.GONE
            }
        })
        anim.start()
    }

    /** Quét thật xem app có quyền root dùng được không, chạy ở thread nền vì Shell.getShell() có thể chặn. */
    private fun scanRootAccess() {
        val icon = findViewById<TextView>(R.id.root_status_icon)
        val title = findViewById<TextView>(R.id.root_status_title)

        title.text = "Đang quét root..."
        icon.text = "?"

        thread {
            val granted = PerfProfileManager.hasRootAccess()
            runOnUiThread {
                if (granted) {
                    icon.text = "✓"
                    title.text = "Đã cấp quyền root"
                } else {
                    icon.text = "✕"
                    title.text = "Chưa có quyền root"
                }
            }
        }
    }

    private fun ensurePermissions() {
        Shell.getShell()

        if (!hasUsageAccess()) {
            Toast.makeText(this, "Cần cấp quyền truy cập dữ liệu sử dụng", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        val missingPhonePerms = listOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.ANSWER_PHONE_CALLS
        ).filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPhonePerms.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missingPhonePerms.toTypedArray(), 101)
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun refreshList() {
        adapter.submit(GameListStore.getGames(this).toList().sorted())
    }

    private fun showInstalledAppsPicker(onSelected: (String) -> Unit) {
        val pm = packageManager
        val apps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.picker_list)
        val searchBox = dialogView.findViewById<android.widget.EditText>(R.id.picker_search)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val pickerAdapter = AppPickerAdapter(pm, apps) { app ->
            onSelected(app.packageName)
            dialog.dismiss()
        }
        recyclerView.adapter = pickerAdapter

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                pickerAdapter.filter(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        dialog.show()
    }

    private fun refreshQuickApps() {
        val row = findViewById<android.widget.LinearLayout>(R.id.quick_apps_row)
        row.removeAllViews()
        val pm = packageManager
        for (pkg in SettingsStore.getQuickApps(this).toList().sorted()) {
            val itemView = layoutInflater.inflate(R.layout.item_quick_app, row, false)
            val icon = itemView.findViewById<android.widget.ImageView>(R.id.quick_app_icon)
            val name = itemView.findViewById<TextView>(R.id.quick_app_name)
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                icon.setImageDrawable(pm.getApplicationIcon(appInfo))
                name.text = pm.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                name.text = pkg
            }
            itemView.setOnLongClickListener {
                SettingsStore.removeQuickApp(this, pkg)
                refreshQuickApps()
                true
            }
            row.addView(itemView)
        }
    }
}
