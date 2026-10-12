package com.perfmode.boost.perf

import com.perfmode.boost.AppContext
import com.perfmode.boost.device.BoostLevel
import com.perfmode.boost.device.BrandRecipe
import com.perfmode.boost.device.DeviceProfile
import com.perfmode.boost.shizuku.PrivilegedExecutor
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.util.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 性能模式执行引擎。
 *
 * 三档设计（对应研究报告的实现优先级）：
 * - SAFE     ：只读探测 + 官方入口引导，不写入任何值。无风险。
 * - ENHANCED ：在 ADB/shell 身份下探测并写入"这台机器确实存在"的 settings 键，写后读回校验。
 * - EXTREME  ：root 身份下直写 cpufreq / devfreq / sched_boost，逐节点备份 + 校验，失败即回滚。
 *
 * 关键约束：绝不 chmod、绝不批量 echo、绝不写死社区流传的频率数值，
 * 所有目标值都来自设备自己上报的 cpuinfo_max_freq / available_frequencies。
 */
object PerfEngine {

    private const val KEY_SESSION = "active_session"

    enum class Kind { SETTINGS, SYSFS }

    data class AppliedStep(
        val kind: Kind,
        val target: String,
        val oldValue: String,
        val newValue: String,
        val ok: Boolean,
        val label: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("kind", kind.name)
            put("target", target)
            put("old", oldValue)
            put("new", newValue)
            put("ok", ok)
            put("label", label)
        }

