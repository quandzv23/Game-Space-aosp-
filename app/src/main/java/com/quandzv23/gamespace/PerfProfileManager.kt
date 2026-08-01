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
        "com.zing.mp3"
    )

    /** Dọn RAM: force-stop các app bên thứ 3 đang cài (trừ app nhạc/YouTube, chính Qspace,
     *  và game đang chơi). Trả về số lượng app đã tắt, hoặc -1 nếu lệnh thất bại (thiếu root). */
    fun clearBackgroundApps(ownPackage: String, currentGamePackage: String?): Int {
        val listResult = Shell.cmd("pm list packages -3").exec()
        if (!listResult.isSuccess) return -1

        val packages = listResult.out
            .mapNotNull { line -> line.removePrefix("package:").trim().takeIf { it.isNotEmpty() } }
            .filter { it != ownPackage && it != currentGamePackage && it !in keepAlivePackages }

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
        return value.trim().toIntOrNull()?.coerceIn(0, 100) ?: -1
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

    /** Trả về true CHỈ KHI toàn bộ lệnh ghi sysfs đều thành công thật sự. */
    fun applyGameProfile(profile: Profile): Boolean {
        val results = mutableListOf<Boolean>()
        when (profile) {
            Profile.PERFORMANCE -> {
                results += writeSysfs(CPU0_GOV_PATH, "performance")
                results += writeSysfs(CPU4_GOV_PATH, "performance")
                results += writeSysfs(CPU0_MAX_FREQ_PATH, "2210000")
                results += writeSysfs(CPU4_MAX_FREQ_PATH, "2210000")
                results += writeSysfs(GPU_GOVERNOR_PATH, "Static")
                results += writeSysfs(GPU_MAX_FREQ_PATH, "1196")
            }
            Profile.BALANCED -> {
                results += writeSysfs(CPU0_GOV_PATH, "schedutil")
                results += writeSysfs(CPU4_GOV_PATH, "schedutil")
                results += writeSysfs(CPU0_MAX_FREQ_PATH, defaultCpu0MaxFreq)
                results += writeSysfs(CPU4_MAX_FREQ_PATH, "2106000")
                results += writeSysfs(GPU_GOVERNOR_PATH, defaultGpuGovernor)
                results += writeSysfs(GPU_MAX_FREQ_PATH, "1001")
            }
            Profile.BATTERY_SAVER -> {
                results += writeSysfs(CPU0_GOV_PATH, "powersave")
                results += writeSysfs(CPU4_GOV_PATH, "powersave")
                results += writeSysfs(CPU0_MAX_FREQ_PATH, defaultCpu0MaxFreq)
                results += writeSysfs(CPU4_MAX_FREQ_PATH, defaultCpu4MaxFreq)
                results += writeSysfs(GPU_GOVERNOR_PATH, defaultGpuGovernor)
                results += writeSysfs(GPU_MAX_FREQ_PATH, defaultGpuMaxFreq)
            }
        }
        return results.all { it }
    }

    fun restoreDefault(): Boolean {
        val results = listOf(
            writeSysfs(CPU0_GOV_PATH, defaultGovernor),
            writeSysfs(CPU4_GOV_PATH, defaultGovernor),
            writeSysfs(CPU0_MAX_FREQ_PATH, defaultCpu0MaxFreq),
            writeSysfs(CPU4_MAX_FREQ_PATH, defaultCpu4MaxFreq),
            writeSysfs(GPU_GOVERNOR_PATH, defaultGpuGovernor),
            writeSysfs(GPU_MAX_FREQ_PATH, defaultGpuMaxFreq)
        )
        return results.all { it }
    }

    /** Ghi sysfs qua root, trả về true/false theo kết quả THẬT của lệnh shell. */
    private fun writeSysfs(path: String, value: String): Boolean {
        val result = Shell.cmd("echo $value > $path").exec()
        return result.isSuccess
    }

    private fun readSysfs(path: String): String? {
        val result = Shell.cmd("cat $path").exec()
        return if (result.isSuccess) result.out.firstOrNull()?.trim() else null
    }
}
