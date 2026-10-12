#!/usr/bin/env bash
# ============================================================
#  环境自检：在开始打包前，先确认这台电脑能不能构建
#  用法：./check-env.sh
# ============================================================

# macOS 上 sed -i 需要备份后缀，这里统一处理
OS="$(uname -s)"
RED='\033[0;31m'; GRN='\033[0;32m'; YEL='\033[0;33m'; DIM='\033[2m'; NC='\033[0m'
ok()   { printf "${GRN}✓${NC} %s\n" "$1"; }
bad()  { printf "${RED}✗${NC} %s\n" "$1"; }
warn() { printf "${YEL}!${NC} %s\n" "$1"; }
info() { printf "${DIM}  %s${NC}\n" "$1"; }

FATAL=0

echo "────────────────────────────────────────────"
echo " 环境自检"
echo "────────────────────────────────────────────"

# ---------- 1. Java ----------
echo
echo "【1/4】JDK"
if command -v java >/dev/null 2>&1; then
  # javac 才是编译器；只有 jre 的话 gradle 会报 "Cannot determine java version"
  if command -v javac >/dev/null 2>&1; then
    JV=$(javac -version 2>&1 | grep -oE '[0-9]+' | head -1)
    JV=${JV:-0}
    if [ "$JV" -ge 17 ] 2>/dev/null; then
      ok "JDK $JV（AGP 8.7 需要 17，满足）"
    else
      bad "JDK 版本 $JV，需要 17 或以上"
      info "装 JDK 17：https://adoptium.net/temurin/releases/?version=17"
      FATAL=1
    fi
  else
    bad "找到了 java 但没有 javac —— 装的是 JRE，不是 JDK"
    info "请安装 JDK 17：https://adoptium.net/temurin/releases/?version=17"
    FATAL=1
  fi
else
  bad "没找到 java"
  if [ "$OS" = "Darwin" ]; then
    info "brew install openjdk@17"
  else
    info "Windows：装 Adoptium JDK 17 并勾选『设置 JAVA_HOME 环境变量』"
    info "Linux：sudo apt install openjdk-17-jdk"
  fi
  FATAL=1
fi

# JAVA_HOME 是否指向 17（Gradle 优先用 JAVA_HOME，容易和 PATH 里的 java 打架）
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JH=$("$JAVA_HOME/bin/javac" -version 2>&1 | grep -oE '[0-9]+' | head -1)
  if [ "$JH" -ge 17 ] 2>/dev/null; then
    ok "JAVA_HOME 指向 JDK $JH"
  else
    warn "JAVA_HOME 指向 JDK $JH（<17），Gradle 会用它，建议改掉"
    info "unset JAVA_HOME  或重新指向 JDK 17 目录"
  fi
else
  info "JAVA_HOME 未设置（Gradle 会自动找，通常没问题）"
fi

# ---------- 2. Android SDK ----------
echo
echo "【2/4】Android SDK"
SDK=""
if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ]; then SDK="$ANDROID_HOME"; fi
if [ -z "$SDK" ] && [ -n "$ANDROID_SDK_ROOT" ] && [ -d "$ANDROID_SDK_ROOT" ]; then SDK="$ANDROID_SDK_ROOT"; fi
if [ -z "$SDK" ] && [ -f local.properties ]; then
  SDK=$(grep -E '^\s*sdk\.dir=' local.properties | head -1 | cut -d= -f2- | sed 's/\\\\/\\/g')
fi
# 常见默认位置兜底
if [ -z "$SDK" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" \
           "$LOCALAPPDATA/Android/Sdk" "/usr/lib/android-sdk" "/opt/android-sdk"; do
    [ -d "$c" ] && SDK="$c" && break
  done
fi

if [ -n "$SDK" ] && [ -d "$SDK" ]; then
  ok "SDK：$SDK"
  # 平台 35（compileSdk）
  if [ -d "$SDK/platforms/android-35" ]; then
    ok "已装 Android 15（API 35）平台"
  else
    warn "缺 platforms/android-35 —— 首次编译时会自动下载"
  fi
  if [ -d "$SDK/build-tools" ] && ls "$SDK/build-tools" >/dev/null 2>&1; then
    BT=$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)
    ok "build-tools：$BT"
  else
    warn "缺 build-tools —— 首次编译时会自动下载"
  fi
else
  bad "没找到 Android SDK"
  info "装了 Android Studio 就有，默认在："
  [ "$OS" = "Darwin" ] && info "  ~/Library/Android/sdk"
  [ "$OS" = "Linux" ]  && info "  ~/Android/Sdk"
  info 'Windows 一般在 %LOCALAPPDATA%\Android\Sdk'（即 C:\Users\你\AppData\Local\Android\Sdk）
  info "在本目录建 local.properties 写：sdk.dir=你的路径"
  echo
  info "Windows 路径要用正斜杠或双反斜杠，例如："
  info '  sdk.dir=C\:\\Users\\你\\AppData\\Local\\Android\\Sdk' 
  FATAL=1
fi

# ---------- 3. 构建入口 ----------
echo
echo "【3/4】构建入口"
if [ -f gradlew ] && [ -f gradle/wrapper/gradle-wrapper.jar ]; then
  ok "gradlew 完整，可直接命令行构建"
  WRAPPER_OK=1
else
  warn "工程没有自带 gradlew（这是正常的，我这边无法联网打包 jar）"
  echo
  info "两条路："
  info "  A. 用 Android Studio 打开本目录 —— 它会自动生成 gradlew 并下载 Gradle（推荐）"
  info "  B. 手动生成：先装 gradle 8.9，再执行 gradle wrapper --gradle-version 8.9"
  if command -v gradle >/dev/null 2>&1; then
    GV=$(gradle --version 2>/dev/null | grep -E '^Gradle' | grep -oE '[0-9.]+' | head -1)
    ok "检测到系统 gradle $GV，可用路线 B"
  else
    info "当前没装系统 gradle，只能走路线 A"
  fi
  WRAPPER_OK=0
fi

# ---------- 4. 网络 ----------
echo
echo "【4/4】网络（首次编译要下载 Gradle + 依赖，约几百 MB）"
code(){ curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1" 2>/dev/null || echo 000; }
C1=$(code https://dl.google.com/dl/android/maven2/)
C2=$(code https://repo1.maven.org/maven2/)
if [ "$C1" = "200" ] || [ "$C1" = "301" ] || [ "$C1" = "302" ]; then
  ok "Google Maven 可达（$C1）"
elif [ "$C2" = "200" ] || [ "$C2" = "301" ] || [ "$C2" = "302" ]; then
  ok "Maven Central 可达（$C2）"
else
  warn "直连不通（Google=$C1 / Central=$C2）"
  info "403/000 通常是被墙或代理拦截。解决办法："
  info "  1) 在 Android Studio 里配 HTTP Proxy（Settings > HTTP Proxy）"
  info "  2) 或在 gradle.properties 加阿里云镜像（见 README 打包章节）"
fi

# ---------- 结论 ----------
echo
echo "────────────────────────────────────────────"
if [ "$FATAL" -eq 1 ]; then
  echo "结论：环境还差东西，先按上面的红色提示补齐。"
else
  if [ "$WRAPPER_OK" = "1" ]; then
    echo "结论：环境 OK。直接跑 ./build.sh 就能出 APK。"
  else
    echo "结论：环境 OK。用 Android Studio 打开本目录，"
    echo "      然后 Build > Generate Signed Bundle / APK > APK。"
    echo "      生成 gradlew 之后，之后就能用 ./build.sh 了。"
  fi
fi
echo "────────────────────────────────────────────"
