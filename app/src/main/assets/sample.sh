for d in /sys/devices/system/cpu/cpufreq/policy*; do
  n=${d##*/}
  echo "C|$n|$(cat $d/scaling_cur_freq 2>/dev/null)|$(cat $d/scaling_min_freq 2>/dev/null)|$(cat $d/scaling_max_freq 2>/dev/null)|$(cat $d/scaling_governor 2>/dev/null)"
done
g=""
if [ -d /sys/class/kgsl/kgsl-3d0/devfreq ]; then g=/sys/class/kgsl/kgsl-3d0/devfreq; fi
if [ -z "$g" ]; then
  for d in /sys/class/devfreq/*; do
    l=$(readlink -f "$d" 2>/dev/null)
    case "$l" in *gpu*|*kgsl*|*mali*) g=$d ;; esac
  done
fi
if [ -n "$g" ]; then
  echo "G|$g|$(cat $g/cur_freq 2>/dev/null)|$(cat $g/max_freq 2>/dev/null)|$(cat $g/governor 2>/dev/null)"
fi
for z in /sys/class/thermal/thermal_zone*; do
  echo "TZ|${z##*/}|$(cat $z/type 2>/dev/null)|$(cat $z/temp 2>/dev/null)"
done
for c in /sys/class/thermal/cooling_device*; do
  echo "CD|${c##*/}|$(cat $c/type 2>/dev/null)|$(cat $c/cur_state 2>/dev/null)|$(cat $c/max_state 2>/dev/null)"
done
echo "B|$(dumpsys battery 2>/dev/null | grep -i 'temperature' | tr -dc '0-9')"
