package com.perfmode.boost.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.perfmode.boost.R
import com.perfmode.boost.device.BoostLevel
import com.perfmode.boost.monitor.Telemetry
import com.perfmode.boost.perf.PerfEngine
import com.perfmode.boost.perf.TuningSession
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.util.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 性能模式会话服务。
 *
 * 职责：维持会话、周期采样、在温度越界时自动恢复、提供常驻通知与"立即恢复"入口。
 * Android 12+ 只能从用户可见交互启动，因此入口是 Tile 点击 / Activity 按钮 / 通知按钮。
 */
class PerfForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.perfmode.boost.START"
        const val ACTION_STOP = "com.perfmode.boost.STOP"
        const val ACTION_RESTORE = "com.perfmode.boost.RESTORE"
        const val CHANNEL_ID = "perf_session"
        const val NOTIFICATION_ID = 2048

        /** 温度保护阈值（摄氏度）：越过就自动恢复默认，避免长时间烫手 */
        private const val TEMP_LIMIT_C = 47.0f
        private const val TEMP_HIT_COUNT = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null
    private var overHeatHits = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 关键：服务可能由系统重启（START_STICKY）或被 Task 移除后自救拉起，
        // 这时 ViewModel 还没创建，必须自己先把上次持久化的会话读回来，
        // 否则 isActive() 为 false，维持根本不会启动。
        runCatching {
            PerfEngine.loadPending()
            TuningSession.loadPending()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { PerfEngine.restore(); TuningSession.stopKeepAlive(); stopSelf() }
                return START_NOT_STICKY
            }
            ACTION_RESTORE -> {
                scope.launch {
                    PerfEngine.restore()
                    TuningSession.revert()
                    LogStore.add("会话", "已执行紧急恢复", LogStore.Level.OK)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> startSession()
        }
        return START_STICKY
    }

    private fun startSession() {
        val level = PerfEngine.level.value ?: BoostLevel.ENHANCED
        startForeground(NOTIFICATION_ID, buildNotification(level, null))
        Telemetry.start(2_000L)
        // 再兜一次底：万一 onCreate 的读取失败（比如 SharedPreferences 还没就绪）
        runCatching {
            if (!TuningSession.isActive()) TuningSession.loadPending()
        }
        // 调频会话的周期维持由本服务托管：服务在，维持就在。
        // startKeepAlive 内部会先立刻重放一次，不等第一个 interval。
        if (TuningSession.isActive()) {
            TuningSession.startKeepAlive()
        }
        LogStore.add("会话", "前台服务已启动（$level）", LogStore.Level.INFO)

        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                delay(2_000L)
                val snap = Telemetry.sampleOnce()
                withContext(Dispatchers.Main) {
                    updateNotification(PerfEngine.level.value ?: level, snap)
                }
                // 过热保护：连续多次越限就自动恢复，而不是硬扛
                val temp = Telemetry.mainTemp(snap)?.second
                if (temp != null && temp >= TEMP_LIMIT_C) {
                    overHeatHits++
                    if (overHeatHits >= TEMP_HIT_COUNT) {
                        LogStore.add("保护", "温度 ${"%.1f".format(temp)}°C 连续越限，自动恢复默认", LogStore.Level.WARN)
                        PerfEngine.restore()
                        TuningSession.revert()
                        stopSelf()
                        return@launch
                    }
                } else {
                    overHeatHits = 0
                }
                // 两个会话都空了就别占着前台服务
                if (!PerfEngine.isActive() && !TuningSession.isActive()) {
                    LogStore.add("会话", "没有活跃会话，服务退出", LogStore.Level.INFO)
                    stopSelf()
                    return@launch
                }
                // Shizuku 掉线：没有执行能力就别占着会话
                if (!ShizukuManager.isReady()) {
                    LogStore.add("会话", "Shizuku 不可用，会话结束", LogStore.Level.WARN)
                    PerfEngine.restore()
                    TuningSession.revert()
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        level: BoostLevel,
        snap: Telemetry.Snapshot?
    ): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, com.perfmode.boost.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val restoreIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent(this, RestoreReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val temp = Telemetry.mainTemp(snap)
        val score = Telemetry.score(snap)
        val text = buildString {
            append("档位：${levelName(level)}")
            append(" · 生效度：$score%")
            if (temp != null) append(" · ${temp.first} ${"%.1f".format(temp.second)}°C")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_bolt, getString(R.string.action_restore), restoreIntent
                ).build()
            )
            .build()
    }

    private fun updateNotification(level: BoostLevel, snap: Telemetry.Snapshot?) {
        val nm = NotificationManagerCompat.from(this)
        runCatching {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                nm.notify(NOTIFICATION_ID, buildNotification(level, snap))
            }
        }
    }

    private fun levelName(level: BoostLevel): String = when (level) {
        BoostLevel.SAFE -> "安全（官方入口）"
        BoostLevel.ENHANCED -> "增强（设置键）"
        BoostLevel.EXTREME -> "极限（节点直写）"
    }

    /**
     * 用户从最近任务里划掉本应用时触发。
     * 这里自救一次：只要还有活跃会话，就立刻把自己重新拉起，
     * 否则划掉卡片 = 维持停止 = 节点几秒内被系统写回默认。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        runCatching {
            if (TuningSession.isActive() || PerfEngine.isActive()) {
                val i = Intent(applicationContext, PerfForegroundService::class.java)
                    .setAction(ACTION_START)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(i)
                } else {
                    applicationContext.startService(i)
                }
                LogStore.add("会话", "任务被移除，已自动恢复服务", LogStore.Level.WARN)
            }
        }
    }

    override fun onDestroy() {
        watchJob?.cancel()
        Telemetry.stop()
        TuningSession.stopKeepAlive()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
