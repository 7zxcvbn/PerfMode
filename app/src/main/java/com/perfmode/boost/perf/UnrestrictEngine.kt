package com.perfmode.boost.perf

import com.perfmode.boost.device.DeviceProfile
import com.perfmode.boost.device.SocVendor
import com.perfmode.boost.shizuku.PrivilegedExecutor
import com.perfmode.boost.shizuku.PrivilegedExecutor.Result
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.util.LogStore

/**
 * Unrestrict / qboost / MTK 三套调频动作的执行器。
 *
 * 三条硬规则贯穿全类：
 * 1. 目标值只来自设备自己上报的 cpuinfo_max_freq / cpuinfo_min_freq / gpufreq_opp_dump，
 *    绝不写死社区流传的频率数值。
 * 2. 每一步都是「读备份 → 写 → 读回校验 → 失败回滚」，且绝不 chmod。
 * 3. 不预设身份门槛。root 能写的比例最高，但部分机型（尤其 SELinux 宽松或厂商放开
 *    sysfs 权限的）ADB/shell 身份同样可写。是否可用一律以实机探测到的 writable 为准，
 *    不因为"看起来像 ADB"就提前拦下。
 */

object Unrestrict {

    // ---------- 节点路径 ----------
    private const val CPUFREQ = "/sys/devices/system/cpu/cpufreq"
    private const val SCHED_BOOST = "/proc/sys/kernel/sched_boost"
    private const val SCHED_UP = "/proc/sys/kernel/sched_upmigrate"
    private const val SCHED_DOWN = "/proc/sys/kernel/sched_downmigrate"
    private const val STUNE_BOOST = "/dev/stune/top-app/schedtune.boost"
    private const val STUNE_PREFER_IDLE = "/dev/stune/top-app/schedtune.prefer_idle"
    private const val CPUSET_TOP_APP = "/dev/cpuset/top-app/cpus"
    private const val MSM_MAX = "/sys/module/msm_performance/parameters/cpu_max_freq"
    private const val MSM_MIN = "/sys/module/msm_performance/parameters/cpu_min_freq"

    // MTK
    private const val PPM_ENABLED = "/proc/ppm/enabled"
    private const val PPM_FREQ_IDX = "/proc/ppm/policy/ut_fix_freq_idx"
    private const val PPM_CORE_NUM = "/proc/ppm/policy/ut_fix_core_num"
    private const val PPM_HARD_MAX = "/proc/ppm/policy/hard_userlimit_max_cpu_freq"
    private const val PPM_HARD_MIN = "/proc/ppm/policy/hard_userlimit_min_cpu_freq"
    private const val GPUFREQ_OPP_DUMP = "/proc/gpufreq/gpufreq_opp_dump"
    private const val GPUFREQ_OPP_FREQ = "/proc/gpufreq/gpufreq_opp_freq"
    private const val GED_UPBOUND = "/d/ged/hal/custom_upbound_gpu_freq"
    private const val FPSGO_FORCE = "/sys/kernel/debug/fpsgo/common/force_onoff"
    private const val FPSGO_ENABLE = "/sys/kernel/fpsgo/common/fpsgo_enable"
    private const val FPSGO_LIMIT_LEVEL = "/sys/module/mtk_fpsgo/parameters/max_freq_limit_level"
    private const val PERFMGR_TOUCH = "/proc/perfmgr/tchbst/kernel/tb_enable"

    data class Step(
        val path: String,
        val label: String,
        val oldValue: String,
        val newValue: String,
        val ok: Boolean
    )

    data class Outcome(
        val applied: Int,
        val failed: Int,
        val steps: List<Step>,
        val notes: List<String>,
        val denied: Boolean = false
    )

    // ---------- 对外入口 ----------

