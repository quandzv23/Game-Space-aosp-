#!/usr/bin/env bash
set -e

# Script tự động ghi đè 4 file cho tính năng video mở app + gỡ file cũ không dùng nữa
# Chạy trong thư mục gốc repo (nơi có thư mục app/)

rm -f app/src/main/java/com/quandzv23/gamespace/AspectFillVideoView.kt

mkdir -p "app"
cat > app/build.gradle << 'QSPACE_EOF'
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.quandzv23.gamespace'
    compileSdk 34

    defaultConfig {
        applicationId "com.quandzv23.gamespace"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "0.1"
    }

    buildTypes {
        release {
            minifyEnabled false
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding true
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.13.1'
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'com.google.android.material:material:1.12.0'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'androidx.coordinatorlayout:coordinatorlayout:1.2.0'

    // Root shell access — works with KernelSU's su binary (Atlas kernel already has KernelSU)
    implementation 'com.github.topjohnwu.libsu:core:5.2.2'

    // ExoPlayer (Media3) — dùng cho video mở app: xử lý đúng metadata xoay (rotation)
    // của video quay dọc (TikTok, v.v.) mà VideoView cũ đọc sai, và có sẵn chế độ
    // "zoom" (crop kín khung, không méo hình) không cần tự tính toán tay.
    implementation 'androidx.media3:media3-exoplayer:1.4.1'
    implementation 'androidx.media3:media3-ui:1.4.1'
}
QSPACE_EOF

mkdir -p "app/src/main/java/com/quandzv23/gamespace"
cat > app/src/main/java/com/quandzv23/gamespace/MainActivity.kt << 'QSPACE_EOF'
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.superuser.Shell
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        // static -> sống theo vòng đời PROCESS, không theo Activity. Activity có thể bị
        // hủy/tạo lại (bấm back, xoay máy, quay lại từ game...) trong khi process (và
        // GameWatcherService) vẫn đang chạy nền -> những lần vào lại đó KHÔNG phát video/animation
        // mở app nữa. Cờ này chỉ reset về false khi cả process bị hệ thống kill hoàn toàn.
        private var introPlayedThisProcess = false
    }

    private lateinit var adapter: GameAdapter
    private var selectedGame: String? = null
    private var introPlayer: ExoPlayer? = null

    // Chọn file video mp4 từ máy để dùng làm hiệu ứng lúc mở app.
    // Dùng OpenDocument (thay vì GetContent) để xin được quyền truy cập lâu dài (persistable),
    // vì video sẽ được VideoView đọc lại ở mỗi lần mở app sau này, không chỉ lần chọn.
    private val pickIntroVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Một số provider không hỗ trợ persistable permission — vẫn lưu URI,
                // video có thể không phát lại được sau khi khởi động lại máy.
            }
            SettingsStore.setIntroVideoUri(this, uri.toString())
            Toast.makeText(this, "Đã đổi video mở app", Toast.LENGTH_SHORT).show()
        }

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

        if (introPlayedThisProcess) {
            // App (process) đã chạy sẵn từ trước -> vào lại lần này không phát video/animation nữa
            findViewById<android.view.View>(R.id.open_anim_overlay).visibility = android.view.View.GONE
        } else {
            introPlayedThisProcess = true
            playEntranceAnimation()
        }
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
            showGameManagerDialog()
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

        findViewById<TextView>(R.id.btn_change_intro_video).setOnClickListener {
            pickIntroVideoLauncher.launch(arrayOf("video/*"))
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

    override fun onDestroy() {
        super.onDestroy()
        introPlayer?.release()
        introPlayer = null
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

        val introUriString = SettingsStore.getIntroVideoUri(this)
        if (introUriString != null) {
            if (playIntroVideo(overlay, Uri.parse(introUriString))) return
            // Không phát được video (file bị xóa/thu hồi quyền...) -> rơi về animation mặc định
        }

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

    /**
     * Phát video mp4 do người dùng chọn thay cho animation logo mặc định.
     * Dùng ExoPlayer (Media3) thay vì VideoView vì VideoView đọc sai kích thước với
     * video có metadata xoay (video quay dọc như TikTok) -> tính crop sai hướng.
     * ExoPlayer + PlayerView(resize_mode="zoom") xử lý đúng rotation và tự crop kín khung.
     * Trả về true nếu bắt đầu phát được; false nếu URI không dùng được (mất quyền,
     * file bị xóa...) -> rơi về animation mặc định ngay lập tức.
     */
    private fun playIntroVideo(overlay: android.view.View, uri: Uri): Boolean {
        val playerView = findViewById<PlayerView>(R.id.open_intro_video)
        return try {
            val player = ExoPlayer.Builder(this).build()
            introPlayer = player
            playerView.player = player
            playerView.visibility = android.view.View.VISIBLE

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        finishIntroVideo(overlay, playerView, player)
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    finishIntroVideo(overlay, playerView, player)
                }
            })

            player.setMediaItem(MediaItem.fromUri(uri))
            player.playWhenReady = true
            player.prepare()
            true
        } catch (e: Exception) {
            playerView.visibility = android.view.View.GONE
            false
        }
    }

    private fun finishIntroVideo(overlay: android.view.View, playerView: PlayerView, player: ExoPlayer) {
        playerView.visibility = android.view.View.GONE
        player.release()
        if (introPlayer === player) introPlayer = null
        revealRealUi(overlay)
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

    private fun showGameManagerDialog() {
        val pm = packageManager
        val allApps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val dialogView = layoutInflater.inflate(R.layout.dialog_game_manager, null)
        val addedList = dialogView.findViewById<RecyclerView>(R.id.list_added_games)
        val availableList = dialogView.findViewById<RecyclerView>(R.id.list_available_apps)
        val addedLabel = dialogView.findViewById<TextView>(R.id.label_added_count)
        val availableLabel = dialogView.findViewById<TextView>(R.id.label_available_count)
        val searchBox = dialogView.findViewById<android.widget.EditText>(R.id.game_manager_search)
        addedList.layoutManager = LinearLayoutManager(this)
        availableList.layoutManager = LinearLayoutManager(this)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
            .setView(dialogView)
            .create()

        var currentQuery = ""

        fun matchesQuery(app: android.content.pm.ApplicationInfo): Boolean {
            if (currentQuery.isBlank()) return true
            return pm.getApplicationLabel(app).toString().contains(currentQuery, ignoreCase = true) ||
                app.packageName.contains(currentQuery, ignoreCase = true)
        }

        lateinit var addedAdapter: AppToggleAdapter
        lateinit var availableAdapter: AppToggleAdapter

        fun refreshBothLists() {
            val filtered = allApps.filter { matchesQuery(it) }
            val added = filtered.filter { GameListStore.isGame(this, it.packageName) }
            val available = filtered.filter { !GameListStore.isGame(this, it.packageName) }
            addedAdapter.submit(added)
            availableAdapter.submit(available)
            addedLabel.text = "Đã thêm ${added.size} game"
            availableLabel.text = "Chưa thêm ${available.size} app"
            refreshList()
        }

        addedAdapter = AppToggleAdapter(
            pm,
            emptyList(),
            isOn = { true }
        ) { app, checked ->
            if (!checked) {
                GameListStore.removeGame(this, app.packageName)
                refreshBothLists()
            }
        }
        availableAdapter = AppToggleAdapter(
            pm,
            emptyList(),
            isOn = { false }
        ) { app, checked ->
            if (checked) {
                GameListStore.addGame(this, app.packageName)
                if (selectedGame == null) selectGame(app.packageName)
                refreshBothLists()
            }
        }
        addedList.adapter = addedAdapter
        availableList.adapter = availableAdapter
        refreshBothLists()

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                currentQuery = s?.toString() ?: ""
                refreshBothLists()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        dialogView.findViewById<TextView>(R.id.btn_back_game_manager).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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
QSPACE_EOF

mkdir -p "app/src/main/java/com/quandzv23/gamespace"
cat > app/src/main/java/com/quandzv23/gamespace/SettingsStore.kt << 'QSPACE_EOF'
package com.quandzv23.gamespace

import android.content.Context

object SettingsStore {
    private const val PREFS = "qspace_settings"
    private const val KEY_TOUCH_LOCK = "touch_lock_enabled"
    private const val KEY_CALL_BLOCK = "call_block_enabled"
    private const val KEY_WIFI_OPT = "wifi_optimize_enabled"
    private const val KEY_TAB_Y = "edge_tab_y_position"
    private const val KEY_QUICK_APPS = "quick_apps"
    private const val KEY_INTRO_VIDEO_URI = "intro_video_uri"

    fun isTouchLockEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TOUCH_LOCK, false)

    fun setTouchLockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_TOUCH_LOCK, enabled).apply()
    }

    fun isCallBlockEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_BLOCK, false)

    fun setCallBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CALL_BLOCK, enabled).apply()
    }

    fun isWifiOptimizeEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WIFI_OPT, false)

    fun setWifiOptimizeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIFI_OPT, enabled).apply()
    }

    fun getTabYPosition(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TAB_Y, 260)

    fun setTabYPosition(context: Context, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_TAB_Y, y).apply()
    }

    fun getQuickApps(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_QUICK_APPS, emptySet()) ?: emptySet()

    fun addQuickApp(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getQuickApps(context).toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_QUICK_APPS, current).apply()
    }

    fun removeQuickApp(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getQuickApps(context).toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_QUICK_APPS, current).apply()
    }

    /** URI (dạng String) của video mp4 dùng làm hiệu ứng lúc mở app. Null = dùng animation mặc định. */
    fun getIntroVideoUri(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INTRO_VIDEO_URI, null)

    fun setIntroVideoUri(context: Context, uri: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_INTRO_VIDEO_URI, uri).apply()
    }
}
QSPACE_EOF

