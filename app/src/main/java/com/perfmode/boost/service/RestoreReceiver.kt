package com.perfmode.boost.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.perfmode.boost.perf.PerfEngine
import com.perfmode.boost.perf.TuningSession
import com.perfmode.boost.util.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 紧急恢复入口。通知按钮走这里，避免"应用被杀后无法恢复"的死角。
 * 前置条件仍是 Shizuku 在运行且已授权；否则只能建议重启设备。
 */
class RestoreReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                val r = PerfEngine.restore()
                val t = TuningSession.revert()
                LogStore.add(
                    "恢复",
                    "紧急恢复完成：性能档成功 ${r.applied}/失败 ${r.failed}；调频成功 ${t.applied}/失败 ${t.failed}",
                    LogStore.Level.OK
                )
                runCatching {
                    context.startService(
                        Intent(context, PerfForegroundService::class.java)
                            .setAction(PerfForegroundService.ACTION_STOP)
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