    /** Unrestrict 策略：解锁频率上限 + 释放大核 */
    fun applyUnrestrict(profile: DeviceProfile, o: UnrestrictOptions): Outcome {
        if (!ShizukuManager.isReady()) return notReady("Unrestrict 策略")
        val steps = LinkedHashMap<String, Step>()
        val notes = mutableListOf<String>()

        for (p in profile.cpuPolicies) {
            val base = "$CPUFREQ/${p.name}"
            val hwMax = if (p.hwMaxKhz > 0) p.hwMaxKhz else p.maxKhz
            val hwMin = if (p.hwMinKhz > 0) p.hwMinKhz else p.minKhz

            // 1) 解锁上限
            if (o.unlockMaxFreq && hwMax > 0 && p.maxKhz < hwMax) {
                write("$base/scaling_max_freq", hwMax.toString(), "${p.name} 上限解锁", steps)
            }
            // 2) 抬高下限：在 [hwMin, hwMax] 区间按百分比取值
            if (o.raiseMinFreqPct > 0 && hwMax > hwMin) {
                val target = hwMin + ((hwMax - hwMin) * o.raiseMinFreqPct / 100)
                val safe = target.coerceAtMost(hwMax - 1)
                if (p.minKhz < safe) {
                    write("$base/scaling_min_freq", safe.toString(), "${p.name} 下限抬升", steps)
                }
            }
            // 3) core_ctl：让大核保持在线
            if (o.forceBigCoreOnline) {
                val firstCpu = p.relatedCpus.trim().split(Regex("\\s+")).firstOrNull() ?: "0"
                val minPath = "/sys/devices/system/cpu/cpu$firstCpu/core_ctl/min_cpus"
                val cur = PrivilegedExecutor.readNode(minPath)
                if (cur != null) {
                    val cores = p.relatedCpus.trim().split(Regex("\\s+")).size
                    write(minPath, cores.toString(), "${p.name} 保核在线", steps)
                }
            }
        }

        // 4) 高通：释放每簇频率上限（格式 "0:1996800 4:2500000"）
        if (o.qtiUnlockCluster && profile.socVendor == SocVendor.QTI) {
            if (profile.nodes[MSM_MAX]?.writable == true && profile.cpuPolicies.isNotEmpty()) {
                val value = profile.cpuPolicies.joinToString(" ") { p ->
                    val first = p.relatedCpus.trim().split(Regex("\\s+")).firstOrNull() ?: "0"
                    val hw = if (p.hwMaxKhz > 0) p.hwMaxKhz else p.maxKhz
                    "$first:$hw"
                }
                write(MSM_MAX, value, "高通簇频率上限", steps)
            } else {
                notes += "高通 msm_performance 节点不可用，跳过（不影响已生效的 cpufreq 解锁）"
            }
        }

        // 5) top-app cpuset 放开到全部核心
        if (o.widenTopAppCpuset && profile.nodes[CPUSET_TOP_APP]?.writable == true) {
            val total = profile.cpuPolicies.sumOf {
                it.relatedCpus.trim().split(Regex("\\s+")).count { s -> s.isNotBlank() }
            }
            if (total > 0) {
                val mask = if (total >= 8) "0-$total" else "0-${total.coerceAtLeast(1) - 1}"
                write(CPUSET_TOP_APP, mask, "top-app 可用核心", steps)
            }
        }

        // 6) EAS：把任务往大核搬
        if (o.schedtuneBoost > 0 && profile.nodes[STUNE_BOOST]?.writable == true) {
            write(STUNE_BOOST, o.schedtuneBoost.toString(), "top-app 调度提升", steps)
            if (profile.nodes[STUNE_PREFER_IDLE]?.writable == true) {
                write(STUNE_PREFER_IDLE, "1", "top-app 偏好空闲核", steps)
            }
        }
        if (o.schedBoost && profile.nodes[SCHED_BOOST]?.writable == true) {
            write(SCHED_BOOST, "1", "内核调度加速", steps)
        }

        // 7) 迁移阈值：更早升、更晚降
        if (o.aggressiveMigrate) {
            if (profile.nodes[SCHED_UP]?.writable == true) write(SCHED_UP, "80", "升频迁移阈值", steps)
            if (profile.nodes[SCHED_DOWN]?.writable == true) write(SCHED_DOWN, "60", "降频迁移阈值", steps)
        }

        return finish(steps, notes)
    }

