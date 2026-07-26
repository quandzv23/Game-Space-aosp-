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

    /** Kiểm tra thật xem app có quyền root dùng được không (không chỉ "đã cài KernelSU"). */
    fun hasRootAccess(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
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
