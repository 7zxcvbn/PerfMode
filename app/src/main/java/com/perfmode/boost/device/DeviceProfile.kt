package com.perfmode.boost.device

enum class SocVendor { QTI, MTK, OTHER }

enum class BoostLevel { SAFE, ENHANCED, EXTREME }

data class NodeState(val exists: Boolean, val writable: Boolean, val value: String = "")

data class CpuPolicy(
    val name: String,
    val relatedCpus: String,
    val governor: String,
    val curKhz: Long,
    val minKhz: Long,
    val maxKhz: Long,
    val hwMaxKhz: Long,
    val hwMinKhz: Long,
    val availableGovernors: String
)

data class GpuInfo(
    val path: String,
    val curKhz: Long,
    val maxKhz: Long,
    val hwMaxKhz: Long,
    val minKhz: Long,
    val governor: String
)

data class ThermalZone(val name: String, val type: String, val rawTemp: Long)

data class CoolingDevice(val name: String, val type: String, val curState: Int, val maxState: Int)

data class DeviceProfile(
    val manufacturer: String,
    val brand: String = "",
    val model: String,
    val modelName: String = "",
    val board: String,
    val platform: String,
    val chipname: String,
    val hardware: String = "",
    val socModel: String = "",
    val skuCode: String = "",
    val fingerprint: String = "",
    val android: String,
    val sdk: Int,
    val kernel: String,
    val socVendor: SocVendor,
    val cpuPolicies: List<CpuPolicy>,
    val gpu: GpuInfo?,
    val thermalZones: List<ThermalZone>,
    val coolingDevices: List<CoolingDevice>,
    val nodes: Map<String, NodeState>
) {
    val displayName: String get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")

    val hasAnyWritableCpuNode: Boolean
        get() = cpuPolicies.any { p ->
            nodes["/sys/devices/system/cpu/cpufreq/${p.name}/scaling_max_freq"]?.writable == true ||
                nodes["/sys/devices/system/cpu/cpufreq/${p.name}/scaling_governor"]?.writable == true
        }
}
