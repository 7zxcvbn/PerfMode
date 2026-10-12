package com.perfmode.boost.perf

import com.perfmode.boost.AppContext
import com.perfmode.boost.device.DeviceProfile
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.util.LogStore
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 调频会话管理器：持有 Unrestrict / qboost / MTK 的当前状态与已写入步骤，
 * 并驱动「后台周期维持」循环。
 *
 * 周期维持是刚需：vendor 的 perf / thermal 守护进程会周期性把频率上限写回去，
 * 只写一次通常几秒后就失效。这里由前台服务每 N 秒重放一次已成功步骤。
 */
object TuningSession {

    private const val KEY_STATE = "tuning_state"
    private const val KEY_STEPS = "tuning_steps"
    private const val KEY_AOSP = "tuning_aosp_steps"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var keepJob: Job? = null

    private val _state = MutableStateFlow(TuningState())
    val state: StateFlow<TuningState> = _state.asStateFlow()

    private val _steps = MutableStateFlow<List<Unrestrict.Step>>(emptyList())
    val steps: StateFlow<List<Unrestrict.Step>> = _steps.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _lastOutcome = MutableStateFlow<Unrestrict.Outcome?>(null)
    val lastOutcome: StateFlow<Unrestrict.Outcome?> = _lastOutcome.asStateFlow()

    /** 原生性能（Settings 层）已写入的开关，用于还原 */
    private val _aospSteps = MutableStateFlow<List<AospPerf.Step>>(emptyList())
    val aospSteps: StateFlow<List<AospPerf.Step>> = _aospSteps.asStateFlow()

    private val _lastAospOutcome = MutableStateFlow<AospPerf.Outcome?>(null)
    val lastAospOutcome: StateFlow<AospPerf.Outcome?> = _lastAospOutcome.asStateFlow()

    fun isActive(): Boolean = _steps.value.isNotEmpty() || _aospSteps.value.isNotEmpty()

    fun loadPending() {
        val st = runCatching {
            val o = JSONObject(AppContext.prefs().getString(KEY_STATE, "{}") ?: "{}")
            TuningState(
                unrestrictOn = o.optBoolean("unrestrictOn"),
                qboostOn = o.optBoolean("qboostOn"),
                mtkOn = o.optBoolean("mtkOn"),
                aospPerfOn = o.optBoolean("aospPerfOn"),
                freq = FreqPercent(o.optInt("minPct", 0), o.optInt("maxPct", 100)),
                keepAlive = KeepAliveOptions(
                    enabled = o.optBoolean("kaEnabled", true),
                    intervalMs = o.optLong("kaInterval", 5_000L),
                    onlyWhenScreenOn = o.optBoolean("kaScreenOn", true)
                )
            )
        }.getOrDefault(TuningState())
        val steps = runCatching {
            val arr = JSONArray(AppContext.prefs().getString(KEY_STEPS, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                Unrestrict.Step(
                    path = s.optString("path"),
                    label = s.optString("label"),
                    oldValue = s.optString("old"),
                    newValue = s.optString("new"),
                    ok = s.optBoolean("ok", true)
                )
            }
        }.getOrDefault(emptyList())
        val aosp = runCatching {
            val arr = JSONArray(AppContext.prefs().getString(KEY_AOSP, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                AospPerf.Step(
                    key = s.optString("key"),
                    namespace = s.optString("ns"),
                    label = s.optString("label"),
                    oldValue = s.optString("old"),
                    newValue = s.optString("new"),
                    ok = s.optBoolean("ok", true)
                )
            }
        }.getOrDefault(emptyList())
        if (aosp.isNotEmpty()) _aospSteps.value = aosp

        if (steps.isNotEmpty() || aosp.isNotEmpty()) {
            _state.value = st
            _steps.value = steps
            LogStore.add("调频", "检测到上次未恢复的调频 ${steps.size} 项", LogStore.Level.WARN)
        }
    }

    fun updateState(mutate: (TuningState) -> TuningState) {
        _state.value = mutate(_state.value)
        persistState()
    }

    /** 应用当前勾选的全部策略；会先回滚上一轮，避免叠加出不可预期状态 */
    suspend fun apply(profile: DeviceProfile, state: TuningState): Unrestrict.Outcome =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _busy.value = true
                val acc = mutableListOf<Unrestrict.Step>()
                val notes = mutableListOf<String>()
                var applied = 0
                var failed = 0
                try {
                    stopKeepAlive()
                    if (_steps.value.isNotEmpty()) {
                        Unrestrict.revert(_steps.value)
                        _steps.value = emptyList()
                    }
                    if (!ShizukuManager.isReady()) {
                        return@withContext Unrestrict.Outcome(
                            0, 0, emptyList(),
                            listOf("请先授权并启动 Shizuku（当前：${ShizukuManager.stateText()}）。")
                        ).also { _lastOutcome.value = it; _busy.value = false }
                    }

                    if (state.unrestrictOn) {
                        val r = Unrestrict.applyUnrestrict(profile, UnrestrictOptions())
                        acc += r.steps; notes += r.notes; applied += r.applied; failed += r.failed
                    }
                    if (state.qboostOn) {
                        val r = Unrestrict.applyQboost(profile, state.freq)
                        acc += r.steps; notes += r.notes; applied += r.applied; failed += r.failed
                    }
                    if (state.mtkOn) {
                        val r = Unrestrict.applyMtk(profile, MtkOptions())
                        acc += r.steps; notes += r.notes; applied += r.applied; failed += r.failed
                    }
                    if (state.aospPerfOn) {
                        val r = AospPerf.apply(AospPerf.AospPerfOptions())
                        _aospSteps.value = r.steps
                        _lastAospOutcome.value = r
                        notes += r.notes
                        // Settings 层失败同样计入总数，不静默吞掉
                        r.steps.filter { !it.ok }.forEach {
                            failed += 1
                            notes += "原生性能写入失败：${it.label}（settings put ${it.namespace} ${it.key} ${it.newValue}）"
                        }
                    }
                    if (!state.unrestrictOn && !state.qboostOn && !state.mtkOn && !state.aospPerfOn) {
                        notes += "没有勾选任何策略。"
                    }
                } catch (t: Throwable) {
                    notes += "执行异常：${t.message}"
                } finally {
                    _busy.value = false
                }

                _steps.value = acc
                _lastOutcome.value = Unrestrict.Outcome(applied, failed, acc, notes)
                persistSteps()
                persistState()
                if (acc.isNotEmpty() && state.keepAlive.enabled) startKeepAlive(state.keepAlive)
                _lastOutcome.value!!
            }
        }

