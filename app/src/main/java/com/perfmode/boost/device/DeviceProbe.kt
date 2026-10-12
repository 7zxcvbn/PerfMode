package com.perfmode.boost.device

import com.perfmode.boost.AppContext
import com.perfmode.boost.shizuku.PrivilegedExecutor
import com.perfmode.boost.util.LogStore

/**
 * 设备能力探测。
 *
 * 结论驱动的设计：机型配方必须由"这台机器真实暴露了什么"来决定，而不是靠型号字符串猜。
 *
 * 关键实现：探测**分 5 个阶段独立执行**，每阶段有自己的超时。
 * 早先是一整段脚本一次性跑完，机器上的 thermal_zone / cooling_device 动辄几十上百个，
 * 每个还要额外 fork 一次 cat，单次执行就会超过超时上限直接失败，
 * 结果连最基础的机型信息都拿不到。拆开之后：
 *   - 任何一阶段超时/失败都只影响该阶段，其余照常返回
 *   - 用户能看到进度，知道卡在哪一段
 *   - 单阶段脚本很短，几乎不会触发超时
 */
object DeviceProbe {

    private data class Stage(
        val asset: String,
        val label: String,
        val timeoutMs: Long
    )

    private val STAGES = listOf(
        Stage("probe_id.sh", "设备身份", 15_000L),
        Stage("probe_cpu.sh", "CPU 频率簇", 20_000L),
        Stage("probe_gpu.sh", "GPU 节点", 20_000L),
        Stage("probe_nodes.sh", "调度节点可写性", 25_000L),
        Stage("probe_thermal.sh", "温控", 20_000L)
    )

    private fun loadScript(name: String): String? =
        runCatching { AppContext.get().assets.open(name).bufferedReader().readText() }.getOrNull()

    /**
     * 分阶段探测。
     * @param onProgress 回调 (当前阶段序号 1-based, 总阶段数, 阶段名)
     */
    fun probe(onProgress: ((Int, Int, String) -> Unit)? = null): DeviceProfile? {
        val sb = StringBuilder()
        var gotAny = false

        // 先自检通道：所有阶段都失败时，这一步能立刻定位是 exec 不通还是脚本问题
        val channel = PrivilegedExecutor.testChannel()
        LogStore.add("探测", "通道自检：$channel", LogStore.Level.INFO)

        for ((i, stage) in STAGES.withIndex()) {
            onProgress?.invoke(i + 1, STAGES.size, stage.label)

            val script = loadScript(stage.asset)
            if (script == null) {
                LogStore.add("探测", "${stage.label}：脚本 ${stage.asset} 缺失，跳过", LogStore.Level.WARN)
                continue
            }

            val r = PrivilegedExecutor.exec(script, stage.timeoutMs)
            when {
                r.timedOut -> {
                    LogStore.add(
                        "探测",
                        "${stage.label} 超时（${stage.timeoutMs}ms），跳过该项",
                        LogStore.Level.WARN
                    )
                }
                r.out.isBlank() -> {
                    LogStore.add(
                        "探测",
                        "${stage.label} 无输出：${r.err.ifBlank { "节点不存在或无权限读取" }}",
                        LogStore.Level.WARN
                    )
                }
                else -> {
                    sb.append(r.out).append('\n')
                    gotAny = true
                }
            }
        }

        if (!gotAny) {
            LogStore.add(
                "探测",
                "失败：所有阶段均无输出。请确认 Shizuku 已授权且服务在运行",
                LogStore.Level.ERROR
            )
            return null
        }

        val profile = parse(sb.toString())
        LogStore.add(
            "探测",
            "完成：${profile.cpuPolicies.size} 个 CPU 簇，GPU=${profile.gpu?.path ?: "无"}，" +
                "可写 CPU 节点=${if (profile.hasAnyWritableCpuNode) "有" else "无"}",
            LogStore.Level.OK
        )
        return profile
    }