    /** qboost 调频：按百分比设置每个簇的频率上下限 */
    fun applyQboost(profile: DeviceProfile, fp: FreqPercent): Outcome {
        if (!ShizukuManager.isReady()) return notReady("qboost 调频")
        val steps = LinkedHashMap<String, Step>()
        val notes = mutableListOf<String>()

        for (p in profile.cpuPolicies) {
            val base = "$CPUFREQ/${p.name}"
            val hwMax = if (p.hwMaxKhz > 0) p.hwMaxKhz else p.maxKhz
            val hwMin = if (p.hwMinKhz > 0) p.hwMinKhz else p.minKhz
            if (hwMax <= 0) continue

            val span = (hwMax - hwMin).coerceAtLeast(1)
            val targetMin = (hwMin + span * fp.minPct / 100).coerceIn(hwMin, hwMax)
            val targetMax = (hwMin + span * fp.maxPct / 100).coerceIn(hwMin, hwMax)
            // 内核要求 min <= max，留 1kHz 余量
            val safeMin = targetMin.coerceAtMost((targetMax - 1).coerceAtLeast(hwMin))

            // 顺序很关键：先把上限抬上去，再抬下限，否则会出现 min > max 写入失败
            write("$base/scaling_max_freq", targetMax.toString(), "${p.name} 最大 ${fp.maxPct}%", steps)
            write("$base/scaling_min_freq", safeMin.toString(), "${p.name} 最小 ${fp.minPct}%", steps)

            // GPU 同步按最大百分比设上限
            val gpu = profile.gpu
            if (gpu != null && gpu.hwMaxKhz > 0) {
                val gMax = (gpu.minKhz + (gpu.hwMaxKhz - gpu.minKhz) * fp.maxPct / 100)
                write("${gpu.path}/max_freq", gMax.toString(), "GPU 上限 ${fp.maxPct}%", steps)
            }
        }
        return finish(steps, notes)
    }

