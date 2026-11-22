#!/system/bin/sh
# ============================================================
# 关闭 USB 网络共享，恢复普通 USB 模式
# ============================================================
set +e

UP="$1"
[ -z "$UP" ] && UP=rmnet_data2

echo "[tether] 正在关闭..."

# 先停看门狗（含被看门狗调用的场景：子进程会继续执行完毕）
pkill -f tether_watch.sh 2>/dev/null

pkill -f 'dnsmasq --interface=usb0' 2>/dev/null
ip link set usb0 down 2>/dev/null
ip rule del iif usb0 lookup $UP pref 20500 2>/dev/null
ip rule del iif $UP lookup main pref 20400 to 192.168.42.0/24 2>/dev/null
iptables -t nat -D tetherctrl_nat_POSTROUTING -o $UP -j MASQUERADE 2>/dev/null
iptables -D tetherctrl_FORWARD -i usb0 -o $UP -j ACCEPT 2>/dev/null
iptables -D tetherctrl_FORWARD -i $UP -o usb0 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null
ip addr del 192.168.42.129/24 dev usb0 2>/dev/null

# 恢复对外供电开关（防止「都不供电」状态残留导致下次插线不充电）
echo 1 > /sys/class/oplus_chg/usb/otg_switch 2>/dev/null

# 恢复普通 USB 功能（文件传输/充电）
svc usb setFunctions mtp >/dev/null 2>&1

echo "[tether] 已关闭，当前 USB: $(getprop sys.usb.state)"