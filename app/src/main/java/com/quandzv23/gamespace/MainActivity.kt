package com.quandzv23.gamespace

import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.topjohnwu.superuser.Shell

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ensurePermissions()

        val listView = findViewById<ListView>(R.id.game_list)
        refreshList(listView)

        findViewById<android.view.View>(R.id.btn_add_game).setOnClickListener {
            showInstalledAppsPicker(listView)
        }

        findViewById<android.view.View>(R.id.btn_start_service).setOnClickListener {
            startForegroundService(Intent(this, GameWatcherService::class.java))
            Toast.makeText(this, "Game Space đang chạy nền", Toast.LENGTH_SHORT).show()
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

    private fun refreshList(listView: ListView) {
        val games = GameListStore.getGames(this).toList().sorted()
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, games)
        listView.setOnItemLongClickListener { _, _, position, _ ->
            GameListStore.removeGame(this, games[position])
            refreshList(listView)
            true
        }
    }

    private fun showInstalledAppsPicker(listView: ListView) {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val labels = apps.map { "${pm.getApplicationLabel(it)}  (${it.packageName})" }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Chọn app để thêm vào Game Space")
            .setItems(labels) { _, which ->
                GameListStore.addGame(this, apps[which].packageName)
                refreshList(listView)
            }
            .show()
    }
}
