# 排错手册

> 先看结论：**绝大多数“切了 NCM 但没网”，都是少了策略路由那两条。**

## 快速自检（30 秒）

```sh
su -c '
echo "usb.state = $(getprop sys.usb.state)"
echo "carrier   = $(cat /sys/class/net/usb0/carrier 2>/dev/null)"
echo "ip_forward= $(cat /proc/sys/net/ipv4/ip_forward)"
ip -o -4 addr show usb0
ip rule show | grep -E "20400|20500"
iptables -t nat -L tetherctrl_nat_POSTROUTING -n -v | head -5
'
```

健康状态长这样：

```
usb.state = ncm
carrier   = 1
ip_forward= 1
usb0      inet 192.168.42.129/24
20400: from all to 192.168.42.0/24 iif <上行> lookup main
20500: from all iif usb0 lookup <上行>
```

---

## 症状对照表

| 症状 | 最可能原因 | 处理 |
|---|---|---|
| iPad 完全没有“以太网”选项 | gadget 还是 RNDIS | `svc usb setFunctions ncm`；确认 `sys.usb.state=ncm` |
| 有以太网但显示“未连接” | `usb0` 没 up，或 carrier=0 | `ip link set usb0 up`；`cat /sys/class/net/usb0/carrier` |
| iPad 显示 `169.254.x.x` | DHCP 没成功，iOS 已退化 | 手动填 IP（见下表）或确认 dnsmasq 在跑 |
| iPad 有 IP 但打不开网页 | 缺策略路由 / NAT / FORWARD | 看下文「三处必查」 |
| ping 不通但网页能开 | iOS 默认不回 ICMP，正常现象 | 用 `http://223.5.5.5` 或 `capture.apple.com` 验证 |
| 手机切到 WiFi 后 iPad 断网 | 上行写死成 `rmnet_data2` | 用自动探测版脚本；`ip rule` 应指向 `wlan0` |
| 一会儿就自己关掉了 | watchdog 判为断线（20 秒） | 检查 `cat /sys/class/net/usb0/carrier`，确认线插好 |
| 反复切 USB 功能后彻底不通 | gadget 状态污染 | 重启手机，或 `svc usb setFunctions none` 再切回 |
| `svc usb setFunctions ncm` 秒退回 rndis | ROM 强制覆盖 | 试 `settings put global tether_force_usb_functions 1` + 重启（本机未验证，部分 ROM 有效） |
| 切完电脑/平板识别成串口或 MTP | 配置里 NCM 接口没启用 | 检查 `/config/usb_gadget/g1/configs/b.1/` |
| App 显示“未授权” | KernelSU 没给 su 权限 | 在 KernelSU 里授权，建议“记住选择” |

---

## 三处必查（“有 IP 上不了网”）

### ① 转发查路

```sh
ip route get 223.5.5.5 from 192.168.42.30 iif usb0
```

- 输出 `Network is unreachable` → **缺 `iif usb0 lookup <上行>` 规则**
- 正常输出形如 `via 10.x.x.x dev rmnet_data2`

### ② 回程查路

```sh
ip rule show | grep 20400
```

- 没有这条 → iPad 只发不收，表现是“一直在连接”，同时手机会回 `ICMP net unreachable`

### ③ 规则计数

```sh
iptables -L tetherctrl_FORWARD -n -v | head -6
```

- `usb0 -> <上行>` 的 ACCEPT 计数应持续增长；一直 0 说明包根本没进转发路径（路由/链路问题）

---

## iPad 手动配置（绕过 DHCP）

设置 → 以太网 → 配置 IP：**手动**

| 项 | 值 |
|---|---|
| IP 地址 | `192.168.42.30` |
| 子网掩码 | `255.255.255.0` |
| 路由器 | `192.168.42.129` |
| DNS | `8.8.8.8`（备用 `223.5.5.5`） |

---

## 抓包定位（判断包死在哪一跳）

```sh
timeout 5 /system/bin/tcpdump -i usb0 -n        # 看 iPad 发了什么
timeout 5 /system/bin/tcpdump -i <上行> -n      # 看 NAT 后有没有出去、有没有回包
```

典型三种结果：

1. `usb0` 只有 ARP / mDNS，没有 TCP → iPad 没在发业务流量（可能没网卡地址/在休眠）
2. `usb0` 有 SYN，`<上行>` 没有 → 出向规则问题
3. `usb0` 有 SYN，`<上行>` 有 SYN-ACK，`usb0` 没回包 → 回程规则问题（`20400`）

---

## 日志位置

| 文件 | 内容 |
|---|---|
| `/data/local/tmp/dns.log` | dnsmasq 输出 |
| `/data/local/tmp/leases` | DHCP 租约 |
| `/data/local/tmp/watch.log` | 看门狗（上行切换 / 断线清理 / 自愈） |

```sh
su -c 'cat /data/local/tmp/watch.log'
```
