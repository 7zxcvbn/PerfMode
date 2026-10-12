# 阶段 2/5：CPU 频率簇
for d in /sys/devices/system/cpu/cpufreq/policy*; do
  n=${d##*/}
  echo "CPU|$n|$(cat $d/related_cpus 2>/dev/null | tr '\n' ' ')|$(cat $d/scaling_governor 2>/dev/null)|$(cat $d/scaling_cur_freq 2>/dev/null)|$(cat $d/scaling_min_freq 2>/dev/null)|$(cat $d/scaling_max_freq 2>/dev/null)|$(cat $d/cpuinfo_max_freq 2>/dev/null)|$(cat $d/cpuinfo_min_freq 2>/dev/null)|$(cat $d/scaling_available_governors 2>/dev/null | tr ' ' ',')"
done
