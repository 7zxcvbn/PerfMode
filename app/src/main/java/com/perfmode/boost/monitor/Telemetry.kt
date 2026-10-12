package com.perfmode.boost.monitor

import com.perfmode.boost.AppContext
import com.perfmode.boost.shizuku.PrivilegedExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 状态采样。
 *
 * 设计要点：
 * - 一次采样 = 一条脚本，避免为每个节点创建进程（进程创建本身会抬高负载）。
 * - 采样频率分档：页面在前台 1s，服务后台 2-5s；频率越高越耗电，也更容易自己把机器烧热。
 * - 温度单位按"≥1000 视为毫摄氏度"换算，并在 UI 上保留原始值，避免跨设备误判。
 */
object Telemetry {

    data class CpuSample(val policy: String, val curKhz: Long, val minKhz: Long, val maxKhz: Long, val governor: String)
    data class GpuSample(val path: String, val curKhz: Long, val maxKhz: Long, val governor: String)
    data class ZoneSample(val name: String, val type: String, val rawTemp: Long)
    data class CoolingSample(val name: String, val type: String, val cur: Int, val max: Int)

    data class Snapshot(
        val timeMillis: Long,
        val cpu: List<CpuSample>,
        val gpu: GpuSample?,
        val zones: List<ZoneSample>,
        val cooling: List<CoolingSample>,
        val batteryTempDeciC: Int?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start(intervalMs: Long = 2_000L) {
        if (job?.isActive == true) return
        _running.value = true
        job = scope.launch {
            while (isActive) {
                val s = runCatching { sampleOnce() }.getOrNull()
                if (s != null) {
                    _snapshot.value = s
                    delay(intervalMs)
                } else {
                    // Shizuku 未就绪时退避，避免空转建立进程
                    delay(intervalMs.coerceAtLeast(5_000L))
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
    }

    fun sampleOnce(): Snapshot? {
        val script = runCatching {
            AppContext.get().assets.open("sample.sh").bufferedReader().readText()
        }.getOrNull() ?: return null
        val r = PrivilegedExecutor.exec(script, 8_000)
        if (r.out.isBlank()) return null
        return parse(r.out)
    }

    private fun parse(out: String): Snapshot {
        val cpu = mutableListOf<CpuSample>()
        var gpu: GpuSample? = null
        val zones = mutableListOf<ZoneSample>()
        val cooling = mutableListOf<CoolingSample>()
        var battery: Int? = null

        for (line in out.lineSequence()) {
            val f = line.split("|")
            when (f.getOrNull(0)) {
                "C" -> cpu += CpuSample(
                    policy = f.getOrNull(1).orEmpty(),
                    curKhz = f.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L,
                    minKhz = f.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L,
                    maxKhz = f.getOrNull(4)?.trim()?.toLongOrNull() ?: 0L,
                    governor = f.getOrNull(5).orEmpty().trim()
                )
                "G" -> gpu = GpuSample(
                    path = f.getOrNull(1).orEmpty(),
                    curKhz = f.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L,
                    maxKhz = f.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L,
                    governor = f.getOrNull(4).orEmpty().trim()
                )
                "TZ" -> zones += ZoneSample(
                    name = f.getOrNull(1).orEmpty(),
                    type = f.getOrNull(2).orEmpty().trim(),
                    rawTemp = f.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L
                )
                "CD" -> cooling += CoolingSample(
                    name = f.getOrNull(1).orEmpty(),
                    type = f.getOrNull(2).orEmpty().trim(),
                    cur = f.getOrNull(3)?.trim()?.toIntOrNull() ?: -1,
                    max = f.getOrNull(4)?.trim()?.toIntOrNull() ?: -1
                )
                "B" -> battery = f.getOrNull(1)?.trim()?.toIntOrNull()
            }
        }
        return Snapshot(System.currentTimeMillis(), cpu, gpu, zones, cooling, battery)
    }

    /** 复合证据评分：没有厂商统一状态位，就用频率占比 + governor + 冷却状态一起判断 */
    fun score(s: Snapshot?): Int {
        if (s == null) return 0
        var sc = 0f
        val ratios = s.cpu.map { if (it.maxKhz <= 0) 0f else it.curKhz.toFloat() / it.maxKhz.toFloat() }
        if (ratios.isNotEmpty()) sc += ratios.average().toFloat() * 45f
        if (s.cpu.any { it.governor == "performance" }) sc += 15f
        val g = s.gpu
        if (g != null && g.maxKhz > 0) sc += (g.curKhz.toFloat() / g.maxKhz.toFloat()) * 25f
        if (s.cooling.isNotEmpty() && s.cooling.all { it.cur <= 0 }) sc += 15f
        return sc.toInt().coerceIn(0, 100)
    }

    /** 温度换算：多数设备为毫摄氏度；<1000 的值按原值当摄氏度处理，UI 同时显示原始值 */
    fun celsius(raw: Long): Float = if (raw >= 1000) raw / 1000f else raw.toFloat()

    /** 取最有代表性的温度：优先 cpu/soc 相关 zone */
    fun mainTemp(s: Snapshot?): Pair<String, Float>? {
        val z = s?.zones?.filter { it.rawTemp > 0 } ?: return null
        val preferred = z.firstOrNull {
            val t = it.type.lowercase()
            t.contains("cpu") || t.contains("soc") || t.contains("tsens") || t.contains("skin")
        } ?: z.firstOrNull()
        return if (preferred == null) null else preferred.type.ifBlank { preferred.name } to celsius(preferred.rawTemp)
    }
}
