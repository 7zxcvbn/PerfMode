package com.perfmode.boost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perfmode.boost.device.SocVendor
import com.perfmode.boost.perf.TuningSession
import com.perfmode.boost.perf.Unrestrict
import com.perfmode.boost.perf.TuningState
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.ui.components.NoteText
import com.perfmode.boost.ui.components.SectionCard
import com.perfmode.boost.ui.components.formatFreq
import com.perfmode.boost.vm.MainViewModel

private val PinkBg = Color(0xFFFCE4EC)
private val PinkAccent = Color(0xFFE5397A)

@Composable
fun TuningScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val shizuku by vm.shizukuState.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val state by TuningSession.state.collectAsStateWithLifecycle()
    val steps by TuningSession.steps.collectAsStateWithLifecycle()
    val busy by TuningSession.busy.collectAsStateWithLifecycle()
    val outcome by TuningSession.lastOutcome.collectAsStateWithLifecycle()
    val aospSteps by TuningSession.aospSteps.collectAsStateWithLifecycle()
    val aospOutcome by TuningSession.lastAospOutcome.collectAsStateWithLifecycle()

    // 不再强制 root：ADB/shell 身份在部分机型上同样可写 sysfs，
    // 能否生效交给探测结果判定，而不是提前把开关锁死。
    val ready = shizuku == ShizukuManager.State.READY_ROOT ||
        shizuku == ShizukuManager.State.READY_ADB
    val isMtk = profile?.socVendor == SocVendor.MTK
    val cap = remember(profile, shizuku) { Unrestrict.capability(profile) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        if (!ready) {
            SectionCard(title = "需要 Shizuku 授权", subtitle = ShizukuManager.stateText()) {
                NoteText(
                    "Unrestrict / qboost / MTK 三套调频要写入 /sys 与 /proc 下的调度节点，" +
                        "需要先授权并启动 Shizuku。\n\n" +
                        "root 身份能写的节点最多，但部分机型 ADB/shell 身份同样可写——" +
                        "能不能用，以首页探测结果为准。",
                    MaterialTheme.colorScheme.error
                )
            }
        } else {
            SectionCard(title = "当前可写性", subtitle = ShizukuManager.stateText()) {
                NoteText(cap.hint)
                if (cap.writableCpu > 0) {
                    NoteText("可写 cpufreq 节点 ${cap.writableCpu} 个：" +
                        cap.writableNodes.take(4).joinToString("、") { it.substringAfterLast("/cpufreq/") } +
                        (if (cap.writableNodes.size > 4) " 等" else ""))
                } else {
                    NoteText("未探测到可写的 cpufreq 节点。点首页『开始探测』后再回来看。",
                        MaterialTheme.colorScheme.error)
                }
            }
        }

        // 0. 安卓原生性能（Settings 层，不需要 root）
        SectionCard(
            title = "强制开启原生安卓性能",
            subtitle = "流畅度 + 稳定帧率 · 不需要 root"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("系统层性能开关", fontWeight = FontWeight.Medium)
                    NoteText("关闭省电与自适应电池、锁定最高刷新率、关闭窗口模糊")
                }
                Switch(
                    checked = state.aospPerfOn,
                    onCheckedChange = {
                        TuningSession.updateState { s -> s.copy(aospPerfOn = it) }
                    },
                    enabled = ready
                )
            }
            NoteText(
                "走 Android 框架自己的 Settings 开关（settings put），ADB 身份即可写入，" +
                    "不像 /sys 节点那样需要 root。\n\n" +
                    "包含：关闭省电模式、关闭自适应电池、关闭应用待机、" +
                    "关闭窗口模糊（Android 12+）、把刷新率锁定在系统上报的最高值。"
            )
            aospOutcome?.let { o ->
                if (o.failed > 0) {
                    NoteText(
                        "上次结果：成功 ${o.applied}，失败 ${o.failed}（明细见日志页）",
                        MaterialTheme.colorScheme.error
                    )
                } else if (o.applied > 0) {
                    NoteText("上次结果：已开启 ${o.applied} 项")
                }
                o.notes.forEach { NoteText("· $it") }
            }
            if (aospSteps.isNotEmpty()) {
                Text(
                    "已生效开关",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                aospSteps.forEach { st ->
                    NoteText(
                        "${if (st.ok) "✓" else "✗"} ${st.label}：${st.oldValue.ifBlank { "(无)" }} → ${st.newValue}",
                        if (st.ok) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // 1. Unrestrict 策略
        SectionCard(title = "开启 Unrestrict 策略", subtitle = "解锁CPU频率上限 / 释放大核性能") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("解锁上限 + 保核在线", fontWeight = FontWeight.Medium)
                    NoteText("把每簇 scaling_max_freq 顶到硬件上限、core_ctl 保大核在线、EAS 把任务往大核搬")
                }
                Switch(
                    checked = state.unrestrictOn,
                    onCheckedChange = {
                        TuningSession.updateState { s -> s.copy(unrestrictOn = it) }
                    },
                    enabled = ready && !busy
                )
            }
        }

        // 2. qboost 调频（强调卡片）
        SectionCard(
            title = "qboost 调频",
            subtitle = if (state.qboostOn && steps.isNotEmpty()) "应用中…" else "按百分比锁定频率上下限"
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PinkBg, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("qboost 调频", fontWeight = FontWeight.Bold, color = PinkAccent)
                        AssistChip(
                            onClick = {},
                            label = { Text("推荐", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.padding(start = 8.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = PinkAccent,
                                labelColor = Color.White
                            )
                        )
                    }
                    Switch(
                        checked = state.qboostOn,
                        onCheckedChange = {
                            TuningSession.updateState { s -> s.copy(qboostOn = it) }
                        },
                        enabled = ready && !busy,
                        colors = SwitchDefaults.colors(checkedTrackColor = PinkAccent)
                    )
                }
                if (state.qboostOn && steps.isNotEmpty()) {
                    Text(
                        "应用中 · 已写入 ${steps.count { it.ok }} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = PinkAccent,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                PercentSlider(
                    label = "最小频率",
                    value = state.freq.minPct,
                    enabled = ready && !busy && state.qboostOn,
                    onValue = { v ->
                        TuningSession.updateState { s ->
                            s.copy(freq = s.freq.copy(minPct = v.coerceAtMost(s.freq.maxPct)))
                        }
                    }
                )
                PercentSlider(
                    label = "最大频率",
                    value = state.freq.maxPct,
                    enabled = ready && !busy && state.qboostOn,
                    onValue = { v ->
                        TuningSession.updateState { s ->
                            s.copy(freq = s.freq.copy(maxPct = v.coerceAtLeast(s.freq.minPct)))
                        }
                    }
                )
                profile?.cpuPolicies?.firstOrNull()?.let { p ->
                    val hwMax = if (p.hwMaxKhz > 0) p.hwMaxKhz else p.maxKhz
                    val hwMin = if (p.hwMinKhz > 0) p.hwMinKhz else p.minKhz
                    NoteText(
                        "参考（${p.name}）：最小 ${formatFreq(hwMin + (hwMax - hwMin) * state.freq.minPct / 100)}，" +
                            "最大 ${formatFreq(hwMin + (hwMax - hwMin) * state.freq.maxPct / 100)}"
                    )
                }
            }
        }

        // 3. 周期维持
        SectionCard(title = "后台周期维持", subtitle = "vendor 守护进程会周期性把频率限制写回去，不重写只能维持几秒") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("持续重写已生效的节点", fontWeight = FontWeight.Medium)
                    NoteText("已维持 ${state.keepAliveRounds} 轮")
                }
                Switch(
                    checked = state.keepAlive.enabled,
                    onCheckedChange = {
                        TuningSession.updateState { s -> s.copy(keepAlive = s.keepAlive.copy(enabled = it)) }
                    },
                    enabled = ready && !busy
                )
            }
            Text(
                "重写间隔：${state.keepAlive.intervalMs / 1000}s",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = (state.keepAlive.intervalMs / 1000).toFloat(),
                onValueChange = {
                    TuningSession.updateState { s ->
                        s.copy(keepAlive = s.keepAlive.copy(intervalMs = it.toLong() * 1000))
                    }
                },
                valueRange = 1f..30f,
                steps = 28,
                enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.keepAlive.onlyWhenScreenOn,
                    onCheckedChange = {
                        TuningSession.updateState { s ->
                            s.copy(keepAlive = s.keepAlive.copy(onlyWhenScreenOn = it))
                        }
                    },
                    enabled = ready && !busy
                )
                Text("仅亮屏时维持（息屏停止，省电）", style = MaterialTheme.typography.bodyMedium)
            }
            NoteText("间隔越短越稳，但维持本身会唤醒 CPU。1–5s 适合游戏，10s 以上适合日常。")
        }

        // 4. MTK 调频
        MtkCard(ready = ready && isMtk, isMtk = isMtk, state = state, busy = busy)

        // 5. 执行
        SectionCard(title = "应用与恢复") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.applyTuning(state) },
                    enabled = ready && !busy && (state.unrestrictOn || state.qboostOn || state.mtkOn)
                ) { Text(if (busy) "执行中…" else "应用调频") }
                OutlinedButton(
                    onClick = { vm.revertTuning() },
                    enabled = !busy && steps.isNotEmpty()
                ) { Text("恢复默认") }
            }
            if (steps.isNotEmpty()) {
                NoteText("当前会话 ${steps.count { it.ok }} 项已生效，切换选项后再点『应用』会先回滚上一轮。")
            }
        }

        outcome?.let { o ->
            SectionCard(title = "上次执行结果", subtitle = "成功 ${o.applied} · 失败 ${o.failed}") {
                o.notes.forEach { NoteText("· $it") }
                o.steps.filter { it.ok }.take(8).forEach { s ->
                    NoteText("✓ ${s.label}：${s.oldValue} → ${s.newValue}")
                }
            }
        }

        // 6. 说明
        SectionCard(title = "功能介绍") {
            NoteText("Unrestrict：解除厂商对 CPU 频率上限的限制，并让大核保持在线、让 EAS 更愿意把任务放到大核。")
            NoteText("qboost 调频：用百分比表达每个簇的频率上下限。最小频率决定「掉不下去」，最大频率决定「冲不上去」。")
            NoteText("周期维持：系统侧的性能/温控守护进程会周期性改回节点值，因此需要一个前台服务按间隔重放已生效的步骤。")
            NoteText("MTK 调频：联发科专有的 PPM / GPU / FPSGO / perfmgr 节点，仅天玑与 Helio 平台存在。")
        }
    }
}

