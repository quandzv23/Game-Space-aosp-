package com.quandzv23.gamespace

import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.superuser.Shell

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: GameAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ensurePermissions()

        val listView = findViewById<RecyclerView>(R.id.game_list)
        listView.layoutManager = LinearLayoutManager(this)
        adapter = GameAdapter(packageManager, GameListStore.getGames(this).toList().sorted()) { pkg ->
            GameListStore.removeGame(this, pkg)
            refreshList()
        }
        listView.adapter = adapter

        findViewById<TextView>(R.id.btn_add_game).setOnClickListener {
            showInstalledAppsPicker()
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

    private fun ensurePermissions() {
        // Quyền root qua KernelSU / Magisk — bắt request 1 lần khi mở app
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

    private fun showInstalledAppsPicker() {
        val pm = packageManager
        val apps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val labels = apps.map { "${pm.getApplicationLabel(it)}  (${it.packageName})" }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Chọn app để thêm vào Qspace")
            .setItems(labels) { _, which ->
                GameListStore.addGame(this, apps[which].packageName)
                refreshList()
            }
            .show()
    }
}
