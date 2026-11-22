#!/system/bin/sh
# ============================================================
# USB 网络共享 (NCM) 一键开启
#   - 自动检测上行（WiFi 优先，其次移动数据）
#   - 启动看门狗：断线自动清理 / 上行变化自动重配
# ============================================================
set +e

USBIF=usb0
UP="${1:-}"

say() { echo "[tether] $*"; }

# ---------- 上行接口检测（WiFi 优先） ----------
detect_up() {
  for i in wlan0 wlan1 eth0 rmnet_data0 rmnet_data1 rmnet_data2 rmnet_data3 rmnet_data4; do
    if [ -d /sys/class/net/$i ] && ip route show table $i 2>/dev/null | grep -q '^default'; then
      echo $i
      return
    fi
  done
  ip -o route show table all 2>/dev/null | awk '/default/ && !/usb0/ {for(k=1;k<=NF;k++) if($k=="dev"){print $(k+1); exit}}'
}

# ---------- 应用规则（幂等） ----------
apply_rules() {
  U="$1"
  [ -z "$U" ] && return
  iptables -t nat -C tetherctrl_nat_POSTROUTING -o $U -j MASQUERADE 2>/dev/null || \
    iptables -t nat -A tetherctrl_nat_POSTROUTING -o $U -j MASQUERADE
  iptables -C tetherctrl_FORWARD -i $USBIF -o $U -j ACCEPT 2>/dev/null || \
    iptables -I tetherctrl_FORWARD -i $USBIF -o $U -j ACCEPT
  iptables -C tetherctrl_FORWARD -i $U -o $USBIF -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
    iptables -I tetherctrl_FORWARD -i $U -o $USBIF -m state --state RELATED,ESTABLISHED -j ACCEPT
  ip rule del iif $USBIF lookup $U pref 20500 2>/dev/null
  ip rule add iif $USBIF lookup $U pref 20500 2>/dev/null
  ip rule del iif $U lookup main pref 20400 to 192.168.42.0/24 2>/dev/null
  ip rule add iif $U lookup main pref 20400 to 192.168.42.0/24 2>/dev/null
}

# ---------- 0. 上行 ----------
if [ -z "$UP" ]; then
  UP=$(detect_up)
  [ -z "$UP" ] && UP=rmnet_data2
fi
say "上行接口 = $UP"

# ---------- 1. 切 NCM ----------
if [ "$(getprop sys.usb.state)" != "ncm" ]; then
  say "切换 USB gadget -> NCM ..."
  svc usb setFunctions ncm >/dev/null 2>&1
  i=0
  while [ $i -lt 120 ]; do
    [ -d /sys/class/net/$USBIF ] && break
    sleep 1; i=$((i+1))
  done
fi

# ---------- 2. 网卡 up，等 carrier ----------
ip link set $USBIF up 2>/dev/null
i=0
while [ $i -lt 60 ]; do
  [ "$(cat /sys/class/net/$USBIF/carrier 2>/dev/null)" = "1" ] && break
  sleep 1; i=$((i+1))
done
say "carrier=$(cat /sys/class/net/$USBIF/carrier 2>/dev/null)"

# ---------- 3. 下联地址 ----------
ip addr replace 192.168.42.129/24 dev $USBIF

# ---------- 4. 转发 ----------
echo 1 > /proc/sys/net/ipv4/ip_forward
say "ip_forward=$(cat /proc/sys/net/ipv4/ip_forward)"

# ---------- 5/6. NAT + 策略路由 ----------
apply_rules "$UP"
say "NAT/FORWARD/路由 就绪"

# ---------- 7. DHCP ----------
pkill -f "dnsmasq --interface=$USBIF" 2>/dev/null
nohup /system/bin/dnsmasq --interface=$USBIF \
  --dhcp-range=192.168.42.2,192.168.42.254,255.255.255.0,1h \
  --dhcp-option=3,192.168.42.129 \
  --dhcp-option=6,8.8.8.8,223.5.5.5 \
  --port=0 --dhcp-authoritative \
  --pid-file=/data/local/tmp/dns.pid \
  --dhcp-leasefile=/data/local/tmp/leases \
  >/data/local/tmp/dns.log 2>&1 &
say "dnsmasq 已启动"

# ---------- 8. 看门狗（断线自动清理 / 上行变化重配） ----------
pkill -f tether_watch.sh 2>/dev/null
nohup sh /data/local/tmp/tether_watch.sh "$UP" > /data/local/tmp/watch.log 2>&1 &
say "看门狗已启动"

say "配置完成"
ip -o -4 addr show $USBIF