    /** MTK 调频：PPM / GPU / FPSGO / perfmgr，全部需要 root 且节点高度依赖内核 */
    fun applyMtk(profile: DeviceProfile, o: MtkOptions): Outcome {
        if (!ShizukuManager.isReady()) return notReady("MTK 调频")
        if (profile.socVendor != SocVendor.MTK) {
            return Outcome(0, 0, emptyList(), listOf("当前不是联发科平台（${profile.socVendor}），MTK 节点不存在，已跳过。"))
        }
        val steps = LinkedHashMap<String, Step>()
        val notes = mutableListOf<String>()

        if (o.disablePpm && profile.nodes[PPM_ENABLED]?.writable == true) {
            write(PPM_ENABLED, "0", "关闭 PPM 电源管理", steps)
            notes += "PPM 已关闭：这会绕过联发科的功耗策略引擎，发热与耗电会明显上升。"
        }
        if (o.fixFreqIdx && profile.nodes[PPM_FREQ_IDX]?.writable == true) {
            write(PPM_FREQ_IDX, o.freqIdx, "PPM 定频 $o.freqIdx", steps)
        }
        if (o.fixCoreNum && profile.nodes[PPM_CORE_NUM]?.writable == true) {
            write(PPM_CORE_NUM, o.coreNum, "PPM 定核 ${o.coreNum}", steps)
        }
        if (o.hardMaxFreqKhz > 0 && profile.nodes[PPM_HARD_MAX]?.writable == true) {
            // 格式："<cluster> <freq>"，cluster -1 表示全部簇
            write(PPM_HARD_MAX, "-1 ${o.hardMaxFreqKhz}", "PPM 硬限最高频", steps)
        }

        if (o.fixGpuFreq && o.gpuFreqKhz > 0 && profile.nodes[GPUFREQ_OPP_FREQ]?.writable == true) {
            val dump = PrivilegedExecutor.readNode(GPUFREQ_OPP_DUMP) ?: ""
            if (dump.contains(o.gpuFreqKhz.toString())) {
                write(GPUFREQ_OPP_FREQ, o.gpuFreqKhz.toString(), "GPU 定频", steps)
            } else {
                notes += "目标 GPU 频率不在 gpufreq_opp_dump 档位表中，已跳过。请先读取档位表再填写。"
            }
        }
        if (o.gpuUpboundIdx >= 0 && profile.nodes[GED_UPBOUND]?.writable == true) {
            write(GED_UPBOUND, o.gpuUpboundIdx.toString(), "GED GPU 天花板档位", steps)
        }

        if (o.disableFpsgo) {
            if (profile.nodes[FPSGO_FORCE]?.writable == true) write(FPSGO_FORCE, "0", "关闭 FPSGO", steps)
            if (profile.nodes[FPSGO_ENABLE]?.writable == true) write(FPSGO_ENABLE, "0", "FPSGO 开关置 0", steps)
        }
        if (o.fpsgoLimitLevel >= 0 && profile.nodes[FPSGO_LIMIT_LEVEL]?.writable == true) {
            write(FPSGO_LIMIT_LEVEL, o.fpsgoLimitLevel.toString(), "FPSGO 限频等级", steps)
        }
        if (o.disableTouchBoost && profile.nodes[PERFMGR_TOUCH]?.writable == true) {
            write(PERFMGR_TOUCH, "0", "关闭触摸提频", steps)
        }
        if (o.disableThermal) {
            val r = PrivilegedExecutor.exec("thermal_manager /vendor/etc/.tp/.ht120.mtc 2>&1", 8_000)
            notes += if (r.err.contains("not found", true)) {
                "thermal_manager 不可用，温控屏蔽未生效（热保护仍在，这是安全侧）"
            } else {
                "温控策略已切换。恢复请执行：thermal_manager /vendor/etc/.tp/thermal.conf"
            }
        }
        if (steps.isEmpty() && notes.isEmpty()) {
            notes += "这台联发科设备没有暴露任何可写的 MTK 调频节点，所有选项均未生效。"
        }
        return finish(steps, notes)
    }

    /**
     * 周期维持：把已记录的改动重新写一遍。
     * 这是 qboost 类工具真正生效的关键 —— vendor 的 perf/thermal 守护进程
     * 会周期性把 scaling_max_freq 等节点改回去，不重写就只能维持几秒。
     */
    fun reapply(steps: List<Step>): Int {
        if (!ShizukuManager.isReady()) return 0
        var ok = 0
        for (s in steps) {
            if (!s.ok) continue
            val r = PrivilegedExecutor.writeNode(s.path, s.newValue)
            val now = PrivilegedExecutor.readNode(s.path)
            if (r.ok && now?.trim() == s.newValue.trim()) ok++
        }
        return ok
    }

    /** 按记录逐步回滚 */
    fun revert(steps: List<Step>): Outcome {
        val out = LinkedHashMap<String, Step>()
        for (s in steps.reversed()) {
            if (!s.ok) continue
            write(s.path, s.oldValue, s.label, out)
        }
        return finish(out, mutableListOf())
    }

    // ---------- 内部 ----------

