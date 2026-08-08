package com.quandzv23.gamespace

import com.topjohnwu.superuser.Shell

/**
 * Đẩy/khôi phục các thông số hiệu năng qua sysfs khi vào và thoát game.
 *
 * Đường dẫn sysfs bên dưới đã xác nhận thật trên A21s (Exynos 850) qua Termux.
 */
object PerfProfileManager {

    private const val CPU0_GOV_PATH = "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor"
    private const val CPU0_MAX_FREQ_PATH = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
    private const val CPU4_GOV_PATH = "/sys/devices/system/cpu/cpufreq/policy4/scaling_governor"
    private const val CPU4_MAX_FREQ_PATH = "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"

    private const val GPU_MAX_FREQ_PATH = "/sys/kernel/gpu/gpu_max_clock"
    private const val GPU_GOVERNOR_PATH = "/sys/kernel/gpu/gpu_governor"

    private var defaultGovernor = "schedutil"
    private var defaultCpu0MaxFreq = "2002000"
    private var defaultCpu4MaxFreq = "2002000"
    private var defaultGpuMaxFreq = "1001"
    private var defaultGpuGovernor = "Default"

    enum class Profile { BALANCED, PERFORMANCE, BATTERY_SAVER }

    /** Danh sách app luôn được giữ lại khi "Dọn RAM" — nhạc/media hay chạy nền, tắt đi sẽ gián
     *  đoạn nhạc đang phát. Có thể mở rộng thêm nếu thiếu app nào người dùng hay dùng. */
    private val keepAlivePackages = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.vng.zingmp3",
        "com.nct.nhaccuatui",
        "com.soundcloud.android",
        "com.zing.mp3",
        // Bàn phím phổ biến — giữ dự phòng cả khi lệnh đọc IME hiện tại thất bại
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey",
        "com.sec.android.inputmethod"
    )

    /** Dọn RAM: force-stop các app bên thứ 3 đang cài (trừ app nhạc/YouTube, chính Qspace,
     *  và game đang chơi). Trả về số lượng app đã tắt, hoặc -1 nếu lệnh thất bại (thiếu root). */
    fun clearBackgroundApps(ownPackage: String, currentGamePackage: String?): Int {
        val listResult = Shell.cmd("pm list packages -3").exec()
        if (!listResult.isSuccess) return -1

        // Bàn phím (IME) đang dùng — tuyệt đối không được tắt, không thì mất chữ đang gõ ngay.
        val imeResult = Shell.cmd("settings get secure default_input_method").exec()
        val currentImePackage = imeResult.out.firstOrNull()?.substringBefore("/")?.trim()

        val packages = listResult.out
            .mapNotNull { line -> line.removePrefix("package:").trim().takeIf { it.isNotEmpty() } }
            .filter {
                it != ownPackage && it != currentGamePackage && it != currentImePackage &&
                    it !in keepAlivePackages
            }

        var stoppedCount = 0
        for (pkg in packages) {
            val result = Shell.cmd("am force-stop $pkg").exec()
            if (result.isSuccess) stoppedCount++
        }
        return stoppedCount
    }

    /** Đọc % CPU tổng thật qua /proc/stat, dùng chung cho cả OverlayBubbleService và MainActivity. */
    fun readCpuUsagePercent(): Int {
        try {
            fun readStatLine(): LongArray? {
                val result = Shell.cmd("cat /proc/stat").exec()
                if (!result.isSuccess) return null
                val line = result.out.firstOrNull { it.startsWith("cpu ") } ?: return null
                val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (parts.size < 4) return null
                val idle = parts[3] + (parts.getOrElse(4) { 0L })
                val total = parts.sum()
                return longArrayOf(idle, total)
            }
            val first = readStatLine() ?: return -1
            Thread.sleep(200)
            val second = readStatLine() ?: return -1
            val idleDelta = second[0] - first[0]
            val totalDelta = second[1] - first[1]
            if (totalDelta <= 0) return -1
            val usage = (100 * (totalDelta - idleDelta) / totalDelta).toInt()
            return usage.coerceIn(0, 100)
        } catch (e: Exception) {
            return -1
        }
    }

    /** Đọc % tải GPU thật qua /sys/kernel/gpu/gpu_busy — node đã xác nhận có trên A21s. */
    fun readGpuUsagePercent(): Int {
        val value = readSysfs("/sys/kernel/gpu/gpu_busy") ?: return -1
        // Một số kernel trả về kèm "%" hoặc chữ khác (vd "12 %", "busy: 12") — lọc lấy số đầu tiên
        val digitsOnly = Regex("\\d+").find(value)?.value ?: return -1
        return digitsOnly.toIntOrNull()?.coerceIn(0, 100) ?: -1
    }

    /** Kiểm tra thật xem app có quyền root dùng được không (không chỉ "đã cài KernelSU"). */
    fun hasRootAccess(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
    }

    /** Kiểm tra thật xem tiến trình của app còn sống trong nền không (chưa bị hệ thống/kill hẳn),
     *  dùng để quyết định có cần hiện lại splash lúc vào game hay không. */
    fun isProcessAlive(pkg: String): Boolean {
        val result = Shell.cmd("pidof $pkg").exec()
        return result.isSuccess && result.out.any { it.isNotBlank() }
    }

    fun captureCurrentAsDefault() {
        defaultGovernor = readSysfs(CPU4_GOV_PATH) ?: defaultGovernor
        defaultCpu0MaxFreq = readSysfs(CPU0_MAX_FREQ_PATH) ?: defaultCpu0MaxFreq
        defaultCpu4MaxFreq = readSysfs(CPU4_MAX_FREQ_PATH) ?: defaultCpu4MaxFreq
        defaultGpuMaxFreq = readSysfs(GPU_MAX_FREQ_PATH) ?: defaultGpuMaxFreq
        defaultGpuGovernor = readSysfs(GPU_GOVERNOR_PATH) ?: defaultGpuGovernor
    }

    /**
     * Trả về true CHỈ KHI toàn bộ giá trị đều ĐỌC LẠI ĐÚNG như đã ghi (không chỉ tin vào
     * exit code của lệnh "echo" — kernel có thể âm thầm từ chối/làm tròn giá trị không phải
     * bước tần số hợp lệ mà lệnh shell vẫn báo thành công). Tần số CPU cũng được tự động
     * khớp về đúng bước gần nhất có trong bảng tần số thật của máy trước khi ghi, thay vì
     * áp thẳng 1 số cứng có thể không tồn tại trên chip.
     */
    fun applyGameProfile(profile: Profile): Boolean {
        val cpu0Freqs = availableFreqs(CPU0_MAX_FREQ_PATH)
        val cpu4Freqs = availableFreqs(CPU4_MAX_FREQ_PATH)
        val results = mutableListOf<Boolean>()
        when (profile) {
            Profile.PERFORMANCE -> {
                results += writeSysfsVerified(CPU0_GOV_PATH, "performance")
                results += writeSysfsVerified(CPU4_GOV_PATH, "performance")
                results += writeSysfsVerified(CPU0_MAX_FREQ_PATH, nearestFreq(cpu0Freqs, 2210000))
                results += writeSysfsVerified(CPU4_MAX_FREQ_PATH, nearestFreq(cpu4Freqs, 2210000))
                results += writeSysfsVerified(GPU_GOVERNOR_PATH, "Static")
                results += writeSysfsVerified(GPU_MAX_FREQ_PATH, "1196")
            }
            Profile.BALANCED -> {
                results += writeSysfsVerified(CPU0_GOV_PATH, "schedutil")
                results += writeSysfsVerified(CPU4_GOV_PATH, "schedutil")
                results += writeSysfsVerified(CPU0_MAX_FREQ_PATH, defaultCpu0MaxFreq)
                // 2106000 là mốc "giữa" chủ ý (nhanh hơn mặc định, thấp hơn hẳn mức OC full
                // performance) — nhưng có thể KHÔNG phải bước tần số hợp lệ của chip, nên
                // luôn khớp về bước thật gần nhất trước khi ghi.
                results += writeSysfsVerified(CPU4_MAX_FREQ_PATH, nearestFreq(cpu4Freqs, 2106000))
                results += writeSysfsVerified(GPU_GOVERNOR_PATH, defaultGpuGovernor)
                results += writeSysfsVerified(GPU_MAX_FREQ_PATH, "1001")
            }
            Profile.BATTERY_SAVER -> {
                results += writeSysfsVerified(CPU0_GOV_PATH, "powersave")
                results += writeSysfsVerified(CPU4_GOV_PATH, "powersave")
                results += writeSysfsVerified(CPU0_MAX_FREQ_PATH, defaultCpu0MaxFreq)
                results += writeSysfsVerified(CPU4_MAX_FREQ_PATH, defaultCpu4MaxFreq)
                results += writeSysfsVerified(GPU_GOVERNOR_PATH, defaultGpuGovernor)
                results += writeSysfsVerified(GPU_MAX_FREQ_PATH, defaultGpuMaxFreq)
            }
        }
        return results.all { it }
    }

    fun restoreDefault(): Boolean {
        val results = listOf(
            writeSysfsVerified(CPU0_GOV_PATH, defaultGovernor),
            writeSysfsVerified(CPU4_GOV_PATH, defaultGovernor),
            writeSysfsVerified(CPU0_MAX_FREQ_PATH, defaultCpu0MaxFreq),
            writeSysfsVerified(CPU4_MAX_FREQ_PATH, defaultCpu4MaxFreq),
            writeSysfsVerified(GPU_GOVERNOR_PATH, defaultGpuGovernor),
            writeSysfsVerified(GPU_MAX_FREQ_PATH, defaultGpuMaxFreq)
        )
        return results.all { it }
    }

    /** Đọc bảng tần số CPU hợp lệ thật của policy này (rỗng nếu đọc không được). */
    private fun availableFreqs(scalingMaxFreqPath: String): List<Long> {
        val availPath = scalingMaxFreqPath.replace("scaling_max_freq", "scaling_available_frequencies")
        val raw = readSysfs(availPath) ?: return emptyList()
        return raw.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
    }

    /** Khớp target về đúng bước tần số gần nhất có thật trong danh sách; nếu không đọc được
     *  danh sách (thiết bị/kernel không hỗ trợ liệt kê) thì đành dùng nguyên target. */
    private fun nearestFreq(available: List<Long>, target: Long): String {
        if (available.isEmpty()) return target.toString()
        return (available.minByOrNull { kotlin.math.abs(it - target) } ?: target).toString()
    }

    /**
     * Tắt màn hình thật (giả lập bấm nút nguồn qua root) để chạy nền/AFK farm,
     * không phải overlay đen giả — máy thật sự tắt hình, tiết kiệm pin đúng nghĩa.
     * Bên gọi (OverlayBubbleService) phải giữ PARTIAL_WAKE_LOCK TRƯỚC khi gọi hàm này,
     * nếu không game/CPU sẽ bị hệ thống cho ngủ theo màn hình, auto-click sẽ dừng luôn.
     */
    fun turnScreenOff(): Boolean {
        val result = Shell.cmd("input keyevent 26").exec()
        return result.isSuccess
    }

    /** Ghi sysfs qua root, trả về true/false theo kết quả THẬT của lệnh shell. */
    private fun writeSysfs(path: String, value: String): Boolean {
        val result = Shell.cmd("echo $value > $path").exec()
        return result.isSuccess
    }

    /**
     * Ghi sysfs RỒI ĐỌC LẠI để xác nhận giá trị đã thực sự được kernel áp dụng — không chỉ
     * tin vào exit code của "echo". Đây là cách duy nhất biết chắc chế độ hiệu năng có tác
     * động phần cứng thật hay chỉ đổi UI mà không đổi gì bên dưới.
     *
     * Với giá trị dạng SỐ (tần số): chấp nhận sai lệch nhỏ (≤5%) vì một số kernel làm tròn
     * xuống bước hợp lệ gần nhất thay vì áp đúng số tuyệt đối — vẫn coi là áp dụng thành công.
     * Với giá trị dạng CHỮ (governor: "performance", "powersave"...): bắt buộc khớp chính xác.
     */
    private fun writeSysfsVerified(path: String, value: String): Boolean {
        val writeResult = Shell.cmd("echo $value > $path").exec()
        if (!writeResult.isSuccess) return false

        val readBack = readSysfs(path) ?: return false
        val targetNum = value.toLongOrNull()
        val readNum = readBack.toLongOrNull()
        return if (targetNum != null && readNum != null) {
            val diff = kotlin.math.abs(targetNum - readNum)
            val tolerance = (targetNum / 20).coerceAtLeast(1) // ~5%, tối thiểu 1 để tránh chia hết về 0
            diff <= tolerance
        } else {
            readBack.trim().equals(value.trim(), ignoreCase = true)
        }
    }

    private fun readSysfs(path: String): String? {
        val result = Shell.cmd("cat $path").exec()
        return if (result.isSuccess) result.out.firstOrNull()?.trim() else null
    }
}
