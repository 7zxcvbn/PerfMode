package com.perfmode.boost.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import com.perfmode.boost.AppContext

/**
 * 后台保活相关的检查与跳转。
 *
 * 为什么需要：本应用只在"已开启性能会话"时才跑前台服务。
 * 没开任何开关时它就是个普通后台进程，iQOO/OriginOS 这类系统会
 * 在退后台几分钟后连进程一起回收 —— 表现就是"再进去应用重开了"。
 *
 * 能做的只有一件事：把用户送到系统设置页，让他手动放行。
 * 任何"互相唤醒""隐藏前台服务通知"的做法在 Android 12+ 上都不可靠，
 * 也不该做。
 */
object KeepAlive {

    /** 是否已加入电池优化白名单（不受待机限制） */
    fun isBatteryOptimizationIgnored(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val pm = AppContext.get().getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(AppContext.get().packageName)
        }.getOrDefault(false)
    }

    /** 跳到本应用的电池优化设置页（比 ACTION_REQUEST_IGNORE 兼容性更好） */
    fun openBatterySettings(context: Context): Boolean {
        return runCatching {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        }.getOrDefault(false)
    }

    /** 直接弹系统白名单确认框（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，可能不可用） */
    fun requestIgnoreBatteryOptimization(context: Context): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData("package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        }.getOrDefault(false)
    }

    /**
     * 跳到系统的"电池用量"总入口。
     *
     * 注意：POWER_USAGE_SUMMARY 是 **Intent** 的常量，不在 android.provider.Settings 里，
     * 写成 Settings.ACTION_POWER_USAGE_SUMMARY 会 Unresolved reference。
     * 这里直接用字符串字面量，不依赖任何 SDK 常量，最稳。
     */
    fun openPowerUsageSummary(context: Context): Boolean {
        return runCatching {
            val i = Intent("android.intent.action.POWER_USAGE_SUMMARY")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        }.getOrDefault(false)
    }

    /**
     * 是否运行在已知会激进杀后台的 ROM 上。
     * 只用于文案提示，不做任何绕过系统的操作。
     */
    fun isAggressiveRom(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return listOf("vivo", "iqoo", "oppo", "realme", "oneplus", "xiaomi", "huawei", "honor", "samsung")
            .any { m.contains(it) || b.contains(it) }
    }

    /** 通知是否被关闭——通知被关时前台服务通知不显示，用户更容易以为应用"没了" */
    fun areNotificationsEnabled(): Boolean {
        return runCatching {
            androidx.core.app.NotificationManagerCompat.from(AppContext.get()).areNotificationsEnabled()
        }.getOrDefault(true)
    }

    fun openNotificationSettings(context: Context): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            } else {
                openBatterySettings(context)
            }
            true
        }.getOrDefault(false)
    }
}