    private fun write(
        path: String,
        value: String,
        label: String,
        acc: MutableMap<String, Step>
    ): Boolean {
        val old = PrivilegedExecutor.readNode(path)
        if (old == null) {
            acc["R:$path"] = Step(path, label, "", value, false)
            LogStore.add("调频", "$label 不可读，跳过", LogStore.Level.WARN)
            return false
        }
        val w: Result = PrivilegedExecutor.writeNode(path, value)
        if (!w.ok) {
            acc["R:$path"] = Step(path, label, old.trim(), value, false)
            // 失败必须显形：带上节点路径与期望值，方便直接判断是权限问题还是内核问题
            val reason = if (w.err.isNotBlank()) w.err else "exit=" + w.exit
            LogStore.add(
                "调频",
                "写入失败 $label\n  路径=$path\n  期望=$value\n  原值=" + old.trim() +
                    "\n  原因=$reason",
                LogStore.Level.ERROR
            )
            return false
        }
        val now = PrivilegedExecutor.readNode(path)
        val success = now != null && now.trim() == value.trim()
        acc["W:$path"] = Step(path, label, old.trim(), value, success)
        if (success) {
            LogStore.add("调频", "$label: ${old.trim()} → $value", LogStore.Level.OK)
        } else {
            // 写进去了但读不回来，把原值塞回去，避免留下半改状态
            PrivilegedExecutor.writeNode(path, old.trim())
            val readBack = now ?: "(空)"
            LogStore.add(
                "调频",
                "写入无效 $label\n  路径=$path\n  期望=$value\n  读回=$readBack" +
                    "\n  已回滚到 " + old.trim(),
                LogStore.Level.ERROR
            )
        }
        return success
    }

    private fun finish(
        acc: MutableMap<String, Step>,
        notes: MutableList<String>
    ): Outcome {
        val list = acc.values.toList()
        val applied = list.count { it.ok }
        val failed = list.count { !it.ok }
        if (failed > 0) {
            val detail = list.filter { !it.ok }.joinToString("\n") { "  · ${it.label}（${it.path}）" }
            LogStore.add(
                "调频",
                "完成：成功 $applied，失败 $failed\n失败项：\n$detail",
                LogStore.Level.ERROR
            )
        }
        if (failed > 0 && applied == 0) {
            notes += "全部写入被拒绝：请确认 Shizuku 以 root 方式运行，且内核未裁剪这些节点。"
        }
        return Outcome(applied, failed, list, notes)
    }

    private fun notReady(name: String): Outcome = Outcome(
        applied = 0,
        failed = 0,
        steps = emptyList(),
        notes = listOf("$name 需要先授权并启动 Shizuku（当前：${ShizukuManager.stateText()}）。"),
        denied = true
    )

    /**
     * 这台机器在**当前身份下**实际能写多少调度节点。
     * 用来给用户一个真实预期，而不是按"是不是 root"拍脑袋判断。
     */
    data class Capability(
        val ready: Boolean,
        val root: Boolean,
        val writableCpu: Int,
        val totalCpu: Int,
        val writableNodes: List<String>,
        val hint: String
    )

    fun capability(profile: DeviceProfile?): Capability {
        val root = ShizukuManager.isRoot()
        val ready = ShizukuManager.isReady()
        if (profile == null) {
            return Capability(ready, root, 0, 0, emptyList(),
                if (ready) "尚未探测，点首页『开始探测』确认这台机器能写哪些节点。"
                else "Shizuku 未就绪。")
        }
        val total = profile.cpuPolicies.size * 2 // scaling_max/min
        val writable = mutableListOf<String>()
        var cpu = 0
        for (p in profile.cpuPolicies) {
            val mx = "/sys/devices/system/cpu/cpufreq/${p.name}/scaling_max_freq"
            val mn = "/sys/devices/system/cpu/cpufreq/${p.name}/scaling_min_freq"
            if (profile.nodes[mx]?.writable == true) { cpu++; writable += mx }
            if (profile.nodes[mn]?.writable == true) { cpu++; writable += mn }
        }
        val hint = when {
            !ready -> "Shizuku 未就绪。"
            cpu == 0 -> "当前身份（${PrivilegedExecutor.identity()}）下 cpufreq 节点均不可写。" +
                "常见于 SELinux enforcing 的 ADB 身份，换 root 身份启动 Shizuku 通常可解。"
            !root -> "ADB/shell 身份，实测可写 $cpu 个 cpufreq 节点——这台机器放开了 sysfs 权限，直接用即可。"
            else -> "root 身份，可写 $cpu 个 cpufreq 节点。"
        }
        return Capability(ready, root, cpu, total, writable, hint)
    }
}
