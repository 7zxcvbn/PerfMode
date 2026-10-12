// 顶层构建脚本：只声明插件版本，不在根工程应用
// 注意：AGP 9 的 com.android.application 已内置 Kotlin 编译，
// 不要再单独应用 org.jetbrains.kotlin.android，否则冲突。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
