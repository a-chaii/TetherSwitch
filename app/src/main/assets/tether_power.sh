#!/system/bin/sh
# ============================================================
# 供电方向控制
#   sink   : iPad 供电给手机（默认）
#   source : 手机供电给 iPad
#   off    : 双方都不供电（先 source 再关 otg_switch）
# ============================================================
MODE="$1"
P=/sys/class/typec/port0/power_role
OTG=/sys/class/oplus_chg/usb/otg_switch

case "$MODE" in
  sink)
    echo 1 > $OTG 2>/dev/null
    echo sink > $P 2>/dev/null
    ;;
  source)
    echo 1 > $OTG 2>/dev/null
    echo source > $P 2>/dev/null
    ;;
  off)
    # 顺序很重要：先声明 source（让 iPad 停止供电），再关掉对外供电
    echo source > $P 2>/dev/null
    sleep 1
    echo 0 > $OTG 2>/dev/null
    ;;
  *)
    echo "usage: $0 sink|source|off"
    exit 1
    ;;
esac

sleep 1
echo "[power] mode=$MODE role=$(cat $P 2>/dev/null) otg=$(cat $OTG 2>/dev/null) online=$(cat /sys/class/power_supply/usb/online 2>/dev/null)"