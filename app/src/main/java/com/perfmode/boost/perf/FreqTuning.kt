package com.perfmode.boost.perf

/**
 * 调频模块的数据模型。
 *
 * 设计来源：KBattery 的「Unrestrict 策略 / qboost 调频 / MTK 调频」三件套（闭源 App，仅借鉴功能形态），
 * 加上 Qboost 的「最小/最大性能百分比 + 后台周期维持」思路。
 * 节点与语义来自 MTK 官方 FAQ、CAF 内核与社区实测，均需 root 才能写入。
 */

/** qboost 风格：以百分比表达频率上下限。100% = 该簇硬件上限 */
data class FreqPercent(
    val minPct: Int = 0,
    val maxPct: Int = 100
) {
    init {
        require(minPct in 0..100 && maxPct in 0..100) { "百分比必须在 0..100" }
    }
}

/** Unrestrict 策略：解锁频率上限 + 释放大核 */
data class UnrestrictOptions(
    /** 把每个簇的 scaling_max_freq 顶到 cpuinfo_max_freq */
    val unlockMaxFreq: Boolean = true,
    /** 抬高 scaling_min_freq，避免掉到最低频（0 = 不限制下限） */
    val raiseMinFreqPct: Int = 0,
    /** core_ctl min_cpus 拉满，防止大核被下线 */
    val forceBigCoreOnline: Boolean = true,
    /** 高通 msm_performance：释放簇频率上限 */
    val qtiUnlockCluster: Boolean = true,
    /** top-app cpuset 放开到全部核心 */
    val widenTopAppCpuset: Boolean = true,
    /** schedtune.boost（0-100，影响 EAS 把任务搬到大核） */
    val schedtuneBoost: Int = 20,
    /** /proc/sys/kernel/sched_boost（0/1/2） */
    val schedBoost: Boolean = true,
    /** 提升 sched_upmigrate / 降低 downmigrate，让任务更早/更久留在大核 */
    val aggressiveMigrate: Boolean = true
)

/** MTK 调频（天玑 / Helio），全部需要 root */
data class MtkOptions(
    /** PPM 定频：索引越小频率越高，0 = 最高频，-1 = 不限制 */
    val fixFreqIdx: Boolean = false,
    val freqIdx: String = "0 0",
    /** PPM 定核：如 "4 4"（每簇 4 核），-1 -1 解除 */
    val fixCoreNum: Boolean = false,
    val coreNum: String = "-1 -1",
    /** hard_userlimit_max_cpu_freq：硬限最高频（kHz，0 = 不限制） */
    val hardMaxFreqKhz: Long = 0,
    /** 关闭 PPM（/proc/ppm/enabled = 0），激进但有风险 */
    val disablePpm: Boolean = false,
    /** GPU 定频（需出现在 gpufreq_opp_dump 中的档位） */
    val fixGpuFreq: Boolean = false,
    val gpuFreqKhz: Long = 0,
    /** GED GPU 天花板 OPP 档位（/d/ged/hal/custom_upbound_gpu_freq） */
    val gpuUpboundIdx: Int = -1,
    /** 关闭 FPSGO（帧率稳定模块，关掉后调度更激进） */
    val disableFpsgo: Boolean = false,
    /** mtk_fpsgo max_freq_limit_level，0 = 不限制 */
    val fpsgoLimitLevel: Int = -1,
    /** 关闭触摸提频（perfmgr tb_enable = 0） */
    val disableTouchBoost: Boolean = false,
    /** 屏蔽温控（thermal_manager 切 .ht120.mtc）——高风险，默认关 */
    val disableThermal: Boolean = false
)

/** 周期维持设置：对抗 vendor 守护进程把频率限制写回去 */
data class KeepAliveOptions(
    val enabled: Boolean = true,
    /** 重写间隔，太短会自己把机器烧热，建议 5s */
    val intervalMs: Long = 5_000L,
    /**
     * 只在屏幕点亮时维持。
     *
     * 默认 false：一切后台/息屏就停维持的话，厂商的 perf/thermal 守护
     * 会在几秒内把频率上限写回默认值，用户回来就看到"功能全没了"。
     * 想要省电可以手动打开，但要接受息屏期间调频失效。
     */
    val onlyWhenScreenOn: Boolean = false
)

data class TuningState(
    val unrestrictOn: Boolean = false,
    val qboostOn: Boolean = false,
    val mtkOn: Boolean = false,
    /** 安卓原生性能强制开启（Settings 层开关，不需要 root） */
    val aospPerfOn: Boolean = false,
    val freq: FreqPercent = FreqPercent(),
    val keepAlive: KeepAliveOptions = KeepAliveOptions(),
    /** 周期维持已经重写了多少轮 */
    val keepAliveRounds: Int = 0,
    /** 最近一次维持的时间戳 */
    val lastKeepAliveAt: Long = 0L
)