    private fun parse(out: String): DeviceProfile {
        var manufacturer = ""
        var brand = ""
        var model = ""
        var modelName = ""
        var board = ""
        var platform = ""
        var chipname = ""
        var hardware = ""
        var socModel = ""
        var skuCode = ""
        var fingerprint = ""
        var android = ""
        var sdk = 0
        var kernel = ""
        val policies = mutableListOf<CpuPolicy>()
        var gpu: GpuInfo? = null
        val zones = mutableListOf<ThermalZone>()
        val coolings = mutableListOf<CoolingDevice>()
        val nodes = mutableMapOf<String, NodeState>()

        for (line in out.lineSequence()) {
            val f = line.split("|")
            when (f.getOrNull(0)) {
                "P" -> { skuCode = f.getOrNull(1).orEmpty().trim() }
                "F" -> { fingerprint = f.getOrNull(1).orEmpty().trim() }
                "D" -> {
                    // 字段顺序必须与 probe_id.sh 的 echo 顺序严格一致：
                    // D|manufacturer|brand|model|modelName|board|platform|chipname|hardware|socModel|android|sdk|kernel
                    manufacturer = f.getOrNull(1).orEmpty().trim()
                    brand = f.getOrNull(2).orEmpty().trim()
                    model = f.getOrNull(3).orEmpty().trim()
                    modelName = f.getOrNull(4).orEmpty().trim()
                    board = f.getOrNull(5).orEmpty().trim()
                    platform = f.getOrNull(6).orEmpty().trim()
                    chipname = f.getOrNull(7).orEmpty().trim()
                    hardware = f.getOrNull(8).orEmpty().trim()
                    socModel = f.getOrNull(9).orEmpty().trim()
                    android = f.getOrNull(10).orEmpty().trim()
                    sdk = f.getOrNull(11)?.trim()?.toIntOrNull() ?: 0
                    kernel = f.getOrNull(12).orEmpty().trim()
                }
                "CPU" -> {
                    val name = f.getOrNull(1) ?: continue
                    policies += CpuPolicy(
                        name = name,
                        relatedCpus = f.getOrNull(2).orEmpty().trim(),
                        governor = f.getOrNull(3).orEmpty().trim(),
                        curKhz = f.getOrNull(4)?.trim()?.toLongOrNull() ?: 0L,
                        minKhz = f.getOrNull(5)?.trim()?.toLongOrNull() ?: 0L,
                        maxKhz = f.getOrNull(6)?.trim()?.toLongOrNull() ?: 0L,
                        hwMaxKhz = f.getOrNull(7)?.trim()?.toLongOrNull()
                            ?: f.getOrNull(6)?.trim()?.toLongOrNull() ?: 0L,
                        hwMinKhz = f.getOrNull(8)?.trim()?.toLongOrNull()
                            ?: f.getOrNull(5)?.trim()?.toLongOrNull() ?: 0L,
                        availableGovernors = f.getOrNull(9).orEmpty().trim()
                    )
                }
                "GPU" -> {
                    val path = f.getOrNull(1) ?: continue
                    gpu = GpuInfo(
                        path = path,
                        curKhz = f.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L,
                        maxKhz = f.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L,
                        hwMaxKhz = f.getOrNull(4)?.trim()?.toLongOrNull()
                            ?: f.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L,
                        minKhz = f.getOrNull(5)?.trim()?.toLongOrNull() ?: 0L,
                        governor = f.getOrNull(6).orEmpty().trim()
                    )
                }
                "TZ" -> zones += ThermalZone(
                    name = f.getOrNull(1).orEmpty(),
                    type = f.getOrNull(2).orEmpty().trim(),
                    rawTemp = f.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L
                )
                "CD" -> coolings += CoolingDevice(
                    name = f.getOrNull(1).orEmpty(),
                    type = f.getOrNull(2).orEmpty().trim(),
                    curState = f.getOrNull(3)?.trim()?.toIntOrNull() ?: -1,
                    maxState = f.getOrNull(4)?.trim()?.toIntOrNull() ?: -1
                )
                "N" -> {
                    val p = f.getOrNull(1) ?: continue
                    nodes[p] = NodeState(
                        exists = f.getOrNull(2) == "1",
                        writable = f.getOrNull(3) == "1",
                        value = f.getOrNull(4).orEmpty().trim()
                    )
                }
            }
        }

        return DeviceProfile(
            manufacturer = manufacturer,
            model = model,
            board = board,
            platform = platform,
            brand = brand,
            modelName = modelName,
            chipname = chipname,
            hardware = hardware,
            socModel = socModel,
            skuCode = skuCode,
            fingerprint = fingerprint,
            android = android,
            sdk = sdk,
            kernel = kernel,
            socVendor = detectVendor(platform, board, chipname, hardware, socModel, skuCode),
            cpuPolicies = policies,
            gpu = gpu,
            thermalZones = zones,
            coolingDevices = coolings,
            nodes = nodes
        )
    }

    private fun detectVendor(
        platform: String,
        board: String,
        chipname: String,
        hardware: String,
        socModel: String,
        skuCode: String
    ): SocVendor {
        val s = "$platform $board $chipname $hardware $socModel $skuCode".lowercase()
        val mtk = listOf("mt6", "mt68", "mt69", "mt87", "mtk", "dimensity")
        val qti = listOf(
            "kalama", "sm8", "sm7", "sdm", "msmnile", "kona", "lahaina", "taro",
            "pineapple", "cliffs", "sun", "qcs", "sd7", "sd8", "msm", "anorak", "volcano"
        )
        return when {
            mtk.any { s.contains(it) } -> SocVendor.MTK
            qti.any { s.contains(it) } -> SocVendor.QTI
            else -> SocVendor.OTHER
        }
    }
}
