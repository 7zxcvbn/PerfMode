package com.perfmode.boost.perf

import com.perfmode.boost.shizuku.PrivilegedExecutor
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.util.LogStore

/**
 * 安卓原生性能强制开启。
 *
 * 与 Unrestrict（写 /sys 调度节点）是两条独立的路子：
 * 这里只动 Android 框架层的 Settings 开关键——不需要 root，ADB/shell 身份就能写，
 * 且是系统自己支持的开关，风险远低于直接改内核节点。
 *
 * 目标就是三件事：
 *  1. 关掉一切会主动降频的省电/自适应策略（流畅度）
 *  2. 锁死刷新率区间（稳定帧率，避免帧率在 60/90/120 之间来回跳）
 *  3. 关掉窗口模糊等纯装饰负载（把 GPU 资源让给渲染）
 *
 * 每一步同样遵循「读备份 → 写 → 读回校验」，失败会写清日志而不是静默吞掉。
 */
object AospPerf {

    // ---------- Settings 键 ----------
    private const val LOW_POWER = "low_power"
    private const val ADAPTIVE_BATTERY = "adaptive_battery_management_enabled"
    private const val APP_STANDBY = "app_standby_enabled"
    private const val WINDOW_BLURS = "disable_window_blurs"
    private const val PEAK_REFRESH = "peak_refresh_rate"
    private const val MIN_REFRESH = "min_refresh_rate"