mkdir -p "app/src/main/res/layout"
cat > app/src/main/res/layout/activity_main.xml << 'QSPACE_EOF'
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/bg_deep">

    <LinearLayout
        android:id="@+id/main_content"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <!-- Thanh trạng thái trên cùng: pin, CPU%, GPU% -->
        <LinearLayout
            android:id="@+id/header_container"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@drawable/header_gradient"
            android:orientation="horizontal"
            android:paddingHorizontal="20dp"
            android:paddingVertical="14dp"
            android:gravity="center_vertical">

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="QSPACE"
                android:textColor="@color/accent_cyan"
                android:textSize="15sp"
                android:letterSpacing="0.2"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/stat_battery"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="16dp"
                android:text="🔋 --%"
                android:textColor="@color/text_primary"
                android:textSize="13sp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="4dp"
                android:text="CPU"
                android:textColor="@color/text_secondary"
                android:textSize="12sp" />

            <TextView
                android:id="@+id/stat_cpu"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="16dp"
                android:text="--%"
                android:textColor="@color/accent_amber"
                android:textSize="13sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="4dp"
                android:text="GPU"
                android:textColor="@color/text_secondary"
                android:textSize="12sp" />

            <TextView
                android:id="@+id/stat_gpu"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="--%"
                android:textColor="@color/accent_cyan"
                android:textSize="13sp"
                android:textStyle="bold" />

        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:orientation="horizontal">

            <!-- Cột trái: danh sách game -->
            <LinearLayout
                android:layout_width="250dp"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:paddingTop="12dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:paddingHorizontal="16dp"
                    android:paddingBottom="8dp"
                    android:gravity="center_vertical">

                    <TextView
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:text="Game"
                        android:textColor="@color/text_primary"
                        android:textSize="14sp"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/btn_add_game"
                        android:layout_width="34dp"
                        android:layout_height="34dp"
                        android:background="@drawable/pill_button_amber"
                        android:gravity="center"
                        android:text="+"
                        android:textColor="#0B0E1A"
                        android:textStyle="bold"
                        android:textSize="20sp" />

                </LinearLayout>

                <androidx.recyclerview.widget.RecyclerView
                    android:id="@+id/game_list"
                    android:layout_width="match_parent"
                    android:layout_height="0dp"
                    android:layout_weight="1"
                    android:paddingHorizontal="12dp"
                    android:clipToPadding="false" />

                <!-- Cài đặt gọn ở dưới cột trái -->
                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:paddingHorizontal="16dp"
                    android:paddingVertical="10dp"
                    android:gravity="center_vertical">

                    <TextView
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:text="Theo dõi nền"
                        android:textColor="@color/text_secondary"
                        android:textSize="12sp" />

                    <androidx.appcompat.widget.SwitchCompat
                        android:id="@+id/switch_service"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:thumbTint="@color/accent_amber"
                        android:trackTint="@color/bg_card_alt" />

                </LinearLayout>

                <!-- Đổi video hiệu ứng lúc mở app -->
                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:paddingHorizontal="16dp"
                    android:paddingBottom="10dp"
                    android:gravity="center_vertical">

                    <TextView
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:text="Video mở app"
                        android:textColor="@color/text_secondary"
                        android:textSize="12sp" />

                    <TextView
                        android:id="@+id/btn_change_intro_video"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:background="@drawable/panel_row_bg"
                        android:paddingHorizontal="10dp"
                        android:paddingVertical="6dp"
                        android:text="Đổi video"
                        android:textColor="@color/accent_cyan"
                        android:textSize="10sp"
                        android:textStyle="bold" />

                </LinearLayout>

                <LinearLayout
                    android:id="@+id/root_status_card"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_margin="12dp"
                    android:background="@drawable/card_game_item"
                    android:orientation="horizontal"
                    android:padding="10dp"
                    android:gravity="center_vertical">

                    <TextView
                        android:id="@+id/root_status_icon"
                        android:layout_width="26dp"
                        android:layout_height="26dp"
                        android:background="@drawable/qspace_logo_bg"
                        android:gravity="center"
                        android:text="?"
                        android:textSize="11sp"
                        android:textColor="@color/bg_deep"
                        android:textStyle="bold" />

                    <TextView
                        android:id="@+id/root_status_title"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:layout_marginStart="10dp"
                        android:text="Đang quét root..."
                        android:textColor="@color/text_primary"
                        android:textSize="11sp" />

                    <TextView
                        android:id="@+id/root_status_action"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:background="@drawable/panel_row_bg"
                        android:paddingHorizontal="10dp"
                        android:paddingVertical="6dp"
                        android:text="Quét"
                        android:textColor="@color/accent_cyan"
                        android:textSize="10sp"
                        android:textStyle="bold" />

                </LinearLayout>

            </LinearLayout>

            <!-- Cột phải: khung showcase game đang chọn -->
            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="match_parent"
                android:layout_weight="1"
                android:orientation="vertical"
                android:gravity="center"
                android:padding="24dp">

                <FrameLayout
                    android:layout_width="match_parent"
                    android:layout_height="0dp"
                    android:layout_weight="1"
                    android:background="@drawable/showcase_frame_bg">

                    <ImageView
                        android:id="@+id/showcase_backdrop"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:scaleType="centerCrop"
                        android:alpha="0.25"
                        android:contentDescription="backdrop" />

                    <LinearLayout
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_gravity="center"
                        android:orientation="vertical"
                        android:gravity="center">

                        <ImageView
                            android:id="@+id/showcase_icon"
                            android:layout_width="96dp"
                            android:layout_height="96dp"
                            android:background="@drawable/card_game_item"
                            android:padding="10dp"
                            android:contentDescription="game" />

                        <TextView
                            android:id="@+id/showcase_name"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="14dp"
                            android:text="Chưa chọn game"
                            android:textColor="@color/text_primary"
                            android:textSize="16sp"
                            android:textStyle="bold" />

                    </LinearLayout>

                </FrameLayout>

                <TextView
                    android:id="@+id/btn_start_game"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="18dp"
                    android:background="@drawable/pill_button_amber"
                    android:gravity="center"
                    android:paddingVertical="14dp"
                    android:text="▶  Bắt đầu chơi"
                    android:textColor="#0B0E1A"
                    android:textSize="15sp"
                    android:textStyle="bold" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="16dp"
                    android:layout_gravity="start"
                    android:text="ĐA NHIỆM NHANH"
                    android:textColor="@color/text_secondary"
                    android:textSize="9sp"
                    android:letterSpacing="0.15" />

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:layout_marginTop="6dp"
                    android:gravity="center_vertical">

                    <HorizontalScrollView
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:scrollbars="none">

                        <LinearLayout
                            android:id="@+id/quick_apps_row"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:orientation="horizontal" />

                    </HorizontalScrollView>

                    <TextView
                        android:id="@+id/btn_add_quick_app"
                        android:layout_width="34dp"
                        android:layout_height="34dp"
                        android:background="@drawable/tile_bg_inactive"
                        android:gravity="center"
                        android:text="+"
                        android:textColor="@color/accent_cyan"
                        android:textSize="20sp"
                        android:textStyle="bold" />

                </LinearLayout>

            </LinearLayout>

        </LinearLayout>

    </LinearLayout>

    <!-- Overlay animation lúc mở app: sóng xung + logo bụp vào + khép tròn lộ giao diện thật -->
    <FrameLayout
        android:id="@+id/open_anim_overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@color/bg_deep">

        <androidx.media3.ui.PlayerView
            android:id="@+id/open_intro_video"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            app:resize_mode="zoom"
            app:use_controller="false"
            app:surface_type="surface_view"
            android:visibility="gone" />

        <View
            android:id="@+id/open_ring1"
            android:layout_width="120dp"
            android:layout_height="120dp"
            android:layout_gravity="center"
            android:background="@drawable/shock_ring"
            android:alpha="0" />

        <View
            android:id="@+id/open_ring2"
            android:layout_width="120dp"
            android:layout_height="120dp"
            android:layout_gravity="center"
            android:background="@drawable/shock_ring"
            android:alpha="0" />

        <View
            android:id="@+id/open_ring3"
            android:layout_width="120dp"
            android:layout_height="120dp"
            android:layout_gravity="center"
            android:background="@drawable/shock_ring"
            android:alpha="0" />

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:orientation="vertical"
            android:gravity="center">

            <FrameLayout
                android:id="@+id/open_logo_badge"
                android:layout_width="86dp"
                android:layout_height="86dp"
                android:background="@drawable/qspace_logo_bg"
                android:scaleX="1.3"
                android:scaleY="1.3"
                android:alpha="0">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center"
                    android:text="Q"
                    android:textColor="@color/bg_deep"
                    android:textSize="40sp"
                    android:textStyle="bold" />
            </FrameLayout>

            <TextView
                android:id="@+id/open_logo_title"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="18dp"
                android:text="QSPACE"
                android:textColor="@color/text_primary"
                android:textSize="22sp"
                android:letterSpacing="0.3"
                android:textStyle="bold"
                android:alpha="0" />

        </LinearLayout>

    </FrameLayout>

</androidx.coordinatorlayout.widget.CoordinatorLayout>
QSPACE_EOF

echo "Đã ghi đè xong 4 file."
git status
