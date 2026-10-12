# 阶段 5/5：温控（限量，避免节点过多拖垮单次执行）
i=0
for z in /sys/class/thermal/thermal_zone*; do
  [ $i -ge 8 ] && break
  echo "TZ|${z##*/}|$(cat $z/type 2>/dev/null)|$(cat $z/temp 2>/dev/null)"
  i=$((i+1))
done
i=0
for c in /sys/class/thermal/cooling_device*; do
  [ $i -ge 8 ] && break
  echo "CD|${c##*/}|$(cat $c/type 2>/dev/null)|$(cat $c/cur_state 2>/dev/null)|$(cat $c/max_state 2>/dev/null)"
  i=$((i+1))
done
