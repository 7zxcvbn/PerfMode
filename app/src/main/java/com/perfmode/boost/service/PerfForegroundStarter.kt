package com.perfmode.boost.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.perfmode.boost.AppContext

/**
 * 前台服务启停入口。
 * Android 12+ 后台启动前台服务受限，因此只允许从 Activity / Tile / 通知按钮这类用户可见交互调用。
 */
object PerfForegroundStarter {

    fun requestStart() {
        val ctx = AppContext.get()
        val intent = Intent(ctx, PerfForegroundService::class.java).setAction(PerfForegroundService.ACTION_START)
        start(ctx, intent)
    }

    fun requestStop() {
        val ctx = AppContext.get()
        val intent = Intent(ctx, PerfForegroundService::class.java).setAction(PerfForegroundService.ACTION_STOP)
        start(ctx, intent)
    }

    fun requestRestore(context: Context) {
        val intent = Intent(context, PerfForegroundService::class.java).setAction(PerfForegroundService.ACTION_RESTORE)
        start(context, intent)
    }

    private fun start(ctx: Context, intent: Intent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
