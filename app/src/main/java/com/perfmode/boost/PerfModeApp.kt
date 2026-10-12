package com.perfmode.boost

import android.app.Application
import com.perfmode.boost.shizuku.ShizukuManager
import com.perfmode.boost.util.CrashGuard

class PerfModeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 必须在最前面：之后任何初始化崩溃都能被记下来
        CrashGuard.install()
        AppContext.init(this)
        ShizukuManager.attach(this)
    }
}
