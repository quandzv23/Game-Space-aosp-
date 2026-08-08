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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.topjohnwu.superuser.Shell
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: GameAdapter
    private var selectedGame: String? = null
    private var introPlayer: ExoPlayer? = null
    // View của dialog "Cài đặt" đang mở (null nếu đang đóng) — các hàm xử lý video/root/
    // đa nhiệm cần tìm view CON BÊN TRONG dialog này thay vì tìm trên Activity gốc, vì
    // những view đó (nút đổi video, trạng thái root...) giờ chỉ tồn tại trong dialog.
    private var settingsMenuView: android.view.View? = null

    // Chọn video mở app bằng Photo Picker gốc của Android (lưới thumbnail đẹp, không
    // cần xin quyền đọc bộ nhớ) thay vì trình duyệt file cũ. Vẫn xin persistable
    // permission như trước để phát lại được video ở những lần mở app sau, kể cả sau
    // khi khởi động lại máy — Photo Picker hỗ trợ y hệt cơ chế này.
    private val pickIntroVideoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
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
            // Video mới -> góc xoay cũ (nếu có) không còn đúng nữa, reset về 0.
            SettingsStore.setIntroVideoRotation(this, 0)
            SettingsStore.addIntroVideoToHistory(this, uri.toString(), 0)
            SettingsStore.setIntroVideoEnabled(this, true)
            val menuView = settingsMenuView
            val rotateBtn = menuView?.findViewById<TextView>(R.id.btn_rotate_intro_video)
            rotateBtn?.text = "Xoay video này: 0°"
            syncIntroVideoCard(menuView)
            if (menuView != null && rotateBtn != null) {
                refreshVideoThumbRow(menuView.findViewById(R.id.video_thumb_row), rotateBtn)
            }
            Toast.makeText(this, "Đã đổi video mở app", Toast.LENGTH_SHORT).show()
        }

    // Chọn ảnh nền tuỳ chỉnh cho giao diện chính (khác với ảnh nền/video mở app).
    private val pickBackgroundLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) { }
            SettingsStore.setAppBackgroundUri(this, uri.toString())
            applyAppBackground()
            Toast.makeText(this, "Đã đổi nền giao diện", Toast.LENGTH_SHORT).show()
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

        if (SettingsStore.getIntroPlayed(this)) {
            // Đã phát trong phiên hiện tại (chưa bị vuốt khỏi Recents kể từ lần phát trước)
            // -> vào lại lần này không phát video/animation nữa.
            findViewById<android.view.View>(R.id.open_anim_overlay).visibility = android.view.View.GONE
        } else {
            SettingsStore.setIntroPlayed(this, true)
            playEntranceAnimation()
        }
        ensurePermissions()
        applyAppBackground()

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

        findViewById<TextView>(R.id.btn_open_settings_menu).setOnClickListener {
            showSettingsMenu(openQuickAppPickerAfter = false)
        }

        // Được mở từ shortcut/widget bên ngoài để thêm nhanh 1 app đa nhiệm -> mở luôn
        // menu cài đặt (nơi giờ chứa mục Đa nhiệm nhanh) rồi bật sẵn bộ chọn app.
        if (intent?.getBooleanExtra("open_add_quick_app", false) == true) {
            showSettingsMenu(openQuickAppPickerAfter = true)
        }
    }

    override fun onResume() {
        super.onResume()
        statsRunning = true
        statsHandler.post(statsRunnable)
    }

    /**
     * Menu "Cài đặt" gộp mọi tính năng ngoài phần chọn/chơi game (theo dõi nền, video
     * mở app, trạng thái root, đa nhiệm nhanh) — mở qua nút ⋮ ở góc trên, để màn hình
     * chính chỉ còn thuần danh sách game + khung showcase.
     */
    private fun showSettingsMenu(openQuickAppPickerAfter: Boolean) {
        val menuView = layoutInflater.inflate(R.layout.settings_menu, null)
        settingsMenuView = menuView

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(menuView)
            .setOnDismissListener { settingsMenuView = null }
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Theo dõi nền
        val switch = menuView.findViewById<SwitchCompat>(R.id.switch_service)
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startForegroundService(Intent(this, GameWatcherService::class.java))
                Toast.makeText(this, "Qspace đang chạy nền", Toast.LENGTH_SHORT).show()
            } else {
                stopService(Intent(this, GameWatcherService::class.java))
            }
        }

        // Nền giao diện chính
        menuView.findViewById<TextView>(R.id.btn_change_app_background).setOnClickListener {
            pickBackgroundLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        menuView.findViewById<TextView>(R.id.btn_reset_app_background).setOnClickListener {
            SettingsStore.setAppBackgroundUri(this, null)
            applyAppBackground()
            Toast.makeText(this, "Đã trả về nền mặc định", Toast.LENGTH_SHORT).show()
        }

        // Video mở app
        menuView.findViewById<android.view.View>(R.id.btn_change_intro_video).setOnClickListener {
            pickIntroVideoLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }

        val rotateBtn = menuView.findViewById<TextView>(R.id.btn_rotate_intro_video)
        val thumbRow = menuView.findViewById<android.widget.LinearLayout>(R.id.video_thumb_row)
        rotateBtn.text = "Xoay video này: ${SettingsStore.getIntroVideoRotation(this)}°"
        rotateBtn.setOnClickListener {
            // Chỉ để chỉnh cho video có nội dung nghiêng sẵn trong file (không có cờ
            // rotation để tự phát hiện) — bấm lần lượt 0 -> 90 -> 180 -> 270 -> 0.
            val current = SettingsStore.getIntroVideoRotation(this)
            val next = (current + 90) % 360
            SettingsStore.setIntroVideoRotation(this, next)
            // Nhớ luôn góc xoay này riêng cho video đang chọn, để lỡ chuyển qua video
            // khác rồi quay lại thì không phải chỉnh lại từ đầu.
            SettingsStore.getIntroVideoUri(this)?.let {
                SettingsStore.updateIntroVideoRotationInHistory(this, it, next)
            }
            rotateBtn.text = "Xoay video này: $next°"
            refreshVideoThumbRow(thumbRow, rotateBtn)
            Toast.makeText(this, "Vuốt app khỏi Recents rồi mở lại để xem thử", Toast.LENGTH_SHORT).show()
        }

        syncIntroVideoCard(menuView)
        refreshVideoThumbRow(thumbRow, rotateBtn)

        // Tỉ lệ khuyến nghị để crop video trước khi thêm — tính TRỰC TIẾP từ kích
        // thước màn hình thật lúc chạy (không hardcode số của riêng máy nào), nên đúng
        // với mọi máy cài app này, không chỉ A21s.
        val dmHint = resources.displayMetrics
        val w = dmHint.widthPixels
        val h = dmHint.heightPixels
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val g = gcd(w, h)
        menuView.findViewById<TextView>(R.id.intro_video_ratio_hint).text =
            "Tỉ lệ khuyến nghị để không viền/không cắt: ${w}×${h} (${w / g}:${h / g})"

        // Trạng thái root
        val rootIcon = menuView.findViewById<TextView>(R.id.root_status_icon)
        val rootTitle = menuView.findViewById<TextView>(R.id.root_status_title)
        scanRootAccess(rootIcon, rootTitle)
        menuView.findViewById<TextView>(R.id.root_status_action).setOnClickListener {
            scanRootAccess(rootIcon, rootTitle)
        }

        // Đa nhiệm nhanh
        val quickAppsRow = menuView.findViewById<android.widget.LinearLayout>(R.id.quick_apps_row)
        refreshQuickApps(quickAppsRow)
        menuView.findViewById<TextView>(R.id.btn_add_quick_app).setOnClickListener {
            showInstalledAppsPicker { pkg ->
                SettingsStore.addQuickApp(this, pkg)
                refreshQuickApps(quickAppsRow)
            }
        }

        dialog.show()

        if (openQuickAppPickerAfter) {
            showInstalledAppsPicker { pkg ->
                SettingsStore.addQuickApp(this, pkg)
                refreshQuickApps(quickAppsRow)
            }
        }
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

    /** Hiện ảnh nền tuỳ chỉnh (nếu đã chọn) lên toàn giao diện chính, hoặc ẩn đi để
     *  dùng lại màu nền mặc định nếu chưa chọn/đã reset. */
    private fun applyAppBackground() {
        val bgImage = findViewById<ImageView>(R.id.app_background_image)
        val uriString = SettingsStore.getAppBackgroundUri(this)
        if (uriString == null) {
            bgImage.visibility = android.view.View.GONE
            bgImage.setImageDrawable(null)
            return
        }
        try {
            bgImage.setImageURI(Uri.parse(uriString))
            bgImage.visibility = android.view.View.VISIBLE
        } catch (e: Exception) {
            // URI không đọc được nữa (file bị xoá, mất quyền...) -> rơi về nền mặc định
            bgImage.visibility = android.view.View.GONE
            SettingsStore.setAppBackgroundUri(this, null)
        }
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

    /** Lấy tên file hiển thị cho 1 content URI, dùng để liệt kê trong danh sách "Video đã thêm". */
    private fun displayNameForUri(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            var name: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(idx)
                }
            }
            name ?: uri.lastPathSegment ?: "Video"
        } catch (e: Exception) {
            "Video"
        }
    }

    /**
     * Đồng bộ lại thẻ "Video mở app" trong menu Cài đặt (switch bật/tắt + dòng tên
     * video đang dùng) theo đúng trạng thái đang lưu — gọi lại mỗi khi trạng thái đổi
     * (chọn video mới, bật/tắt, chọn từ danh sách đã thêm...).
     */
    private fun syncIntroVideoCard(menuView: android.view.View?) {
        menuView ?: return
        val enabled = SettingsStore.isIntroVideoEnabled(this)
        val switch = menuView.findViewById<SwitchCompat>(R.id.switch_intro_video_enabled)
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = enabled
        switch.setOnCheckedChangeListener { _, isChecked -> toggleIntroVideo(menuView, isChecked) }

        val nameView = menuView.findViewById<TextView>(R.id.intro_video_current_name)
        val uri = SettingsStore.getIntroVideoUri(this)
        nameView.text = if (enabled && uri != null) displayNameForUri(uri) else "Animation gốc"
    }

    private fun toggleIntroVideo(menuView: android.view.View, enabled: Boolean) {
        SettingsStore.setIntroVideoEnabled(this, enabled)
        syncIntroVideoCard(menuView)
        val rotateBtn = menuView.findViewById<TextView>(R.id.btn_rotate_intro_video)
        val thumbRow = menuView.findViewById<android.widget.LinearLayout>(R.id.video_thumb_row)
        refreshVideoThumbRow(thumbRow, rotateBtn)
        Toast.makeText(
            this,
            if (enabled) "Đã bật lại video mở app" else "Đã tắt — dùng animation gốc",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Dựng lại lưới thumbnail chọn video (kiểu MIUI Game Turbo): ô "+" cố định đầu
     * tiên (đã có sẵn trong XML) + 1 ô cho mỗi video trong lịch sử, viền sáng quanh ô
     * đang được chọn. Thumbnail lấy từ khung hình đầu video, giải mã chạy nền vì hơi
     * tốn thời gian, tránh giật lúc mở menu.
     */
    private fun refreshVideoThumbRow(row: android.widget.LinearLayout, rotateBtn: TextView) {
        // Xóa hết trừ ô "+" (view đầu tiên, luôn giữ nguyên vị trí)
        while (row.childCount > 1) {
            row.removeViewAt(1)
        }

        val history = SettingsStore.getIntroVideoHistory(this)
        val enabled = SettingsStore.isIntroVideoEnabled(this)
        val activeUri = if (enabled) SettingsStore.getIntroVideoUri(this) else null
        val dp = resources.displayMetrics.density

        // Ô "Animation gốc" — luôn đứng ngay sau "+", bấm để quay lại animation mặc định
        // (logo bụp + sóng xung) bất cứ lúc nào mà không mất video đã chọn.
        val gocTile = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt()).apply {
                marginEnd = (8 * dp).toInt()
            }
            setBackgroundResource(R.drawable.tile_bg_inactive)
        }
        val gocLabel = TextView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = android.view.Gravity.CENTER
            text = "🎬\nGốc"
            textSize = 10f
            setTextColor(resources.getColor(R.color.text_secondary, theme))
        }
        gocTile.addView(gocLabel)
        if (!enabled) {
            val border = android.view.View(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundResource(R.drawable.video_thumb_selected_border)
            }
            gocTile.addView(border)
        }
        gocTile.setOnClickListener {
            SettingsStore.setIntroVideoEnabled(this, false)
            syncIntroVideoCard(settingsMenuView)
            refreshVideoThumbRow(row, rotateBtn)
            Toast.makeText(this, "Đã chuyển về animation gốc", Toast.LENGTH_SHORT).show()
        }
        row.addView(gocTile)

        for ((uri, rotation) in history) {
            val tile = layoutInflater.inflate(R.layout.item_video_thumb, row, false)
            val image = tile.findViewById<ImageView>(R.id.thumb_image)
            val border = tile.findViewById<android.view.View>(R.id.thumb_selected_border)
            val badge = tile.findViewById<TextView>(R.id.thumb_rotation_badge)

            border.visibility = if (uri == activeUri) android.view.View.VISIBLE else android.view.View.GONE
            if (rotation != 0) {
                badge.text = "$rotation°"
                badge.visibility = android.view.View.VISIBLE
            }

            tile.setOnClickListener {
                SettingsStore.setIntroVideoUri(this, uri)
                SettingsStore.setIntroVideoRotation(this, rotation)
                SettingsStore.setIntroVideoEnabled(this, true)
                rotateBtn.text = "Xoay video này: $rotation°"
                syncIntroVideoCard(settingsMenuView)
                refreshVideoThumbRow(row, rotateBtn)
                Toast.makeText(this, "Đã chọn: ${displayNameForUri(uri)}", Toast.LENGTH_SHORT).show()
            }

            row.addView(tile)

            thread {
                val bitmap = try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(this, Uri.parse(uri))
                    val frame = retriever.frameAtTime
                    retriever.release()
                    frame
                } catch (e: Exception) {
                    null
                }
                if (bitmap != null) {
                    runOnUiThread { image.setImageBitmap(bitmap) }
                }
            }
        }
    }

    /** Animation nhẹ lúc vừa mở app: header + card trượt lên và mờ dần vào. */
    private fun playEntranceAnimation() {
        val overlay = findViewById<android.view.View>(R.id.open_anim_overlay)

        val introUriString = if (SettingsStore.isIntroVideoEnabled(this)) {
            SettingsStore.getIntroVideoUri(this)
        } else {
            null
        }
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
     *
     * Đơn giản: 1 lớp duy nhất, luôn "fit" (hiện đủ 100% nội dung, không cắt, không
     * zoom, không lớp nền phụ). Có viền tối 2 bên nếu tỉ lệ video khác màn hình —
     * chấp nhận đánh đổi này để giữ mọi thứ đơn giản, nhẹ máy, không phèn.
     *
     * Trả về true nếu bắt đầu phát được; false nếu URI không dùng được (mất quyền,
     * file bị xóa...) -> rơi về animation mặc định ngay lập tức.
     */
    private fun playIntroVideo(overlay: android.view.View, uri: Uri): Boolean {
        val playerView = findViewById<PlayerView>(R.id.open_intro_video)
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        // Ẩn hẳn thanh trạng thái lúc phát video mở app -> video được dùng TOÀN BỘ diện
        // tích màn hình thật, không bị status bar ăn mất 1 dải phía trên (nguyên nhân
        // gây lệch tỉ lệ / dư viền dù video đã đúng tỉ lệ máy).
        hideSystemBarsForVideo()

        // Góc xoay thủ công (nút "Xoay video") — dùng cho video có nội dung nghiêng
        // sẵn trong file, không có cờ rotation để tự phát hiện được (như video F1 kiểu meme).
        val manualRotation = SettingsStore.getIntroVideoRotation(this)
        val dm = resources.displayMetrics
        playerView.rotation = manualRotation.toFloat()
        playerView.layoutParams = if (manualRotation == 90 || manualRotation == 270) {
            // Xoay ngang <-> dọc: view trước khi xoay phải có kích thước ĐẢO chiều
            // (rộng = chiều cao màn hình, cao = chiều rộng màn hình), để sau khi xoay
            // 90/270 độ quanh tâm thì vừa khít đúng màn hình thật, không méo/hụt.
            android.widget.FrameLayout.LayoutParams(dm.heightPixels, dm.widthPixels).apply {
                gravity = android.view.Gravity.CENTER
            }
        } else {
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

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
            restoreSystemBars()
            playerView.visibility = android.view.View.GONE
            false
        }
    }

    /** Ẩn thanh trạng thái/điều hướng tạm thời (chỉ trong lúc phát video mở app). */
    private fun hideSystemBarsForVideo() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** Trả lại thanh trạng thái/điều hướng bình thường sau khi video phát xong. */
    private fun restoreSystemBars() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    private fun finishIntroVideo(overlay: android.view.View, playerView: PlayerView, player: ExoPlayer) {
        playerView.visibility = android.view.View.GONE
        player.release()
        if (introPlayer === player) introPlayer = null
        restoreSystemBars()
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
    private fun scanRootAccess(icon: TextView, title: TextView) {
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

    private fun refreshQuickApps(row: android.widget.LinearLayout) {
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
                refreshQuickApps(row)
                true
            }
            row.addView(itemView)
        }
    }
}
