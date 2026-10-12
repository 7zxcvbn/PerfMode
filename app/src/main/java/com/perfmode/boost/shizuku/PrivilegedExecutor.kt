package com.perfmode.boost.shizuku

import com.perfmode.boost.util.LogStore
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 统一的特权命令执行器。
 *
 * 设计原则：
 * 1. 一次采样/一次配方合并成单条脚本，避免高频创建进程（进程创建本身就会耗电并触发调度）。
 * 2. 不提供 "su -c" 包装：ADB 模式下 su 不存在，root 模式下 newProcess 本身已是 root。
 *    身份由 ShizukuManager 判断，结果里回传 identity 便于排查。
 * 3. 必须有超时与强制回收，避免命令挂住后前台服务卡死。
 */
object PrivilegedExecutor {

    data class Result(
        val exit: Int,
        val out: String,
        val err: String,
        val timedOut: Boolean,
        val identity: String
    ) {
        val ok: Boolean get() = exit == 0 && !timedOut
    }

    private val pool = Executors.newCachedThreadPool()
    private val env = arrayOf(
        "PATH=/system/bin:/system/xbin:/vendor/bin:/sbin",
        "ANDROID_DATA=/data"
    )

    private var cachedIdentity: String = "unknown"

    /**
     * Shizuku 13.1.5 把 newProcess 声明为 private，直接调用会编译失败。
     * 这里用反射取到它；取不到就返回 null，调用方走降级路径而不是崩溃。
     */
    private val newProcessMethod: Method? by lazy {
        try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            m
        } catch (t: Throwable) {
            null
        }
    }

    fun identity(): String = cachedIdentity

    /**
     * 执行脚本。
     *
     * 关键实现：以「读到 stdout EOF」作为完成信号，**不依赖 Process.waitFor**。
     * Shizuku 返回的是远程 Process，它对 waitFor(timeout) 的语义实现不完整——
     * 即使脚本早已跑完，waitFor 仍会返回"未结束"，导致每次都被误判成超时。
     * 而 shell 执行完必然关闭 stdout，读流到 EOF 是唯一可靠的完成信号。
     */
    fun exec(script: String, timeoutMs: Long = 20_000): Result {
        if (!ShizukuManager.isReady()) {
            return Result(-1, "", "Shizuku 未就绪", false, cachedIdentity)
        }
        var process: Process? = null
        var outFuture: Future<String>? = null
        var errFuture: Future<String>? = null
        try {
            process = newProcessMethod?.invoke(
                null,
                arrayOf("sh", "-c", script),
                env,
                "/"
            ) as? Process
            if (process == null) {
                return Result(-3, "", "无法获取 Shizuku 执行通道（反射未找到 newProcess）", false, cachedIdentity)
            }

            outFuture = pool.submit(Callable {
                runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
            })
            errFuture = pool.submit(Callable {
                runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
            })

            // 等 stdout 关闭；脚本跑完就返回，不受 waitFor 语义影响
            var out = ""
            var timedOut = false
            try {
                out = outFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                timedOut = true
                outFuture.cancel(true)
            }

            val err = if (!timedOut) {
                try {
                    errFuture.get(3, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    errFuture.cancel(true); ""
                }
            } else {
                errFuture.cancel(true); ""
            }

            // exitValue 在进程未退出时抛 IllegalThreadStateException，一律兜住
            val exit = runCatching { process.exitValue() }.getOrDefault(if (timedOut) -9 else 0)

            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.destroy() }
            if (timedOut) runCatching { process.destroyForcibly() }

            return Result(
                exit,
                out.trim(),
                if (timedOut) "执行超时（${timeoutMs}ms）" else err.trim(),
                timedOut,
                cachedIdentity
            )
        } catch (t: Throwable) {
            runCatching { outFuture?.cancel(true) }
            runCatching { errFuture?.cancel(true) }
            runCatching { process?.destroyForcibly() }
            return Result(-2, "", t.message ?: t.javaClass.simpleName, false, cachedIdentity)
        }
    }

    /**
     * 通道自检：跑一条最轻的命令，确认 exec 真的能拿到输出。
     * 探测失败时先做这一步，一眼区分"Shizuku 不通"和"脚本本身有问题"。
     */
    fun testChannel(): String {
        val r = exec("echo __CHANNEL_OK__", 6_000)
        return when {
            r.timedOut -> "超时：进程能创建但读不到输出（waitFor/流异常）"
            r.out.contains("__CHANNEL_OK__") -> "正常：echo 返回成功（exit=${r.exit}）"
            else -> "异常：输出为「${r.out.take(60)}」err=「${r.err.take(60)}」exit=${r.exit}"
        }
    }

    fun readNode(path: String): String? {
        val r = exec("cat '$path' 2>/dev/null", 4_000)
        val v = r.out.trim()
        return if (v.isEmpty() || v == "null") null else v
    }

    fun nodeWritable(path: String): Boolean {
        val r = exec("[ -w '$path' ] && echo yes || echo no", 4_000)
        return r.out.trim() == "yes"
    }

    fun nodeExists(path: String): Boolean {
        val r = exec("[ -e '$path' ] && echo yes || echo no", 4_000)
        return r.out.trim() == "yes"
    }

    /**
     * 写入节点。返回是否"看起来成功"。
     * 注意：真正的判断交给调用方读回校验，这里只负责写出并检查 shell 层错误。
     */
    fun writeNode(path: String, value: String): Result {
        val r = exec("printf '%s' '$value' > '$path' 2>/dev/null; echo done", 4_000)
        val failed = r.err.contains("denied", true) ||
            r.err.contains("Permission", true) ||
            r.err.contains("Read-only", true) ||
            r.out.trim() != "done"
        return if (failed) {
            Result(r.exit, r.out, r.err.ifBlank { "写入被拒绝（可能是文件权限或 SELinux）" }, r.timedOut, cachedIdentity)
        } else {
            r
        }
    }

    /** 执行一次探测以确认 shell 身份（uid），结果缓存 */
    fun refreshIdentity() {
        val r = exec("id -u 2>/dev/null; echo -n '|'; id -un 2>/dev/null", 4_000)
        cachedIdentity = r.out.trim().ifBlank { "unknown" }
        LogStore.add("身份", "shell 身份 = $cachedIdentity", LogStore.Level.INFO)
    }
}
