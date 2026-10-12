#!/usr/bin/env bash
# 一键构建 Release APK
# 前置：JDK 17 + Android SDK（命令行工具或 Android Studio 均可）
# 用法：./build.sh          构建 app/build/outputs/apk/release/app-release.apk
set -e
cd "$(dirname "$0")"

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
  echo "未设置 ANDROID_HOME / ANDROID_SDK_ROOT"
  echo "  方式一：export ANDROID_HOME=\$HOME/Android/Sdk"
  echo "  方式二：在本目录创建 local.properties，写入 sdk.dir=/你的/sdk/路径"
  exit 1
fi

# 没有 gradlew 时先用系统 gradle 生成 wrapper
if [ ! -f gradlew ]; then
  echo "未发现 gradlew，尝试用系统 gradle 生成…"
  gradle wrapper --gradle-version 8.9
fi

chmod +x gradlew
./gradlew clean assembleRelease

APK=$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)
if [ -n "$APK" ]; then
  echo ""
  echo "构建完成：$APK"
  ls -lh "$APK"
else
  echo "未找到产物，请查看上面 Gradle 输出"
  exit 1
fi
