package com.quandzv23.gamespace

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playEntranceAnimation()
        ensurePermissions()
        scanRootAccess()

        val listView = findViewById<RecyclerView>(R.id.game_list)
        var isCardMode = true
        listView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        adapter = GameAdapter(this, packageManager, GameListStore.getGames(this).toList().sorted(), isCardMode) { pkg ->
            GameListStore.removeGame(this, pkg)
            refreshList()
        }
        listView.adapter = adapter

        findViewById<TextView>(R.id.btn_toggle_view).setOnClickListener { toggleBtn ->
            isCardMode = !isCardMode
            listView.layoutManager = if (isCardMode) {
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            } else {
                LinearLayoutManager(this)
            }
            adapter.setCardMode(isCardMode)
            (toggleBtn as TextView).text = if (isCardMode) "☰ List" else "▦ Thẻ"
        }

        findViewById<TextView>(R.id.btn_add_game).setOnClickListener {
            showInstalledAppsPicker { pkg ->
                GameListStore.addGame(this, pkg)
                refreshList()
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

        val statusText = findViewById<TextView>(R.id.status_text)
        val switch = findViewById<SwitchCompat>(R.id.switch_service)
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startForegroundService(Intent(this, GameWatcherService::class.java))
                statusText.text = "Đang theo dõi"
                Toast.makeText(this, "Qspace đang chạy nền", Toast.LENGTH_SHORT).show()
            } else {
                stopService(Intent(this, GameWatcherService::class.java))
                statusText.text = "Chưa bật theo dõi"
            }
        }
    }

    /** Animation nhẹ lúc vừa mở app: header + card trượt lên và mờ dần vào. */
    private fun playEntranceAnimation() {
        val header = findViewById<android.view.View>(R.id.header_container)
        val content = findViewById<android.view.View>(R.id.main_content)

        header.alpha = 0f
        header.translationY = 40f
        content.alpha = 0f

        val headerAlpha = ObjectAnimator.ofFloat(header, "alpha", 0f, 1f)
        val headerSlide = ObjectAnimator.ofFloat(header, "translationY", 40f, 0f)
        val contentAlpha = ObjectAnimator.ofFloat(content, "alpha", 0f, 1f)

        AnimatorSet().apply {
            playTogether(headerAlpha, headerSlide, contentAlpha)
            duration = 380
            interpolator = DecelerateInterpolator(1.6f)
            start()
        }
    }

    /** Quét thật xem app có quyền root dùng được không, chạy ở thread nền vì Shell.getShell() có thể chặn. */
    private fun scanRootAccess() {
        val icon = findViewById<TextView>(R.id.root_status_icon)
        val title = findViewById<TextView>(R.id.root_status_title)
        val subtitle = findViewById<TextView>(R.id.root_status_subtitle)

        title.text = "Đang quét quyền root..."
        icon.text = "?"

        thread {
            val granted = PerfProfileManager.hasRootAccess()
            runOnUiThread {
                if (granted) {
                    icon.text = "✓"
                    title.text = "Đã cấp quyền root"
                    subtitle.text = "Có thể đổi hiệu năng CPU/GPU khi vào game"
                } else {
                    icon.text = "✕"
                    title.text = "Chưa có quyền root"
                    subtitle.text = "Mở KernelSU Manager, cấp Superuser cho Qspace"
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

        val adapter = AppPickerAdapter(pm, apps) { app ->
            onSelected(app.packageName)
            dialog.dismiss()
        }
        recyclerView.adapter = adapter

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter.filter(s?.toString() ?: "")
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
