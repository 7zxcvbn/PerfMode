#!/usr/bin/env bash
# 彻底重来：删掉旧仓库状态，用新 zip 重建并强制推送
# 用法：./reset-push.sh          用内置 token
#       ./reset-push.sh ghp_xxx  用新 token
set -e

TOKEN="${1:-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}"
USER=7zxcvbn
REPO=PerfMode

cd ~ || exit 1

echo "==> 1/5 清理旧目录"
rm -rf ~/PerfMode ~/PerfMode_new
mkdir -p ~/PerfMode ~/PerfMode_new

echo "==> 2/5 找 zip"
Z=""
for p in ~/storage/downloads/PerfMode.zip ~/storage/download/PerfMode.zip \
         ~/PerfMode.zip /sdcard/Download/PerfMode.zip ./PerfMode.zip; do
  [ -f "$p" ] && Z="$p" && break
done
[ -z "$Z" ] && { echo "找不到 PerfMode.zip，请先下载"; exit 1; }
echo "使用: $Z"

echo "==> 3/5 解压"
unzip -q -o "$Z" -d ~/PerfMode_new
SRC=~/PerfMode_new
[ -d ~/PerfMode_new/PerfMode ] && SRC=~/PerfMode_new/PerfMode
cp -a "$SRC/." ~/PerfMode/

echo "==> 4/5 验证修复"
if grep -q ColumnScope ~/PerfMode/app/src/main/java/com/perfmode/boost/ui/components/Widgets.kt; then
  echo "修复已就位 OK"
else
  echo "zip 里没有修复，请重新下载最新 PerfMode.zip"
  exit 1
fi

cd ~/PerfMode || exit 1
rm -rf .git
git init -q
git config user.email "$USER@users.noreply.github.com"
git config user.name "$USER"
git add -A
git commit -q -m "fix: imports + newProcess reflection" || echo "(nothing to commit)"
git branch -M main

echo "==> 5/5 推送"
git push -f "https://$USER:$TOKEN@github.com/$USER/$REPO.git" main:main
echo ""
echo "DONE - 去 github.com/$USER/$REPO/actions 看构建"
