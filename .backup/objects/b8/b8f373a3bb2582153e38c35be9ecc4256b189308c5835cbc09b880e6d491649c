#!/system/bin/sh
# ============================================================
# USB 共享看门狗
#   1) 断线（carrier=0 或网卡消失）持续约 20 秒 -> 自动关闭共享
#   2) 上行接口发生变化（WiFi <-> 移动数据）-> 自动迁移规则
# 用法: sh tether_watch.sh [当前上行接口]
# ============================================================
set +e

USBIF=usb0
UP="$1"
[ -z "$UP" ] && UP=rmnet_data2

zero=0
miss=0

detect_up() {
  for i in wlan0 wlan1 eth0 rmnet_data0 rmnet_data1 rmnet_data2 rmnet_data3 rmnet_data4; do
    if [ -d /sys/class/net/$i ] && ip route show table $i 2>/dev/null | grep -q '^default'; then
      echo $i
      return
    fi
  done
  ip -o route show table all 2>/dev/null | awk '/default/ && !/usb0/ {for(k=1;k<=NF;k++) if($k=="dev"){print $(k+1); exit}}'
}

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

unapply_rules() {
  U="$1"
  [ -z "$U" ] && return
  iptables -t nat -D tetherctrl_nat_POSTROUTING -o $U -j MASQUERADE 2>/dev/null
  iptables -D tetherctrl_FORWARD -i $USBIF -o $U -j ACCEPT 2>/dev/null
  iptables -D tetherctrl_FORWARD -i $U -o $USBIF -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null
  ip rule del iif $USBIF lookup $U pref 20500 2>/dev/null
  ip rule del iif $U lookup main pref 20400 to 192.168.42.0/24 2>/dev/null
}

echo "[watch] start, upstream=$UP"

while true; do
  # ---------- 存活检测 ----------
  if [ ! -d /sys/class/net/$USBIF ]; then
    # 网卡消失：如果已经不在 NCM 模式，说明共享被主动关掉了，直接退出
    if [ "$(getprop sys.usb.state)" != "ncm" ]; then
      echo "[watch] gadget no longer ncm, exit"
      exit 0
    fi
    miss=$((miss+1))
  else
    miss=0
    if [ "$(cat /sys/class/net/$USBIF/carrier 2>/dev/null)" = "1" ]; then
      zero=0
    else
      zero=$((zero+1))
    fi
  fi

  # 连续 10 轮（约 20 秒）异常 -> 自动关闭
  if [ $zero -ge 10 ] || [ $miss -ge 10 ]; then
    echo "[watch] link down (zero=$zero miss=$miss), auto cleanup"
    sh /data/local/tmp/usb_tether_off.sh "$UP" >/dev/null 2>&1
    exit 0
  fi

  # ---------- 自愈：接口被重建后地址丢失 ----------
  if [ -d /sys/class/net/$USBIF ] && [ "$(cat /sys/class/net/$USBIF/carrier 2>/dev/null)" = "1" ]; then
    if ! ip -o -4 addr show $USBIF 2>/dev/null | grep -q '192.168.42.129'; then
      echo "[watch] addr lost, re-apply"
      ip link set $USBIF up 2>/dev/null
      ip addr replace 192.168.42.129/24 dev $USBIF
      echo 1 > /proc/sys/net/ipv4/ip_forward
      apply_rules "$UP"
      if ! pgrep -f "dnsmasq --interface=$USBIF" >/dev/null; then
        nohup /system/bin/dnsmasq --interface=$USBIF \
          --dhcp-range=192.168.42.2,192.168.42.254,255.255.255.0,1h \
          --dhcp-option=3,192.168.42.129 \
          --dhcp-option=6,8.8.8.8,223.5.5.5 \
          --port=0 --dhcp-authoritative \
          --pid-file=/data/local/tmp/dns.pid \
          --dhcp-leasefile=/data/local/tmp/leases \
          >/data/local/tmp/dns.log 2>&1 &
      fi
    fi
  fi

  # ---------- 上行变化检测 ----------
  NEW=$(detect_up)
  if [ -n "$NEW" ] && [ "$NEW" != "$UP" ]; then
    echo "[watch] upstream $UP -> $NEW"
    unapply_rules "$UP"
    apply_rules "$NEW"
    UP="$NEW"
  fi

  sleep 2
done