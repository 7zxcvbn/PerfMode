# 阶段 3/5：GPU devfreq
g=""
if [ -d /sys/class/kgsl/kgsl-3d0/devfreq ]; then g=/sys/class/kgsl/kgsl-3d0/devfreq; fi
if [ -z "$g" ]; then
  for d in /sys/class/devfreq/*; do
    l=$(readlink -f "$d" 2>/dev/null)
    case "$l" in *gpu*|*kgsl*|*mali*) g=$d; break ;; esac
  done
fi
if [ -n "$g" ]; then
  mx=0
  for f in $(cat "$g/available_frequencies" 2>/dev/null); do
    case "$f" in ''|*[!0-9]*) ;; *) [ "$f" -gt "$mx" ] && mx=$f ;; esac
  done
  echo "GPU|$g|$(cat $g/cur_freq 2>/dev/null)|$(cat $g/max_freq 2>/dev/null)|$mx|$(cat $g/min_freq 2>/dev/null)|$(cat $g/governor 2>/dev/null)"
fi