        companion object {
            fun fromJson(o: JSONObject): AppliedStep = AppliedStep(
                kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.SYSFS),
                target = o.optString("target", ""),
                oldValue = o.optString("old", ""),
                newValue = o.optString("new", ""),
                ok = o.optBoolean("ok", false),
                label = o.optString("label", "")
            )
        }
    }

    data class BoostOptions(
        val boostGpu: Boolean = true,
        val lockGovernor: Boolean = false,
        val relaxThermal: Boolean = false,
        val vendorHint: Boolean = false
    )

    data class ApplyReport(
        val level: BoostLevel,
        val applied: Int,
        val failed: Int,
        val notes: List<String>
    )

    private val mutex = Mutex()
    private val _steps = MutableStateFlow<List<AppliedStep>>(emptyList())
    val steps: StateFlow<List<AppliedStep>> = _steps.asStateFlow()

    private val _level = MutableStateFlow<BoostLevel?>(null)
    val level: StateFlow<BoostLevel?> = _level.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun isActive(): Boolean = _steps.value.isNotEmpty()

    /** 应用启动或前台服务启动时调用：把上次残留的会话读回来，避免"改了但没人恢复" */
    fun loadPending() {
        val raw = AppContext.prefs().getString(KEY_SESSION, null) ?: return
        val list = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AppliedStep.fromJson(arr.getJSONObject(it)) }
        }.getOrNull() ?: return
        if (list.isNotEmpty()) {
            _steps.value = list
            _level.value = BoostLevel.EXTREME
            LogStore.add("会话", "检测到上次未恢复的修改 ${list.size} 项，可执行恢复", LogStore.Level.WARN)
        }
    }

    suspend fun apply(
        profile: DeviceProfile,
        recipe: BrandRecipe?,
        level: BoostLevel,
        options: BoostOptions
    ): ApplyReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            _busy.value = true
            val acc = LinkedHashMap<String, AppliedStep>()
            val notes = mutableListOf<String>()
            var failed = 0
            var effective = level
            try {
                PrivilegedExecutor.refreshIdentity()

                if (level == BoostLevel.EXTREME && !ShizukuManager.isRoot()) {
                    effective = BoostLevel.ENHANCED
                    notes += "极限档需要 root 身份的 Shizuku（当前 ${PrivilegedExecutor.identity()}），已自动降级为增强档"
                }

                when (effective) {
                    BoostLevel.SAFE -> {
                        notes += "安全档不写入任何系统值，仅完成能力探测。"
                        if (!recipe?.entry.isNullOrBlank()) {
                            notes += "官方入口：${recipe?.entry}"
                        }
                        notes += "建议：先在官方入口开启性能模式，再用『监控』页看频率与温度是否变化。"
                    }
                    BoostLevel.ENHANCED -> {
                        val hit = applySettings(recipe, acc, notes)
                        if (hit == 0) {
                            notes += "未在这台机器上探测到可用的性能模式设置键 —— 这属于正常现象（厂商未暴露或键名不同）。"
                            if (!recipe?.entry.isNullOrBlank()) notes += "请按官方入口手动开启：${recipe?.entry}"
                        }
                        notes += "ADB/shell 身份无法写入 sysfs 调度节点，如需频率级控制请以 root 方式启动 Shizuku。"
                    }
                    BoostLevel.EXTREME -> {
                        applySettings(recipe, acc, notes)
                        val sysfsHit = applySysfs(profile, options, acc, notes)
                        failed = acc.values.count { !it.ok }
                        if (sysfsHit == 0) {
                            notes += "没有任何 sysfs 节点可写（SELinux 或文件权限拒绝）。已停止，避免产生半成品状态。"
                        }
                    }
                }
            } catch (t: Throwable) {
                notes += "执行异常：${t.message}"
                LogStore.add("引擎", "异常 ${t.message}", LogStore.Level.ERROR)
            } finally {
                _busy.value = false
            }

            val applied = acc.values.count { it.ok }
            failed = acc.values.count { !it.ok }
            _steps.value = acc.values.toList()
            _level.value = if (applied > 0 || effective == BoostLevel.SAFE) effective else null
            persist()
            ApplyReport(effective, applied, failed, notes)
        }
    }

    /** 逆序回滚所有已记录步骤 */
    suspend fun restore(): ApplyReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            _busy.value = true
            var ok = 0
            var failed = 0
            val notes = mutableListOf<String>()
            try {
                for (step in _steps.value.reversed()) {
                    val success = when (step.kind) {
                        Kind.SETTINGS -> {
                            val parts = step.target.split(":")
                            if (parts.size == 2) {
                                val r = PrivilegedExecutor.exec("settings put ${parts[0]} ${parts[1]} ${step.oldValue}", 6000)
                                r.err.isBlank()
                            } else false
                        }
                        Kind.SYSFS -> {
                            val r = PrivilegedExecutor.writeNode(step.target, step.oldValue)
                            r.ok
                        }
                    }
                    if (success) ok++ else failed++
                    LogStore.add(
                        "恢复",
                        "${step.label} → ${step.oldValue} ${if (success) "成功" else "失败"}",
                        if (success) LogStore.Level.OK else LogStore.Level.WARN
                    )
                }
            } finally {
                _busy.value = false
            }
            if (failed > 0) {
                notes += "有 $failed 项未能自动恢复。重启设备可让 sysfs 与温控回到内核默认状态。"
            }
            _steps.value = emptyList()
            _level.value = null
            persist()
            ApplyReport(BoostLevel.SAFE, ok, failed, notes)
        }
    }

    // ---------------- 具体实现 ----------------

    private fun applySettings(
        recipe: BrandRecipe?,
        acc: MutableMap<String, AppliedStep>,
        notes: MutableList<String>
    ): Int {
        if (recipe == null) return 0
        var hit = 0
        for (c in recipe.candidates) {
            val list = PrivilegedExecutor.exec("settings list ${c.ns} 2>/dev/null", 8000).out
            if (list.isBlank()) {
                notes += "settings list ${c.ns} 无输出（权限被限制），跳过该命名空间"
                continue
            }
            val rows = list.lineSequence().map { it.trim() }.toList()
            for (key in c.keys) {
                val row = rows.firstOrNull { it.startsWith("$key=") } ?: continue
                val old = row.substringAfter("$key=").trim()
                if (old == c.on) {
                    notes += "$key 已经是 ${c.on}，无需重复开启"
                    hit++
                    continue
                }
                val put = PrivilegedExecutor.exec("settings put ${c.ns} $key ${c.on} 2>&1", 6000)
                val now = PrivilegedExecutor.exec("settings get ${c.ns} $key 2>/dev/null", 6000).out.trim()
                val success = now == c.on && !put.out.contains("error", true)
                acc["S:${c.ns}:$key"] = AppliedStep(
                    kind = Kind.SETTINGS,
                    target = "${c.ns}:$key",
                    oldValue = old,
                    newValue = now,
                    ok = success,
                    label = "设置 ${c.ns}/$key"
                )
                if (success) {
                    hit++
                    LogStore.add("设置", "$key: $old → $now", LogStore.Level.OK)
                } else {
                    notes += "$key 写入未生效（读回 $now），已保留原值。厂商可能拦截了 settings put。"
                    LogStore.add("设置", "$key 未生效", LogStore.Level.WARN)
                }
            }
        }
        return hit
    }

    private fun applySysfs(
        profile: DeviceProfile,
        options: BoostOptions,
        acc: MutableMap<String, AppliedStep>,
        notes: MutableList<String>
    ): Int {
        var hit = 0

        // 1) CPU：按 policy(cluster) 为单位，先抬上限再抬下限
        for (p in profile.cpuPolicies) {
            val base = "/sys/devices/system/cpu/cpufreq/${p.name}"
            val hwMax = if (p.hwMaxKhz > 0) p.hwMaxKhz else p.maxKhz
            if (hwMax <= 0) continue

            if (p.maxKhz < hwMax) {
                if (sysfsStep("$base/scaling_max_freq", hwMax.toString(), "${p.name}(${p.relatedCpus}) 频率上限", acc)) hit++
            }
            // 下限抬到硬件上限的 60%，兼顾唤醒成本与性能；不会超过上限
            val targetMin = (hwMax * 0.6).toLong()
            if (p.minKhz < targetMin && targetMin < hwMax) {
                if (sysfsStep("$base/scaling_min_freq", targetMin.toString(), "${p.name} 频率下限", acc)) hit++
            }
            if (options.lockGovernor && p.availableGovernors.split(",").any { it.trim() == "performance" }) {
                if (sysfsStep("$base/scaling_governor", "performance", "${p.name} 调度策略", acc)) hit++
            }
        }

        // 2) GPU：只抬上限，不改 governor（governor 会被驱动快速覆盖，改动收益不稳）
        val gpu = profile.gpu
        if (options.boostGpu && gpu != null && gpu.hwMaxKhz > 0 && gpu.maxKhz < gpu.hwMaxKhz) {
            if (sysfsStep("${gpu.path}/max_freq", gpu.hwMaxKhz.toString(), "GPU 频率上限", acc)) hit++
        }

        // 3) 通用调度加速节点
        if (profile.nodes["/proc/sys/kernel/sched_boost"]?.writable == true) {
            if (sysfsStep("/proc/sys/kernel/sched_boost", "1", "内核调度加速", acc)) hit++
        }
        if (profile.nodes["/dev/stune/top-app/schedtune.boost"]?.writable == true) {
            if (sysfsStep("/dev/stune/top-app/schedtune.boost", "50", "前台任务组提升", acc)) hit++
        }

        // 4) 厂商专有（实验性，默认关闭）
        if (options.vendorHint) {
            val msm = "/sys/module/msm_performance/parameters/cpu_max_freq"
            if (profile.nodes[msm]?.writable == true && profile.cpuPolicies.isNotEmpty()) {
                val value = profile.cpuPolicies.joinToString(" ") { p ->
                    val first = p.relatedCpus.trim().split(Regex("\\s+")).firstOrNull() ?: "0"
                    val hw = if (p.hwMaxKhz > 0) p.hwMaxKhz else p.maxKhz
                    "$first:$hw"
                }
                if (sysfsStep(msm, value, "高通 msm_performance 上限（实验）", acc)) hit++
            } else {
                notes += "高通专有节点不可用，已跳过（MTK 机型请依赖 perfmgr/FPSGO，本工程不做未经验证的直写）"
            }
        }

        // 5) 温控放宽：默认关闭。写 mode=disabled 会让设备失去热保护，只在用户二选一确认后执行
        if (options.relaxThermal) {
            var relaxed = 0
            for (z in profile.thermalZones) {
                val modePath = "/sys/class/thermal/${z.name}/mode"
                if (PrivilegedExecutor.nodeWritable(modePath)) {
                    if (sysfsStep(modePath, "disabled", "温控 ${z.type.ifBlank { z.name }}", acc)) relaxed++
                }
            }
            if (relaxed == 0) notes += "没有任何温控 zone 允许写入 mode，温控放宽未生效（这是好事，热保护仍在）"
            hit += relaxed
        }

        return hit
    }

    /** 单步写入：读备份 → 写 → 读回校验 → 失败回滚 */
    private fun sysfsStep(
        path: String,
        value: String,
        label: String,
        acc: MutableMap<String, AppliedStep>
    ): Boolean {
        val old = PrivilegedExecutor.readNode(path)
        if (old == null) {
            LogStore.add("写入", "$label 不可读，跳过", LogStore.Level.WARN)
            return false
        }
        val w = PrivilegedExecutor.writeNode(path, value)
        if (!w.ok) {
            LogStore.add("写入", "$label 写入失败：${w.err}", LogStore.Level.WARN)
            return false
        }
        val now = PrivilegedExecutor.readNode(path)
        val success = now != null && now.trim() == value.trim()
        if (success) {
            acc["F:$path"] = AppliedStep(Kind.SYSFS, path, old.trim(), value, true, label)
            LogStore.add("写入", "$label: ${old.trim()} → $value", LogStore.Level.OK)
        } else {
            PrivilegedExecutor.writeNode(path, old.trim())
            LogStore.add("写入", "$label 读回为 $now，已回滚", LogStore.Level.WARN)
        }
        return success
    }

    private fun persist() {
        val arr = JSONArray()
        _steps.value.forEach { arr.put(it.toJson()) }
        AppContext.prefs().edit().putString(KEY_SESSION, arr.toString()).apply()
    }
}