    data class Step(
        val key: String,
        val namespace: String,
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

    data class AospPerfOptions(
        /** 关闭省电模式（会强制降频，是流畅度头号杀手） */
        val disableLowPower: Boolean = true,
        /** 关闭自适应电池（Android 12+ 会按使用习惯限制后台性能） */
        val disableAdaptiveBattery: Boolean = true,
        /** 关闭应用待机分组（防止后台应用被降频） */
        val disableAppStandby: Boolean = true,
        /** 关闭窗口模糊（Android 12+，减轻 GPU 负载，帧率更稳） */
        val disableWindowBlurs: Boolean = true,
        /** 锁定刷新率到设备支持的最高值，消除帧率抖动 */
        val lockRefreshRate: Boolean = true
    )

    // ---------- 工具 ----------

    private fun readSetting(ns: String, key: String): String? {
        val r = PrivilegedExecutor.exec("settings get $ns $key 2>/dev/null", 5_000)
        if (!r.ok && r.out.isBlank()) return null
        val v = r.out.trim()
        if (v.isEmpty() || v == "null" || v.equals("null", true)) return null
        return v
    }

    private fun writeSetting(ns: String, key: String, value: String): Boolean {
        val r = PrivilegedExecutor.exec("settings put $ns $key '$value' 2>&1", 5_000)
        if (!r.ok) {
            LogStore.add(
                "原生性能",
                "$key 写入失败：${r.err.ifBlank { "exit=${r.exit}" }}",
                LogStore.Level.ERROR
            )
            return false
        }
        return true
    }

    /**
     * 探测这台机器支持的最高刷新率。
     * 优先读 peak_refresh_rate（多数厂商已写入），读不到就放弃锁帧，不瞎猜数值。
     */
    fun maxRefreshRate(): Float {
        val direct = readSetting("system", PEAK_REFRESH)?.toFloatOrNull()
        if (direct != null && direct > 0f) return direct
        val minRate = readSetting("system", MIN_REFRESH)?.toFloatOrNull()
        if (minRate != null && minRate > 0f) return minRate
        return 0f
    }

    // ---------- 应用 ----------

    fun apply(o: AospPerfOptions): Outcome {
        if (!ShizukuManager.isReady()) {
            return Outcome(
                0, 0, emptyList(),
                listOf("原生性能需要先授权并启动 Shizuku（当前：${ShizukuManager.stateText()}）。"),
                denied = true
            )
        }

        val steps = LinkedHashMap<String, Step>()
        val notes = mutableListOf<String>()

        if (o.disableLowPower) {
            put("global", LOW_POWER, "0", "关闭省电模式", steps)
        }
        if (o.disableAdaptiveBattery) {
            put("global", ADAPTIVE_BATTERY, "0", "关闭自适应电池", steps)
        }
        if (o.disableAppStandby) {
            put("global", APP_STANDBY, "0", "关闭应用待机", steps)
        }
        if (o.disableWindowBlurs) {
            put("global", WINDOW_BLURS, "1", "关闭窗口模糊", steps)
        }
        if (o.lockRefreshRate) {
            val max = maxRefreshRate()
            if (max > 0f) {
                val v = max.toInt().toString()
                put("system", PEAK_REFRESH, v, "锁定最高刷新率", steps)
                put("system", MIN_REFRESH, v, "抬高最低刷新率", steps)
                notes += "已锁定 ${v}Hz（数值来自系统上报，未写死）"
            } else {
                notes += "读不到系统刷新率配置，跳过锁帧（不影响其他项）"
            }
        }

        val list = steps.values.toList()
        val applied = list.count { it.ok }
        val failed = list.count { !it.ok }
        if (failed > 0) {
            LogStore.add(
                "原生性能",
                "完成：成功 $applied，失败 $failed（失败项见上方明细）",
                LogStore.Level.ERROR
            )
        } else if (applied > 0) {
            LogStore.add("原生性能", "已开启 $applied 项", LogStore.Level.OK)
        }
        return Outcome(applied, failed, list, notes)
    }

    /** 逐项还原到开启前的旧值 */
    fun revert(steps: List<Step>): Outcome {
        if (!ShizukuManager.isReady()) return Outcome(0, 0, emptyList(), listOf("Shizuku 未就绪"))
        var ok = 0
        var bad = 0
        val notes = mutableListOf<String>()
        for (s in steps) {
            if (s.oldValue.isBlank() || s.oldValue == "null") {
                // 原来没有值，只能删掉这个键
                PrivilegedExecutor.exec("settings delete ${s.namespace} ${s.key} 2>&1", 5_000)
                ok++
                continue
            }
            if (writeSetting(s.namespace, s.key, s.oldValue)) {
                ok++
                LogStore.add("原生性能", "还原 ${s.label}: ${s.newValue} → ${s.oldValue}", LogStore.Level.OK)
            } else {
                bad++
                LogStore.add("原生性能", "还原失败 ${s.label}（${s.key}）", LogStore.Level.ERROR)
                notes += "${s.label} 还原失败，请手动执行：settings put ${s.namespace} ${s.key} ${s.oldValue}"
            }
        }
        return Outcome(ok, bad, emptyList(), notes)
    }

    // ---------- 单步：读备份 → 写 → 读回校验 ----------

    private fun put(
        ns: String,
        key: String,
        value: String,
        label: String,
        acc: MutableMap<String, Step>
    ) {
        val old = readSetting(ns, key)
        // 旧值已经等于目标值：记为成功但不重复写
        if (old != null && old.trim() == value) {
            acc["$ns:$key"] = Step(key, ns, label, old.trim(), value, true)
            LogStore.add("原生性能", "$label 已是目标值（$value），跳过", LogStore.Level.INFO)
            return
        }
        if (!writeSetting(ns, key, value)) {
            acc["$ns:$key"] = Step(key, ns, label, old.orEmpty(), value, false)
            LogStore.add(
                "原生性能",
                "$label 写入失败：settings put $ns $key $value 被拒绝",
                LogStore.Level.ERROR
            )
            return
        }
        val now = readSetting(ns, key)
        val success = now != null && now.trim() == value
        acc["$ns:$key"] = Step(key, ns, label, old.orEmpty(), value, success)
        if (success) {
            LogStore.add("原生性能", "$label: ${old ?: "(无)"} → $value", LogStore.Level.OK)
        } else {
            // 写进去了但读不回来，把旧值塞回去，不留半改状态
            if (old != null) writeSetting(ns, key, old)
            LogStore.add(
                "原生性能",
                "$label 读回为 ${now ?: "(空)"}（期望 $value），已回滚",
                LogStore.Level.ERROR
            )
        }
    }
}
