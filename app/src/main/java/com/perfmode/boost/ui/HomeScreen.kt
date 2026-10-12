package com.perfmode.boost.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perfmode.boost.device.BoostLevel
import com.perfmode.boost.device.MatchKind
import com.perfmode.boost.perf.PerfEngine
import com.perfmode.boost.perf.PerfEngine.BoostOptions
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.ui.components.KeyValueRow
import com.perfmode.boost.ui.components.NoteText
import com.perfmode.boost.ui.components.SectionCard
import com.perfmode.boost.ui.theme.GlassQuality
import com.perfmode.boost.util.CrashGuard
import com.perfmode.boost.util.KeepAlive
import com.perfmode.boost.vm.MainViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    modifier: Modifier = Modifier,
    glassQuality: GlassQuality = GlassQuality.LIGHT,
    onQualityChange: (GlassQuality) -> Unit = {}
) {
    val shizuku by vm.shizukuState.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val probing by vm.probing.collectAsStateWithLifecycle()
    val probeStage by vm.probeStage.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val brand by vm.brand.collectAsStateWithLifecycle()
    val platform by vm.platform.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val match by vm.match.collectAsStateWithLifecycle()
    val steps by PerfEngine.steps.collectAsStateWithLifecycle()
    val busy by PerfEngine.busy.collectAsStateWithLifecycle()
    val levelState by PerfEngine.level.collectAsStateWithLifecycle()

    var level by remember { mutableStateOf(BoostLevel.ENHANCED) }
    var boostGpu by remember { mutableStateOf(true) }
    var lockGovernor by remember { mutableStateOf(false) }
    var relaxThermal by remember { mutableStateOf(false) }
    var vendorHint by remember { mutableStateOf(false) }
    var confirmDialog by remember { mutableStateOf(false) }

    val devices = remember(brand, platform) { vm.devicesForSelection() }
    val recipe = remember(brand, device) { vm.recipeForSelection() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        // 1. Shizuku 状态
        SectionCard(title = "Shizuku 授权", subtitle = shizukuHint(shizuku)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (shizuku) {
                    ShizukuManager.State.UNAUTHORIZED -> Button(onClick = onRequestPermission) { Text("请求授权") }
                    ShizukuManager.State.READY_ADB, ShizukuManager.State.READY_ROOT ->
                        AssistChip(onClick = {}, label = { Text("已就绪") })
                    else -> OutlinedButton(onClick = onOpenShizuku) { Text("启动 Shizuku") }
                }
            }
            NoteText(
                when (shizuku) {
                    ShizukuManager.State.READY_ROOT -> "当前为 root 身份：可读可写 sysfs，极限档可用。"
                    ShizukuManager.State.READY_ADB -> "当前为 ADB/shell 身份：只读探测与设置键可用，sysfs 直写通常会被 SELinux 拒绝。"
                    ShizukuManager.State.UNAUTHORIZED -> "服务已运行但未授权本应用，点击上方按钮授权。"
                    ShizukuManager.State.STOPPED -> "Shizuku 已安装但服务未运行。非 root 设备每次重启后需重新用无线调试配对启动。"
                    ShizukuManager.State.NOT_INSTALLED -> "未检测到 Shizuku，请先安装并通过无线调试启动。"
                }
            )
        }

        // 2. 设备探测
        SectionCard(title = "当前设备", subtitle = "所有配方都以实机探测结果为准，不靠型号名猜") {
            if (profile == null) {
                Button(onClick = { vm.probe() }, enabled = !probing && shizuku.isReady()) {
                    Text(if (probing) "探测中…" else "开始探测")
                }
                if (probing && probeStage.isNotBlank()) {
                    NoteText("正在探测：$probeStage")
                }
                if (!shizuku.isReady()) NoteText("Shizuku 就绪后才能探测。")
            } else {
                val p = profile!!
                KeyValueRow("厂商 / 型号", "${p.manufacturer} ${p.model}${if (p.modelName.isNotBlank() && !p.model.equals(p.modelName, true)) "（${p.modelName}）" else ""}")
                KeyValueRow("平台 / 板型", "${p.platform} / ${p.board}")
                KeyValueRow("芯片", "${p.chipname.ifBlank { "—" }} · SoC=${p.socVendor}${if (p.socModel.isNotBlank()) " · ${p.socModel}" else ""}")
                if (p.skuCode.isNotBlank()) KeyValueRow("内部代号", p.skuCode)
                KeyValueRow("系统 / 内核", "Android ${p.android} (API ${p.sdk}) · ${p.kernel}")
                KeyValueRow("CPU 簇", "${p.cpuPolicies.size} 个 policy")
                KeyValueRow("GPU 节点", p.gpu?.path ?: "未探测到")
                KeyValueRow("温控 zone / 冷却设备", "${p.thermalZones.size} / ${p.coolingDevices.size}")
                KeyValueRow("可写 CPU 节点", if (p.hasAnyWritableCpuNode) "是（可直写）" else "否（需 root）")
                OutlinedButton(onClick = { vm.probe() }, enabled = !probing) { Text("重新探测") }
                if (probing && probeStage.isNotBlank()) {
                    NoteText("正在探测：$probeStage")
                }
            }
        }

        // 3. 机型选择
        SectionCard(title = "选择机型", subtitle = "先选平台，再选品牌，最后点具体机型") {
            Text("芯片平台", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = platform == null,
                    onClick = { vm.selectPlatform(null) },
                    label = { Text("全部") }
                )
                com.perfmode.boost.device.ProfileRepository.platforms().forEach { p ->
                    FilterChip(
                        selected = platform == p.id,
                        onClick = { vm.selectPlatform(if (platform == p.id) null else p.id) },
                        label = { Text(p.name) }
                    )
                }
            }
            Text(
                "品牌",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = brand == null, onClick = { vm.selectBrand(null) }, label = { Text("全部") })
                com.perfmode.boost.device.ProfileRepository.brands().forEach { b ->
                    FilterChip(
                        selected = brand == b.id,
                        onClick = { vm.selectBrand(if (brand == b.id) null else b.id) },
                        label = { Text(b.name) }
                    )
                }
            }
            Text(
                "机型（${devices.size}）",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            if (devices.isEmpty()) {
                NoteText("当前筛选下没有机型。不影响使用——直接跳过选择，执行时按实机探测结果走。")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.selectPlatform(null); vm.selectBrand(null) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = device == null,
                    onClick = { vm.selectPlatform(null); vm.selectBrand(null) }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("没找到我的机型？用实机探测结果", fontWeight = FontWeight.Medium)
                    Text(
                        "不指定机型。所有动作只看这台机器真实暴露的节点，反而最稳。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            devices.forEach { d ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.selectDevice(d) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = device?.id == d.id, onClick = { vm.selectDevice(d) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.bodyMedium)
                        val pl = com.perfmode.boost.device.ProfileRepository.platform(d.platformId)
                        Text(
                            "${pl?.name ?: d.platformId} · 可信度 ${d.confidence}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 4. 选中机型与官方入口
        SectionCard(title = "适配方案", subtitle = device?.name ?: "未选择具体机型时使用品牌通用方案") {
            match?.let { m ->
                val (txt, color) = when (m.kind) {
                    MatchKind.EXACT_MODEL -> "已收录机型：${m.device?.name}" to MaterialTheme.colorScheme.primary
                    MatchKind.BY_BOARD -> "未收录该型号，已按芯片识别为 ${m.platform?.name}。执行仍以实机探测为准。" to MaterialTheme.colorScheme.onSurfaceVariant
                    MatchKind.BY_VENDOR -> "未识别到具体芯片，按厂商走 ${m.platform?.name} 配方。" to MaterialTheme.colorScheme.onSurfaceVariant
                    MatchKind.GENERIC -> "完全未识别，走纯探测通用配方：只写这台机器真正暴露且可写的节点。" to MaterialTheme.colorScheme.error
                }
                Text(txt, style = MaterialTheme.typography.bodyMedium, color = color)
            }
            if (!recipe?.entry.isNullOrBlank()) KeyValueRow("官方入口", recipe!!.entry)
            if (!recipe?.notes.isNullOrBlank()) NoteText(recipe!!.notes)
            if (device?.note?.isNotBlank() == true) NoteText(device!!.note)
            NoteText(com.perfmode.boost.device.ProfileRepository.disclaimer())
        }

        // 5. 档位与执行
        SectionCard(title = "性能档位", subtitle = "档位越高，收益与风险同时上升") {
            BoostLevel.values().forEach { lv ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { level = lv }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = level == lv, onClick = { level = lv })
                    Column {
                        Text(levelTitle(lv), fontWeight = FontWeight.Medium)
                        Text(levelDesc(lv), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Text("高级选项（仅极限档）", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            OptionRow("提升 GPU 频率上限", boostGpu) { boostGpu = it }
            OptionRow("锁定 performance 调度策略", lockGovernor) { lockGovernor = it }
            OptionRow("放宽温控（高风险，默认关）", relaxThermal) { relaxThermal = it }
            OptionRow("写入厂商专有节点（实验性）", vendorHint) { vendorHint = it }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                Button(
                    onClick = {
                        if (level == BoostLevel.EXTREME && (relaxThermal || lockGovernor)) confirmDialog = true
                        else vm.apply(level, BoostOptions(boostGpu, lockGovernor, relaxThermal, vendorHint))
                    },
                    enabled = !busy && shizuku.isReady()
                ) { Text(if (busy) "执行中…" else "应用") }
                OutlinedButton(onClick = { vm.restore() }, enabled = !busy && steps.isNotEmpty()) {
                    Text("恢复默认")
                }
            }
            if (levelState != null && steps.isNotEmpty()) {
                NoteText("当前会话：${levelTitle(levelState!!)}，已记录 ${steps.size} 项修改，可随时恢复。")
            }
        }

        // 6. 上次执行结果
        report?.let { r ->
            SectionCard(title = "上次执行结果", subtitle = "${levelTitle(r.level)} · 成功 ${r.applied} · 失败 ${r.failed}") {
                r.notes.forEach { NoteText("· $it") }
            }
        }

        CrashCard()
        KeepAliveCard()
        GlassQualityCard(glassQuality, onQualityChange)
    }

    if (confirmDialog) {
        AlertDialog(
            onDismissRequest = { confirmDialog = false },
            title = { Text("确认高风险操作") },
            text = {
                Text(
                    "你勾选了锁定 performance 调度策略或放宽温控。这会让设备明显发热、耗电加快，" +
                        "长时间高负载可能触发系统保护性关机。\n\n" +
                        "本应用会在温度连续超过 47°C 时自动恢复默认；若应用被杀或 Shizuku 掉线，" +
                        "重启设备即可让内核参数回到默认状态。是否继续？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDialog = false
                    vm.apply(level, BoostOptions(boostGpu, lockGovernor, relaxThermal, vendorHint))
                }) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { confirmDialog = false }) { Text("取消") } }
        )
    }
}

private fun shizukuHint(state: ShizukuManager.State) = when (state) {
    ShizukuManager.State.READY_ROOT -> "已授权 · root 身份"
    ShizukuManager.State.READY_ADB -> "已授权 · ADB/shell 身份"
    ShizukuManager.State.UNAUTHORIZED -> "待授权"
    ShizukuManager.State.STOPPED -> "服务未运行"
    ShizukuManager.State.NOT_INSTALLED -> "未安装"
}

private fun ShizukuManager.State.isReady() =
    this == ShizukuManager.State.READY_ADB || this == ShizukuManager.State.READY_ROOT

fun levelTitle(lv: BoostLevel) = when (lv) {
    BoostLevel.SAFE -> "安全档：官方入口"
    BoostLevel.ENHANCED -> "增强档：设置键探测写入"
    BoostLevel.EXTREME -> "极限档：节点直写（需 root）"
}

private fun levelDesc(lv: BoostLevel) = when (lv) {
    BoostLevel.SAFE -> "只探测不写入，引导到系统自带的性能模式开关"
    BoostLevel.ENHANCED -> "在这台机器上真实存在的设置键中开启性能模式，写后读回校验"
    BoostLevel.EXTREME -> "直写 cpufreq / GPU / 调度节点，逐项备份并可一键回滚"
}

@Composable
private fun OptionRow(text: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 后台保活卡片。
 *
 * 只在真正有问题时给出可执行的一步操作，没问题就不打扰。
 */
@Composable
fun KeepAliveCard() {
    val context = LocalContext.current
    val ignored = remember { KeepAlive.isBatteryOptimizationIgnored() }
    val notificationOff = remember { !KeepAlive.areNotificationsEnabled() }
    val aggressive = remember { KeepAlive.isAggressiveRom() }

    if (ignored && !notificationOff && !aggressive) return

    SectionCard(
        title = "后台保活",
        subtitle = "退到后台后被系统回收，再进来应用会重开"
    ) {
        NoteText(
            "本应用只在已开启性能会话时才运行前台服务。没开任何开关时它就是一个普通后台进程，" +
                "系统会在退后台一段时间后把它回收掉——这是 Android 的内存管理策略，不是崩溃。"
        )
        if (!ignored) {
            NoteText(
                "· 电池优化未放行：系统会限制后台运行。",
                MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = {
                if (!KeepAlive.requestIgnoreBatteryOptimization(context)) {
                    KeepAlive.openBatterySettings(context)
                }
            }) { Text("申请电池优化白名单") }
        }
        if (notificationOff) {
            NoteText(
                "· 通知被关闭：前台服务通知显示不出来，看起来就像应用已退出。",
                MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = { KeepAlive.openNotificationSettings(context) }) {
                Text("打开通知设置")
            }
        }
        if (aggressive) {
            NoteText(
                "· 检测到 ${android.os.Build.MANUFACTURER} 系统，其后台策略较激进。" +
                    "建议在系统的「电池 → 后台管理 / 应用自启动」里把本应用设为允许后台运行。"
            )
            OutlinedButton(onClick = {
                if (!KeepAlive.openPowerUsageSummary(context)) {
                    KeepAlive.openBatterySettings(context)
                }
            }) { Text("打开系统电池设置") }
        }
    }
}

/**
 * 上次崩溃提示。只在真的崩过、且是 24 小时内时出现。
 * 有这个就不用再靠猜来判断"被杀"还是"崩溃"。
 */
@Composable
fun CrashCard() {
    val crash = remember { CrashGuard.lastCrash() }
    if (crash.isBlank()) return
    var expanded by remember { mutableStateOf(false) }

    SectionCard(
        title = "上次异常退出",
        subtitle = "不是被系统回收，是真的崩了"
    ) {
        NoteText(
            crash.lineSequence().take(if (expanded) 12 else 2).joinToString("\n"),
            MaterialTheme.colorScheme.error
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开堆栈")
            }
            TextButton(onClick = { CrashGuard.clearCrash() }) { Text("清除") }
        }
    }
}

/**
 * 玻璃画质开关。
 *
 * 放在首页而不是藏进设置：卡顿是用户能直接感受到的问题，
 * 让他自己权衡"好看"和"流畅"，比替他决定更合适。
 */
@Composable
fun GlassQualityCard(
    quality: GlassQuality,
    onQualityChange: (GlassQuality) -> Unit
) {
    SectionCard(
        title = "玻璃画质",
        subtitle = if (quality == GlassQuality.RICH) "高画质：模糊 + 折射，更吃 GPU"
        else "流畅：只做模糊，滚动更稳"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = quality == GlassQuality.LIGHT,
                onClick = { onQualityChange(GlassQuality.LIGHT) },
                label = { Text("流畅") }
            )
            FilterChip(
                selected = quality == GlassQuality.RICH,
                onClick = { onQualityChange(GlassQuality.RICH) },
                label = { Text("高画质") }
            )
        }
        NoteText(
            if (quality == GlassQuality.RICH) {
                "开启折射着色器后，一屏多张卡片同时滚动时可能掉帧。如果觉得卡，切回「流畅」。"
            } else {
                "当前不启用折射着色器，观感接近但帧率更稳。切到「高画质」可以看到真正的液态折射。"
            }
        )
    }
}