@Composable
private fun PercentSlider(
    label: String,
    value: Int,
    enabled: Boolean,
    onValue: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("$value%", style = MaterialTheme.typography.bodyMedium, color = PinkAccent)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.toInt()) },
            valueRange = 0f..100f,
            steps = 99,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MtkCard(ready: Boolean, isMtk: Boolean, state: TuningState, busy: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    SectionCard(
        title = "MTK 调频",
        subtitle = if (!isMtk) "非联发科平台，节点不存在" else "PPM / GPU / FPSGO / perfmgr"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("联发科专有调频", fontWeight = FontWeight.Medium)
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "展开"
                        )
                    }
                }
                NoteText("需要 root；节点是否暴露取决于内核，未命中会明确告知而不是静默失败")
            }
            Switch(
                checked = state.mtkOn,
                onCheckedChange = { TuningSession.updateState { s -> s.copy(mtkOn = it) } },
                enabled = ready && !busy
            )
        }

        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                NoteText("可写入的节点（探测到才会真正执行）：")
                NoteText("· /proc/ppm/enabled — 关闭 PPM 功耗引擎（激进）")
                NoteText("· /proc/ppm/policy/ut_fix_freq_idx — PPM 定频，索引越小频率越高")
                NoteText("· /proc/ppm/policy/ut_fix_core_num — PPM 定核数")
                NoteText("· /proc/ppm/policy/hard_userlimit_max_cpu_freq — 硬限最高频")
                NoteText("· /proc/gpufreq/gpufreq_opp_freq — GPU 定频（须在档位表内）")
                NoteText("· /d/ged/hal/custom_upbound_gpu_freq — GPU 天花板档位")
                NoteText("· /sys/kernel/debug/fpsgo/common/force_onoff — FPSGO 开关")
                NoteText("· /sys/module/mtk_fpsgo/parameters/max_freq_limit_level — 0 表示不限制")
                NoteText("· /proc/perfmgr/tchbst/kernel/tb_enable — 触摸提频（0 关闭）")

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（仅本地保存）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true
                )
                NoteText("温控屏蔽（thermal_manager）属于高风险动作，本工程默认不启用：热保护失效可能损伤硬件。")
            }
        }
    }
}
