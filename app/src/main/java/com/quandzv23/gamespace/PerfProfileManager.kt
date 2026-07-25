package com.quandzv23.gamespace

import com.topjohnwu.superuser.Shell

/**
 * Đẩy/khôi phục các thông số hiệu năng qua sysfs khi vào và thoát game.
 *
 * QUAN TRỌNG: các đường dẫn sysfs dưới đây là VÍ DỤ MẪU dựa trên cấu trúc
 * cpufreq-ufc / DVFS thường thấy trên Exynos 850/3830 (samsungexynos850/atlas).
 * Bạn cần xác nhận lại đường dẫn thật trên máy đã flash Atlas kernel bằng:
 *   adb shell find /sys/devices/platform -iname "*cpufreq*"
 *   adb shell find /sys/kernel -iname "*gpu*"
 * rồi sửa lại các hằng số bên dưới cho khớp.
 */
object PerfProfileManager {

    // --- Đường dẫn sysfs đã xác nhận trên A21s (Exynos 850) qua Termux ---
    // Exynos 850 có 2 cluster: policy0 = 4 core Little, policy4 = 4 core big
    private const val CPU0_GOV_PATH = "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor"
    private const val CPU0_MAX_FREQ_PATH = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
    private const val CPU4_GOV_PATH = "/sys/devices/system/cpu/cpufreq/policy4/scaling_governor"
    private const val CPU4_MAX_FREQ_PATH = "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"

    // GPU (Mali) — xác nhận trên A21s qua Termux
    private const val GPU_MAX_FREQ_PATH = "/sys/kernel/gpu/gpu_max_clock"
    private const val GPU_GOVERNOR_PATH = "/sys/kernel/gpu/gpu_governor"

    // Giá trị mặc định (idle) — điền lại theo máy thật, đây chỉ là placeholder an toàn
    private var defaultGovernor = "schedutil"
    private var defaultCpu0MaxFreq = "2002000"
    private var defaultCpu4MaxFreq = "2002000"
    private var defaultGpuMaxFreq = "1001"
    private var defaultGpuGovernor = "Default"

    enum class Profile { BALANCED, PERFORMANCE, BATTERY_SAVER }

    fun captureCurrentAsDefault() {
        defaultGovernor = readSysfs(CPU4_GOV_PATH) ?: defaultGovernor
        defaultCpu0MaxFreq = readSysfs(CPU0_MAX_FREQ_PATH) ?: defaultCpu0MaxFreq
        defaultCpu4MaxFreq = readSysfs(CPU4_MAX_FREQ_PATH) ?: defaultCpu4MaxFreq
        defaultGpuMaxFreq = readSysfs(GPU_MAX_FREQ_PATH) ?: defaultGpuMaxFreq
        defaultGpuGovernor = readSysfs(GPU_GOVERNOR_PATH) ?: defaultGpuGovernor
    }

    fun applyGameProfile(profile: Profile) {
        when (profile) {
            Profile.PERFORMANCE -> {
                writeSysfs(CPU0_GOV_PATH, "performance")
                writeSysfs(CPU4_GOV_PATH, "performance")
                writeSysfs(CPU0_MAX_FREQ_PATH, "2210000") // xác nhận có trong scaling_available_frequencies
                writeSysfs(CPU4_MAX_FREQ_PATH, "2210000")  // đỉnh OC theo atlas-kernel
                // GPU không có governor "performance" — danh sách thật:
                // Default, Interactive, Joint, Static, Booster, Dynamic.
                // "Static" khóa cứng ở 1 mức clock, gần với ý "không tụt clock" nhất.
                writeSysfs(GPU_GOVERNOR_PATH, "Static")
                writeSysfs(GPU_MAX_FREQ_PATH, "1196")      // đỉnh GPU OC đã xác nhận ổn định
            }
            Profile.BALANCED -> {
                writeSysfs(CPU0_GOV_PATH, "schedutil")
                writeSysfs(CPU4_GOV_PATH, "schedutil")
                writeSysfs(CPU0_MAX_FREQ_PATH, defaultCpu0MaxFreq)
                writeSysfs(CPU4_MAX_FREQ_PATH, "2106000")
                writeSysfs(GPU_GOVERNOR_PATH, defaultGpuGovernor)
                writeSysfs(GPU_MAX_FREQ_PATH, "1001")
            }
            Profile.BATTERY_SAVER -> {
                writeSysfs(CPU0_GOV_PATH, "powersave")
                writeSysfs(CPU4_GOV_PATH, "powersave")
                writeSysfs(CPU0_MAX_FREQ_PATH, defaultCpu0MaxFreq)
                writeSysfs(CPU4_MAX_FREQ_PATH, defaultCpu4MaxFreq)
                writeSysfs(GPU_GOVERNOR_PATH, defaultGpuGovernor)
                writeSysfs(GPU_MAX_FREQ_PATH, defaultGpuMaxFreq)
            }
        }
    }

    fun restoreDefault() {
        writeSysfs(CPU0_GOV_PATH, defaultGovernor)
        writeSysfs(CPU4_GOV_PATH, defaultGovernor)
        writeSysfs(CPU0_MAX_FREQ_PATH, defaultCpu0MaxFreq)
        writeSysfs(CPU4_MAX_FREQ_PATH, defaultCpu4MaxFreq)
        writeSysfs(GPU_GOVERNOR_PATH, defaultGpuGovernor)
        writeSysfs(GPU_MAX_FREQ_PATH, defaultGpuMaxFreq)
    }

    private fun writeSysfs(path: String, value: String) {
        // libsu tự động dùng su binary do KernelSU cấp; app cần được cấp quyền
        // root trong danh sách allowlist của KernelSU Manager trước.
        Shell.cmd("echo $value > $path").exec()
    }

    private fun readSysfs(path: String): String? {
        val result = Shell.cmd("cat $path").exec()
        return if (result.isSuccess) result.out.firstOrNull()?.trim() else null
    }
}
