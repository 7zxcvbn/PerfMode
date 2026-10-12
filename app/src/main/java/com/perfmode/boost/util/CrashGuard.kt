package com.perfmode.boost.util

import android.os.Build
import com.perfmode.boost.AppContext
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃兜底。
 *
 * 用户反馈的"退出一会儿再进就没了"有两种可能：进程被系统回收，或者真的崩了。
 * 装了这个之后，如果是崩溃，日志页会留下完整堆栈，不用再猜。
 *
 * 注意：这里只做记录，不做"自动重启"——崩溃后强行重启容易进入反复崩溃循环。
 */
object CrashGuard {

    private const val KEY_LAST_CRASH = "last_crash"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"

    /** 供协程使用的处理器：子协程抛出时不再静默吞掉 */
    val handler = CoroutineExceptionHandler { _, throwable ->
        record("协程", throwable)
    }

    fun install() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record("线程 ${thread.name}", throwable) }
            // 交还给系统默认处理（弹"已停止运行"并结束进程）
            prev?.uncaughtException(thread, throwable)
        }
    }

    private fun record(where: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stack = sw.toString()
        val head = "${t.javaClass.simpleName}: ${t.message ?: "无信息"}"
        LogStore.add("崩溃", "$where $head", LogStore.Level.ERROR)
        // 完整堆栈截断后也记一条，日志页能直接看到关键几行
        stack.lineSequence().take(12).forEach { line ->
            if (line.isNotBlank()) LogStore.add("崩溃", line.trim(), LogStore.Level.ERROR)
        }
        runCatching {
            AppContext.prefs().edit()
                .putString(KEY_LAST_CRASH, "$head\n$stack")
                .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
                .apply()
        }
    }

    /** 上次崩溃信息，用于启动时提示；没有则空 */
    fun lastCrash(): String {
        return runCatching {
            val p = AppContext.prefs()
            val text = p.getString(KEY_LAST_CRASH, null) ?: return ""
            val time = p.getLong(KEY_LAST_CRASH_TIME, 0L)
            if (time == 0L) return ""
            // 超过 24 小时就当过期，不再提示
            if (System.currentTimeMillis() - time > 24 * 60 * 60 * 1000L) return ""
            text
        }.getOrDefault("")
    }

    fun clearCrash() {
        runCatching { AppContext.prefs().edit().remove(KEY_LAST_CRASH).apply() }
    }

    /** 设备/系统信息，排查时一并带上有用 */
    fun deviceLine(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
