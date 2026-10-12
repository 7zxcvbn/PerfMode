package com.perfmode.boost

import android.app.Application

object AppContext {

    private var application: Application? = null

    fun init(app: Application) {
        application = app
    }

    fun get(): Application = application ?: error("PerfModeApp 未初始化")

    fun prefs() = get().getSharedPreferences("perf_mode_prefs", Application.MODE_PRIVATE)
}