    suspend fun revert(): Unrestrict.Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            stopKeepAlive()
            val r = Unrestrict.revert(_steps.value)
            _steps.value = emptyList()
            if (_aospSteps.value.isNotEmpty()) {
                AospPerf.revert(_aospSteps.value)
                _aospSteps.value = emptyList()
                _lastAospOutcome.value = null
            }
            _state.value = TuningState()
            _lastOutcome.value = r
            persistSteps()
            persistState()
            r
        }
    }

    /** 启动周期维持 */
    fun startKeepAlive(options: KeepAliveOptions = _state.value.keepAlive) {
        if (!isActive()) return
        keepJob?.cancel()
        _state.value = _state.value.copy(keepAlive = options)
        val interval = options.intervalMs.coerceAtLeast(1_000L)
        keepJob = scope.launch {
            // 先立刻重放一次：进程刚被拉起时节点多半已被系统写回默认值，
            // 等一个 interval 才写的话，用户回来就看到"功能没了"。
            replayOnce("启动即重写")
            while (isActive) {
                delay(interval)
                if (!ShizukuManager.isReady()) {
                    LogStore.add("维持", "Shizuku 不再可用，维持停止", LogStore.Level.WARN)
                    break
                }
                if (options.onlyWhenScreenOn && !isScreenOn()) continue
                replayOnce(if (options.onlyWhenScreenOn) "亮屏重写" else "周期重写")
            }
        }
        LogStore.add(
            "维持",
            "已启动，间隔 ${interval}ms，息屏${if (options.onlyWhenScreenOn) "暂停" else "继续维持"}",
            LogStore.Level.INFO
        )
    }

    /** 重放一次已成功步骤，返回成功项数 */
    private suspend fun replayOnce(reason: String) {
        mutex.withLock {
            if (_steps.value.isEmpty()) return
            val ok = Unrestrict.reapply(_steps.value)
            _state.value = _state.value.copy(
                keepAliveRounds = _state.value.keepAliveRounds + 1,
                lastKeepAliveAt = System.currentTimeMillis()
            )
            val total = _steps.value.count { it.ok }
            if (ok < total) {
                LogStore.add("维持", "$reason：重写 $ok/$total 项（部分被系统拒绝）", LogStore.Level.WARN)
            }
        }
    }

    fun stopKeepAlive() {
        keepJob?.cancel()
        keepJob = null
    }

    /**
     * 屏幕状态。
     *
     * 用系统 PowerManager 直接查，不再每次 dumpsys 起进程——
     * 之前那样每 5 秒 fork 一次 shell，既慢又费电，还常被超时误判。
     */
    private fun isScreenOn(): Boolean {
        return runCatching {
            val pm = com.perfmode.boost.AppContext.get()
                .getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.isInteractive
        }.getOrDefault(true)
    }

    private fun persistState() {
        val s = _state.value
        val o = JSONObject().apply {
            put("unrestrictOn", s.unrestrictOn)
            put("qboostOn", s.qboostOn)
            put("mtkOn", s.mtkOn)
            put("aospPerfOn", s.aospPerfOn)
            put("minPct", s.freq.minPct)
            put("maxPct", s.freq.maxPct)
            put("kaEnabled", s.keepAlive.enabled)
            put("kaInterval", s.keepAlive.intervalMs)
            put("kaScreenOn", s.keepAlive.onlyWhenScreenOn)
        }
        AppContext.prefs().edit().putString(KEY_STATE, o.toString()).apply()
    }

    private fun persistSteps() {
        val arr = JSONArray()
        _steps.value.forEach { s ->
            arr.put(JSONObject().apply {
                put("path", s.path)
                put("label", s.label)
                put("old", s.oldValue)
                put("new", s.newValue)
                put("ok", s.ok)
            })
        }
        AppContext.prefs().edit().putString(KEY_STEPS, arr.toString()).apply()
        val a = JSONArray()
        _aospSteps.value.forEach { s ->
            a.put(JSONObject().apply {
                put("key", s.key)
                put("ns", s.namespace)
                put("label", s.label)
                put("old", s.oldValue)
                put("new", s.newValue)
                put("ok", s.ok)
            })
        }
        AppContext.prefs().edit().putString(KEY_AOSP, a.toString()).apply()
    }
}
