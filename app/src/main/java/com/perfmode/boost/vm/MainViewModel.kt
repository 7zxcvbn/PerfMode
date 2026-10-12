package com.perfmode.boost.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfmode.boost.device.BoostLevel
import com.perfmode.boost.device.BrandRecipe
import com.perfmode.boost.device.DeviceEntry
import com.perfmode.boost.device.DeviceMatch
import com.perfmode.boost.device.MatchKind
import com.perfmode.boost.device.DeviceProfile
import com.perfmode.boost.device.DeviceProbe
import com.perfmode.boost.device.ProfileRepository
import com.perfmode.boost.monitor.Telemetry
import com.perfmode.boost.perf.PerfEngine
import com.perfmode.boost.perf.TuningSession
import com.perfmode.boost.util.LogStore
import com.perfmode.boost.perf.TuningState
import com.perfmode.boost.perf.PerfEngine.ApplyReport
import com.perfmode.boost.perf.PerfEngine.BoostOptions
import com.perfmode.boost.service.PerfForegroundStarter
import com.perfmode.boost.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    val shizukuState: StateFlow<ShizukuManager.State> = ShizukuManager.state

    private val _profile = MutableStateFlow<DeviceProfile?>(null)
    val profile: StateFlow<DeviceProfile?> = _profile.asStateFlow()

    private val _probing = MutableStateFlow(false)
    private val _probeStage = MutableStateFlow("")
    val probeStage: StateFlow<String> = _probeStage.asStateFlow()
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    private val _report = MutableStateFlow<ApplyReport?>(null)
    val report: StateFlow<ApplyReport?> = _report.asStateFlow()

    private val _brand = MutableStateFlow<String?>(null)
    val brand: StateFlow<String?> = _brand.asStateFlow()

    private val _platform = MutableStateFlow<String?>(null)
    val platform: StateFlow<String?> = _platform.asStateFlow()

    private val _match = MutableStateFlow<DeviceMatch?>(null)
    val match: StateFlow<DeviceMatch?> = _match.asStateFlow()

    private val _device = MutableStateFlow<DeviceEntry?>(null)
    val device: StateFlow<DeviceEntry?> = _device.asStateFlow()

    init {
        PerfEngine.loadPending()
        TuningSession.loadPending()
        Telemetry.start(2_000L)
    }

    /**
     * 回到前台时调用。
     *
     * 场景：切后台/息屏期间，厂商的 perf / thermal 守护进程会把
     * scaling_max_freq 这类节点周期性写回默认值，或者本进程被系统回收后
     * 维持协程一起没了。结果是 UI 上开关还开着，实际早已失效。
     *
     * 这里做一次补写：把已记录的成功步骤重新写一遍，让"开关开着"
     * 和"真的生效"保持一致，而不是让用户困惑地看到功能"自己重置"。
     */
    fun onResume() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!TuningSession.isActive()) return@launch
            if (!ShizukuManager.isReady()) {
                LogStore.add("恢复", "Shizuku 未就绪，跳过补写", LogStore.Level.WARN)
                return@launch
            }
            val before = TuningSession.steps.value.count { it.ok }
            val ok = com.perfmode.boost.perf.Unrestrict.reapply(TuningSession.steps.value)
            if (ok < before) {
                LogStore.add("恢复", "回到前台补写：$ok/$before 项成功", LogStore.Level.WARN)
            } else {
                LogStore.add("恢复", "回到前台补写：$ok 项已在生效", LogStore.Level.OK)
            }
            // 确保维持循环还在跑（进程被杀后它会消失）
            TuningSession.startKeepAlive()
            if (PerfEngine.isActive() || TuningSession.isActive()) {
                PerfForegroundStarter.requestStart()
            }
        }
    }

    fun selectBrand(id: String?) {
        _brand.value = id
        _device.value = null
    }

    fun selectPlatform(id: String?) {
        _platform.value = id
        _device.value = null
    }

    fun selectDevice(entry: DeviceEntry) {
        _device.value = entry
    }

    fun devicesForSelection(): List<DeviceEntry> =
        ProfileRepository.devices(_brand.value, _platform.value)

    fun recipeForSelection(): BrandRecipe? {
        val d = _device.value
        if (d != null) return ProfileRepository.recipe(d.brand)
        return _brand.value?.let { ProfileRepository.recipe(it) }
    }

    fun probe() {
        viewModelScope.launch(Dispatchers.IO) {
            _probing.value = true
            _probeStage.value = "准备中"
            // 分阶段回调：让用户看到进度，也便于定位到底卡在哪一段
            val p = DeviceProbe.probe { cur, total, name ->
                _probeStage.value = "($cur/$total) $name"
            }
            withContext(Dispatchers.Main) {
                _probing.value = false
                _probeStage.value = ""
            }
            if (p != null) {
                _profile.value = p
                val m = ProfileRepository.resolve(p)
                _match.value = m
                if (_device.value == null) {
                    m.device?.let { d ->
                        _device.value = d
                        _brand.value = d.brand
                        _platform.value = d.platformId
                    } ?: run { m.platform?.let { _platform.value = it.id } }
                    LogStore.add("识别", describeMatch(m, p), LogStore.Level.INFO)
                }
            }
        }
    }

    fun apply(level: BoostLevel, options: BoostOptions) {
        viewModelScope.launch {
            var profile = _profile.value
            if (profile == null) {
                _probing.value = true
                profile = withContext(Dispatchers.IO) { DeviceProbe.probe() }
                _probing.value = false
                _profile.value = profile
                if (profile != null && _device.value == null) {
                    val m = ProfileRepository.resolve(profile)
                    _match.value = m
                    m.device?.let { d ->
                        _device.value = d
                        _brand.value = d.brand
                        _platform.value = d.platformId
                    } ?: run { m.platform?.let { _platform.value = it.id } }
                }
            }
            if (profile == null) {
                _report.value = ApplyReport(level, 0, 0, listOf("尚未探测到设备信息：请确认 Shizuku 已授权并处于运行状态，然后重试。"))
                return@launch
            }
            val r = PerfEngine.apply(profile, recipeForSelection(), level, options)
            _report.value = r
            if (level != BoostLevel.SAFE && PerfEngine.isActive()) {
                PerfForegroundStarter.requestStart()
            }
        }
    }

    fun restore() {
        viewModelScope.launch {
            _report.value = PerfEngine.restore()
            PerfForegroundStarter.requestStop()
        }
    }

    fun applyTuning(state: TuningState) {
        viewModelScope.launch {
            var profile = _profile.value
            if (profile == null) {
                _probing.value = true
                profile = withContext(Dispatchers.IO) { DeviceProbe.probe() }
                _probing.value = false
                _profile.value = profile
            }
            if (profile == null) {
                LogStore.add("调频", "尚未探测到设备信息，请先确认 Shizuku 已授权", LogStore.Level.ERROR)
                return@launch
            }
            TuningSession.apply(profile, state)
            if (TuningSession.isActive()) {
                PerfForegroundStarter.requestStart()
            }
        }
    }

    fun revertTuning() {
        viewModelScope.launch {
            TuningSession.revert()
            if (!PerfEngine.isActive()) PerfForegroundStarter.requestStop()
        }
    }

    private fun describeMatch(m: DeviceMatch, p: DeviceProfile): String = when (m.kind) {
        MatchKind.EXACT_MODEL -> "已收录机型：${m.device?.name}"
        MatchKind.BY_BOARD -> "未收录型号 ${p.model}，按芯片识别为 ${m.platform?.name}"
        MatchKind.BY_VENDOR -> "未识别芯片型号（${p.platform}/${p.chipname}），按厂商走 ${m.platform?.name} 配方"
        MatchKind.GENERIC -> "完全未识别，走纯探测通用配方（仅依赖实机暴露的节点）"
    }

    override fun onCleared() {
        super.onCleared()
        Telemetry.stop()
    }
}
