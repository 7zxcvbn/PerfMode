package com.perfmode.boost.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 命令与状态日志。所有经过 Shizuku 执行的动作都会留痕，便于判断"为什么没生效"。
 */
object LogStore {

    data class Entry(
        val time: String,
        val tag: String,
        val detail: String,
        val level: Level
    )

    enum class Level { INFO, OK, WARN, ERROR }

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    fun add(tag: String, detail: String, level: Level = Level.INFO) {
        val e = Entry(sdf.format(Date()), tag, detail, level)
        _entries.value = (listOf(e) + _entries.value).take(300)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }
}
