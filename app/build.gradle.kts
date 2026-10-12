plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 编译：不要再应用 org.jetbrains.kotlin.android，会冲突。
    // Compose 编译器插件仍然单独应用。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.perfmode.boost"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.perfmode.boost"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用调试密钥签名：保证 release 包可直接安装
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kotlin JVM 目标（AGP 9 用 kotlin { } 扩展，旧的 kotlinOptions 已废弃）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    // Shizuku：提供以 shell(root) 身份执行系统能力的 Binder
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Backdrop 液态玻璃。它按 Compose Multiplatform 发布，传递依赖 org.jetbrains.compose；
    // 那些与 AndroidX Compose 包名相同（androidx.compose.*）会重复类，
    // 因此排除掉，让它直接使用本工程的 AndroidX Compose（同为 1.12，API 对齐）。
    implementation(libs.backdrop) {
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.animation")
        exclude(group = "org.jetbrains.compose.material")
        exclude(group = "org.jetbrains.compose.material3")
        exclude(group = "org.jetbrains.compose.materialIconsExtended")
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
}
