# 阶段 1/5：设备身份（只读 getprop，最轻）
M=$(getprop ro.product.manufacturer 2>/dev/null)
B=$(getprop ro.product.brand 2>/dev/null)
MO=$(getprop ro.product.model 2>/dev/null)
MN=$(getprop ro.product.marketname 2>/dev/null)
[ -z "$MN" ] && MN=$(getprop ro.vivo.product.model 2>/dev/null)
BD=$(getprop ro.product.board 2>/dev/null)
PL=$(getprop ro.board.platform 2>/dev/null)
CN=$(getprop ro.chipname 2>/dev/null)
HW=$(getprop ro.hardware 2>/dev/null)
SOC=$(getprop ro.soc.model 2>/dev/null)
A=$(getprop ro.build.version.release 2>/dev/null)
S=$(getprop ro.build.version.sdk 2>/dev/null)
K=$(uname -r 2>/dev/null)
echo "D|$M|$B|$MO|$MN|$BD|$PL|$CN|$HW|$SOC|$A|$S|$K"
PD=$(getprop ro.product.vendor.device 2>/dev/null)
[ -n "$PD" ] && echo "P|$PD"
