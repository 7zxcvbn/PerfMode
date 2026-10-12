package com.perfmode.boost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.perfmode.boost.util.LogStore
import com.perfmode.boost.ui.components.NoteText
import com.perfmode.boost.ui.components.SectionCard

@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val entries by LogStore.entries.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        SectionCard(title = "命令与状态日志", subtitle = "每一条写入都有留痕，便于判断为什么没生效") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { LogStore.clear() }) { Text("清空") }
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (entries.isEmpty()) {
                item { NoteText("暂无日志。") }
            } else {
                items(entries) { e ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            e.time,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "[${e.tag}]",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            e.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (e.level) {
                                LogStore.Level.ERROR -> MaterialTheme.colorScheme.error
                                LogStore.Level.WARN -> Color(0xFFB26A00)
                                LogStore.Level.OK -> Color(0xFF1B7F3B)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
