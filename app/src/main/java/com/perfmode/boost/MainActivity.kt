package com.perfmode.boost

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.NotificationManagerCompat
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.ui.AppRoot
import com.perfmode.boost.util.LogStore
import com.perfmode.boost.vm.MainViewModel

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermission()

        setContent {
            AppRoot(
                vm = vm,
                onRequestPermission = { ShizukuManager.requestPermission() },
                onOpenShizuku = { openShizuku() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuManager.refresh()
        // 回到前台补写一次：切后台期间节点常被系统写回默认值
        vm.onResume()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
            }
        }
    }

    /** 便捷入口：把用户送到 Shizuku 自己的界面（用于未安装/未启动场景） */
    fun openShizuku() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    setClassName("moe.shizuku.privileged.api", "moe.shizuku.privileged.api.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:moe.shizuku.privileged.api")
                })
            }
        }
        LogStore.add("Shizuku", "已尝试打开 Shizuku 界面", LogStore.Level.INFO)
    }
}
