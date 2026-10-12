package com.perfmode.boost.shizuku

import android.app.Application
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku 生命周期与授权状态。
 *
 * 关键事实：Shizuku 只负责把 Binder 递给应用，进程身份仍是 shell(ADB 模式) 或 root(root 模式)。
 * 因此本类把身份区分为 READY_ADB / READY_ROOT，UI 与执行器据此决定功能边界。
 */
object ShizukuManager {

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 10086

    enum class State {
        NOT_INSTALLED,   // 未安装 Shizuku
        STOPPED,         // 已安装但服务未运行（非 root 设备每次重启后需重新启动）
        UNAUTHORIZED,    // 服务运行中，但本应用未获授权
        READY_ADB,       // 已授权，shell 身份：可读大多数节点，写 sysfs 通常被拒
        READY_ROOT       // 已授权，root 身份：可写入 sysfs / 属性
    }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private var app: Application? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun attach(application: Application) {
        app = application
        runCatching { Shizuku.addBinderReceivedListener(binderReceived) }
        runCatching { Shizuku.addBinderDeadListener(binderDead) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResult) }
        refresh()
    }

    fun refresh() {
        _state.value = probe()
    }

    private fun installed(): Boolean {
        val ctx = app ?: return false
        return try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (t: Throwable) {
            false
        }
    }

    private fun probe(): State {
        val alive = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        if (!alive) return if (installed()) State.STOPPED else State.NOT_INSTALLED

        val granted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
        if (!granted) return State.UNAUTHORIZED

        // Shizuku 服务以哪个 uid 运行，决定了 newProcess 出来的进程身份
        val uid = try {
            Shizuku.getUid()
        } catch (t: Throwable) {
            -1
        }
        return if (uid == 0) State.READY_ROOT else State.READY_ADB
    }

    fun isReady(): Boolean {
        val s = _state.value
        return s == State.READY_ADB || s == State.READY_ROOT
    }

    fun isRoot(): Boolean = _state.value == State.READY_ROOT

    /** 给文案用的身份描述，比枚举名可读 */
    fun stateText(): String = when (_state.value) {
        State.READY_ROOT -> "已授权 · root 身份"
        State.READY_ADB -> "已授权 · ADB/shell 身份"
        State.UNAUTHORIZED -> "待授权"
        State.STOPPED -> "服务未运行"
        State.NOT_INSTALLED -> "未安装 Shizuku"
    }

    /** 必须在 Activity 中调用（Shizuku 需要前台界面弹出授权） */
    fun requestPermission() {
        if (_state.value == State.UNAUTHORIZED || _state.value == State.READY_ADB || _state.value == State.READY_ROOT) {
            runCatching { Shizuku.requestPermission(REQUEST_CODE) }
        }
    }
}
