# 阶段 4/5：通用 + 高通 + 联发科节点存在性与可写性
for p in /proc/sys/kernel/sched_boost \
  /proc/sys/kernel/sched_upmigrate \
  /proc/sys/kernel/sched_downmigrate \
  /sys/module/msm_performance/parameters/cpu_max_freq \
  /sys/module/msm_performance/parameters/cpu_min_freq \
  /sys/module/msm_performance/parameters/cpu_core_ctl_min_cpu \
  /dev/stune/top-app/schedtune.boost \
  /dev/stune/top-app/schedtune.prefer_idle \
  /dev/cpuset/top-app/cpus \
  /sys/devices/system/cpu/cpu0/core_ctl/min_cpus \
  /sys/devices/system/cpu/cpu4/core_ctl/min_cpus \
  /sys/devices/system/cpu/cpu7/core_ctl/min_cpus \
  /proc/ppm/enabled \
  /proc/ppm/policy/ut_fix_freq_idx \
  /proc/ppm/policy/ut_fix_core_num \
  /proc/ppm/policy/hard_userlimit_max_cpu_freq \
  /proc/gpufreq/gpufreq_opp_freq \
  /sys/module/mtk_fpsgo/parameters/max_freq_limit_level \
  /proc/perfmgr/tchbst/kernel/tb_enable; do
  if [ -e "$p" ]; then echo "N|$p|1|$([ -w "$p" ] && echo 1 || echo 0)|$(cat "$p" 2>/dev/null | head -1 | tr '\n' ' ')"; else echo "N|$p|0|0|"; fi
done
