#!/usr/bin/env bash
# 用本地新 zip 覆盖已存在的 git 仓库并推送
# remote 里已经带 token，所以不需要再输一次
set -e
cd ~ || exit 1
Z=""
for p in ~/storage/downloads/PerfMode.zip ~/storage/download/PerfMode.zip ~/PerfMode.zip /sdcard/Download/PerfMode.zip ./PerfMode.zip; do
  [ -f "$p" ] && Z="$p" && break
done
[ -z "$Z" ] && { echo "没找到 PerfMode.zip"; exit 1; }
echo "覆盖解压: $Z"
rm -rf ~/PerfMode_stage && mkdir -p ~/PerfMode_stage
unzip -q -o "$Z" -d ~/PerfMode_stage
# 保留 .git，用新文件覆盖
cp -a ~/PerfMode_stage/PerfMode/. ~/PerfMode/ 2>/dev/null || cp -a ~/PerfMode_stage/. ~/PerfMode/
cd ~/PerfMode || exit 1
git add -A
git commit -m "fix: ColumnScope/Spacer/MatchKind imports + newProcess reflection" 2>/dev/null || echo "(无改动)"
git push 2>&1 || git push origin main 2>&1
echo "done"
