#!/usr/bin/env bash
# ============================================================
#  PerfMode - fully automatic push to GitHub
#
#  Usage:
#    ./push-all.sh                 # use the token baked in below
#    ./push-all.sh ghp_xxxx        # or pass a fresh token
#
#  What it does:
#    find zip -> unzip -> verify fix -> rebuild git -> force push
# ============================================================

# ---------------- 令牌：必须显式提供，绝不使用内置旧值 ----------------
# 旧令牌一旦失效，脚本若继续用默认值就会一直报
# "Invalid username or token"，所以这里强制要求传入或现场输入。
USER=7zxcvbn
REPO=PerfMode

if [ -n "$1" ]; then
  TOKEN="$1"
else
  printf 'Paste your NEW ghp_ token (invisible): '
  stty -echo 2>/dev/null
  read -r TOKEN
  stty echo 2>/dev/null
  printf '\n'
fi

# 清洗：手机上复制常带不可见字符，这会让 curl 直接拒绝整个 URL
TOKEN=$(printf '%s' "$TOKEN" | tr -cd 'A-Za-z0-9_-')
[ -z "$TOKEN" ] && { echo "No token given. Aborting."; exit 1; }
case "$TOKEN" in
  ghp_*) ;;
  *) echo "WARNING: token does not start with ghp_ - fine-grained tokens often fail here." ;;
esac
echo "Token length: ${#TOKEN} (github classic = 40)"

say()  { printf '%s\n' "$1"; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

say "=============================================="
say " PerfMode  automatic push"
say "=============================================="

# ---------- 0. 存储权限 ----------
if [ ! -d "$HOME/storage/downloads" ]; then
  step "Requesting storage permission"
  command -v termux-setup-storage >/dev/null 2>&1 && {
    say "If it asks to wipe ~/storage -> type  y  then Enter"
    rm -rf "$HOME/storage" 2>/dev/null
    termux-setup-storage
    sleep 3
  }
fi

# ---------- 1. 找 zip（中文名在 zip 里会乱码，两种都试） ----------
step "Locating PerfMode.zip"
Z=""
for p in \
  "$HOME/storage/downloads/PerfMode.zip" \
  "$HOME/storage/download/PerfMode.zip" \
  "$HOME/downloads/PerfMode.zip" \
  "$HOME/PerfMode.zip" \
  "/sdcard/Download/PerfMode.zip" \
  "/storage/emulated/0/Download/PerfMode.zip" \
  "./PerfMode.zip"
do
  [ -f "$p" ] && Z="$p" && break
done

# 按 glob 再找一遍（兼容下载目录里的变体名）
if [ -z "$Z" ]; then
  for base in "$HOME/storage/downloads" "$HOME/storage/download" "$HOME" "/sdcard/Download" "."; do
    [ -d "$base" ] || continue
    f=$(ls "$base" 2>/dev/null | grep -i 'perfmode.*\.zip$' | head -1)
    [ -n "$f" ] && Z="$base/$f" && break
  done
fi

# ---------- 1b. 仍然找不到 -> 系统文件选择器 ----------
if [ -z "$Z" ]; then
  step "Cannot reach storage, using the file picker"
  if ! command -v termux-storage-get >/dev/null 2>&1; then
    say "Installing termux-api..."
    pkg install termux-api -y
  fi
  if command -v termux-storage-get >/dev/null 2>&1; then
    say ""
    say ">>> A file picker will open. Tap:  PerfMode.zip"
    say ""
    printf 'Press Enter to open the picker...'
    read -r _
    termux-storage-get "$HOME/PerfMode.zip" >/dev/null 2>&1
    [ -f "$HOME/PerfMode.zip" ] && Z="$HOME/PerfMode.zip" && say "Got it."
  fi
fi

if [ -z "$Z" ]; then
  say ""
  say "Could not find PerfMode.zip."
  say ""
  say "Try manually:"
  say "   rm -rf ~/storage && termux-setup-storage"
  say "   ls ~/storage/downloads"
  say ""
  say "If still denied: Android Settings > Apps > Termux"
  say "   > Permissions > Files and media > Allow all files"
  exit 1
fi
say "Using: $Z"

# ---------- 2. 干净解压 ----------
step "Unzipping"
command -v unzip >/dev/null 2>&1 || { say "Installing unzip..."; pkg install unzip -y; }

rm -rf "$HOME/PerfMode" "$HOME/_pm_stage"
mkdir -p "$HOME/_pm_stage"
unzip -q -o "$Z" -d "$HOME/_pm_stage" || { say "unzip failed"; exit 1; }

# 解出来可能是 _pm_stage/PerfMode/ 或直接是工程根
SRC="$HOME/_pm_stage"
[ -f "$SRC/settings.gradle.kts" ] || SRC="$HOME/_pm_stage/PerfMode"
if [ ! -f "$SRC/settings.gradle.kts" ]; then
  # 再往下找一层
  D=$(find "$HOME/_pm_stage" -maxdepth 2 -name settings.gradle.kts 2>/dev/null | head -1)
  [ -n "$D" ] && SRC=$(dirname "$D")
fi
if [ ! -f "$SRC/settings.gradle.kts" ]; then
  say "Cannot find settings.gradle.kts inside the zip."
  exit 1
fi
say "Source: $SRC"

mkdir -p "$HOME/PerfMode"
cp -a "$SRC/." "$HOME/PerfMode/"
rm -rf "$HOME/_pm_stage"

# ---------- 3. 验证修复确实在里面 ----------
step "Verifying fixes"
OK=1
grep -q "ColumnScope" "$HOME/PerfMode/app/src/main/java/com/perfmode/boost/ui/components/Widgets.kt" || OK=0
grep -q "MatchKind"    "$HOME/PerfMode/app/src/main/java/com/perfmode/boost/ui/HomeScreen.kt"       || OK=0
grep -q "PROBE_DONE"   "$HOME/PerfMode/app/src/main/assets/probe.sh"                                 || OK=0
grep -q "40_000"       "$HOME/PerfMode/app/src/main/java/com/perfmode/boost/device/DeviceProbe.kt"   || OK=0
if [ "$OK" = "1" ]; then
  say "All fixes present  OK"
else
  say "WARNING: some fixes missing - the zip may be an old one."
  say "Continuing anyway..."
fi

# ---------- 4. 重建 git 并强制推送 ----------
step "Rebuilding git repo"
cd "$HOME/PerfMode" || exit 1
rm -rf .git
command -v git >/dev/null 2>&1 || {
  say "Installing git (a few minutes, keep screen on)..."
  pkg install git -y || { say "git install failed"; exit 1; }
}

git init -q
git config user.email "$USER@users.noreply.github.com"
git config user.name "$USER"
git add -A
git commit -q -m "PerfMode: probe timeout + import fixes" || say "  (nothing to commit)"
git branch -M main

step "Pushing to github.com/$USER/$REPO"
if git push -f "https://$USER:$TOKEN@github.com/$USER/$REPO.git" main:main 2>&1; then
  say ""
  say "=============================================="
  say " SUCCESS"
  say "=============================================="
  say ""
  say "Now open:"
  say "   github.com/$USER/$REPO/actions"
  say ""
  say "Wait 3-5 min for a green check,"
  say "then download from Artifacts (PerfMode-APK)."
  say ""
else
  say ""
  say "PUSH FAILED."
  say ""
  say "If it says authentication failed, the token is dead."
  say "Generate a new one (tick repo + workflow):"
  say "   github.com/settings/tokens"
  say "then run:"
  say "   ./push-all.sh ghp_YOUR_NEW_TOKEN"
  say ""
fi
