package com.quandzv23.gamespace

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
        val content = findViewById<android.view.View>(R.id.main_content)

        content.visibility = android.view.View.INVISIBLE
        content.post {
            val cx = content.width / 2
            val cy = content.height / 2
            val finalRadius = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()

            content.visibility = android.view.View.VISIBLE
            val reveal = android.view.ViewAnimationUtils.createCircularReveal(
                content, cx, cy, 0f, finalRadius
            )
            reveal.duration = 480
            reveal.interpolator = DecelerateInterpolator(1.4f)
            reveal.start()
        }
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
