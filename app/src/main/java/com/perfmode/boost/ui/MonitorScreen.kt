package com.perfmode.boost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perfmode.boost.monitor.Telemetry
import com.perfmode.boost.ui.components.FreqBar
import com.perfmode.boost.ui.components.KeyValueRow
import com.perfmode.boost.ui.components.NoteText
import com.perfmode.boost.ui.components.SectionCard

@Composable
fun MonitorScreen(modifier: Modifier = Modifier) {
    val snap by Telemetry.snapshot.collectAsStateWithLifecycle()
    val running by Telemetry.running.collectAsStateWithLifecycle()
    var interval by remember { mutableLongStateOf(2_000L) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        SectionCard(title = "生效度评分", subtitle = "没有厂商统一状态位，用频率占比 + 调度策略 + 冷却状态复合判断") {
            val score = Telemetry.score(snap)
            LinearProgressIndicator(progress = score / 100f, modifier = Modifier.fillMaxWidth())
            Text(
                "$score / 100",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
            NoteText("判据：CPU 当前频率占上限比例（45）+ 是否 performance 调度（15）+ GPU 占比（25）+ 冷却设备未介入（15）")
        }

        SectionCard(title = "采样控制", subtitle = "采样越频繁越耗电，也会让自己把机器烧热") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1_000L to "1s", 2_000L to "2s", 5_000L to "5s").forEach { (v, label) ->
                    FilterChip(
                        selected = interval == v,
                        onClick = {
                            interval = v
                            Telemetry.stop()
                            Telemetry.start(v)
                        },
                        label = { Text(label) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { Telemetry.start(interval) }, enabled = !running) { Text("开始") }
                OutlinedButton(onClick = { Telemetry.stop() }, enabled = running) { Text("停止") }
                OutlinedButton(onClick = { Telemetry.sampleOnce() }) { Text("采样一次") }
            }
        }

        SectionCard(title = "CPU（按簇）", subtitle = "同一 policy 内的核心共享频率控制，不是每个核独立") {
            if (snap == null || snap!!.cpu.isEmpty()) {
                NoteText("暂无数据，点『采样一次』或先确认 Shizuku 已就绪。")
            } else {
                snap!!.cpu.forEach { c ->
                    FreqBar(
                        label = "${c.policy} · ${c.governor.ifBlank { "—" }}",
                        cur = c.curKhz,
                        max = c.maxKhz,
                        extra = "min ${com.perfmode.boost.ui.components.formatFreq(c.minKhz)}"
                    )
                }
            }
        }

        SectionCard(title = "GPU") {
            val g = snap?.gpu
            if (g == null) {
                NoteText("未探测到 GPU devfreq 节点（部分 ROM 裁剪了 cur_freq）。")
            } else {
                FreqBar(label = "${g.governor.ifBlank { "gpu" }}", cur = g.curKhz, max = g.maxKhz)
                NoteText("节点：${g.path}")
            }
        }

        SectionCard(title = "温度", subtitle = "≥1000 的原始值按毫摄氏度换算；跨设备不要硬编码 zone 编号") {
            val main = Telemetry.mainTemp(snap)
            if (main == null) {
                NoteText("暂无温度数据。")
            } else {
                Text(
                    "${String.format("%.1f", main.second)}°C  (${main.first})",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            snap?.batteryTempDeciC?.let {
                KeyValueRow("电池温度", String.format("%.1f°C", it / 10f))
            }
            snap?.zones?.take(8)?.forEach { z ->
                KeyValueRow(
                    z.type.ifBlank { z.name },
                    "${String.format("%.1f", Telemetry.celsius(z.rawTemp))}°C  (raw ${z.rawTemp})"
                )
            }
        }

        SectionCard(title = "冷却设备", subtitle = "cur_state > 0 说明温控已经介入降频，此时频率上不去不是调度器的问题") {
            val list = snap?.cooling?.filter { it.max > 0 }?.take(10).orEmpty()
            if (list.isEmpty()) NoteText("无可读冷却设备。")
            else list.forEach { c ->
                KeyValueRow(c.type.ifBlank { c.name }, "${c.cur} / ${c.max}")
            }
        }
    }
}
