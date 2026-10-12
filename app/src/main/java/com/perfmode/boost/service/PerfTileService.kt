package com.perfmode.boost.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.perfmode.boost.device.BoostLevel
import com.perfmode.boost.device.DeviceProbe
import com.perfmode.boost.device.ProfileRepository
import com.perfmode.boost.perf.PerfEngine
import com.perfmode.boost.perf.PerfEngine.BoostOptions
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.util.LogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 快捷磁贴：一键开启/恢复。
 *
 * 状态机：不可用(Shizuku 未就绪) / 未激活 / 已激活(安全或增强) / 已激活(极限，含 sysfs 修改)。
 * 只做"最近一次档位"，复杂选项仍回到应用内。
 */
class PerfTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        update()
    }

    override fun onClick() {
        if (!ShizukuManager.isReady()) {
            update()
            return
        }
        if (PerfEngine.isActive()) {
            scope.launch {
                PerfEngine.restore()
                PerfForegroundStarter.requestStop()
                update()
            }
            return
        }
        scope.launch {
            val profile = DeviceProbe.probe()
            if (profile == null) {
                LogStore.add("磁贴", "探测失败，未开启", LogStore.Level.ERROR)
                update()
                return@launch
            }
            val device = ProfileRepository.matchDevice(profile)
            val recipe = device?.let { ProfileRepository.recipe(it.brand) }
            val level = if (ShizukuManager.isRoot()) BoostLevel.EXTREME else BoostLevel.ENHANCED
            val report = PerfEngine.apply(profile, recipe, level, BoostOptions())
            if (PerfEngine.isActive()) {
                PerfForegroundStarter.requestStart()
            }
            LogStore.add("磁贴", "开启 ${report.level}：成功 ${report.applied}，失败 ${report.failed}", LogStore.Level.INFO)
            update()
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        // subtitle 自 Android 10(API 29) 起才可用
        val canSubtitle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        when {
            !ShizukuManager.isReady() -> {
                tile.state = Tile.STATE_UNAVAILABLE
                if (canSubtitle) tile.subtitle = "Shizuku 未就绪"
            }
            PerfEngine.isActive() -> {
                tile.state = Tile.STATE_ACTIVE
                if (canSubtitle) {
                    tile.subtitle = when (PerfEngine.level.value) {
                        BoostLevel.EXTREME -> "极限档运行中"
                        BoostLevel.ENHANCED -> "增强档运行中"
                        else -> "运行中"
                    }
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (canSubtitle) tile.subtitle = if (ShizukuManager.isRoot()) "点击开启极限档" else "点击开启增强档"
            }
        }
        tile.updateTile()
    }
}
