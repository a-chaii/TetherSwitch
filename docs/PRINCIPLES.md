# 实测原理笔记

本文记录在 **OPPO PJX110（ColorOS 16 / Android 16, KernelSU）→ iPad（iPadOS 27.3）** 上的实测发现。
所有结论都来自实际抓包 / dumpsys / /sys 读数，不是推测。

---

## 1. 为什么 iPad 收不到 RNDIS

- Android 的 USB 网络共享默认 function 是 **RNDIS**（`sys.usb.state=rndis`，主机侧看到 `rndis0`）。
- **Apple 从未实现 RNDIS host 驱动**：macOS/iOS/iPadOS 原生只支持 **CDC-NCM / CDC-ECM / CDC-EEM**。
- 所以手机侧 `rndis0` 再健康，iPad 也不会出现“以太网”。

→ 必须让手机的 USB gadget 变成 **NCM**（本机 gadget 里同时存在 `rndis.rndis`、`gsi.rndis`、`ncm.gs6`）。

## 2. 切换方式与代价

```
svc usb setFunctions ncm      # 立即生效，但会重建整个 gadget
```

- 重建期间 USB 会短暂断开（正在跑的 usbmuxd / adb 会掉）。
- 部分 ROM（如 HyperOS）会在数秒内把 function **回滚**，此时需要 `settings put global tether_force_usb_functions 1` 并**重启一次**。
  本机（ColorOS 16）实测**没有回滚**，`svc` 方式可以稳定保持 —— 但别的机型请自行验证。
- 切完网卡名是 `usb0`（部分机型是 `usb1`），需要 `ip link set usb0 up`，否则 `carrier=0`。

## 3. TetheringManager 不会自动接管（本机关键差异）

切完 NCM 后，`dumpsys tethering` 会告诉你真相：

```
Tether state:
  Upstream wanted: false
  Current upstream interface(s): null
```

意思是状态机**根本没进入 tethering**，所以：

- 不会自动配 `usb0` 的 IPv4 地址
- 不会起 DHCP 服务
- 不会建 NAT / FORWARD 规则
- 也没有任何 eBPF 钩子挂在 `usb0` 上（`tc filter show dev usb0 ingress` 为空）

作为对照，`lipangeng/kernelsu-auto-ncm` 与 `dcode-co/android-usb-tethering-ncm` 的目标机型里，
系统会把 NCM 接口**当作下游自动接管**（`usb0 - TetheredState`），因此它们切完就有网。
**本机不属于这一类**，必须手工补齐整个网络栈。

## 4. 手工网络栈（为什么是这几条）

```sh
ip link set usb0 up                              # 不 up 则 carrier 一直 0
ip addr replace 192.168.42.129/24 dev usb0       # 下联网关地址
echo 1 > /proc/sys/net/ipv4/ip_forward

# NAT
iptables -t nat -A tetherctrl_nat_POSTROUTING -o <上行> -j MASQUERADE
# FORWARD（两条方向都要）
iptables -I tetherctrl_FORWARD -i usb0 -o <上行> -j ACCEPT
iptables -I tetherctrl_FORWARD -i <上行> -o usb0 -m state --state RELATED,ESTABLISHED -j ACCEPT
```

### 4.1 两条策略路由（最容易漏的坑）

Android 用 fwmark 做策略路由，**`main` 表里没有默认路由**（默认路由在 `rmnet_dataX` / `wlan0` 这些按接口命名的表里）。
于是：

- **出向**：转发包的 `fwmark=0`、`iif=usb0`，一路走到 `32000: from all unreachable` → 包出不去
  → `ip rule add iif usb0 lookup <上行表> pref 20500`
- **回程**：外部响应包 `iif=<上行>`，conntrack 还原目标后仍落到 `unreachable` → 内核自己回 `ICMP net unreachable`
  → `ip rule add iif <上行> lookup main pref 20400 to 192.168.42.0/24`

少了第二条的典型现象：`tcpdump -i usb0` 能看到 iPad 的 SYN，`tcpdump -i <上行>` 能看到 SYN-ACK，
但 iPad 永远收不到回包，手机反而在往外发 ICMP unreachable。

### 4.2 上行不是固定的

默认路由可能落在 `rmnet_data2`（移动数据）或 `wlan0`（WiFi）。
把上行写死会导致**手机从数据切到 WiFi 时 iPad 立刻断网**。
本项目按 `wlan0 → wlan1 → eth0 → rmnet_data*` 顺序探测“存在且有 default 路由”的表。

### 4.3 DHCP

`dumpsys tethering` 里 `enableLegacyDhcpServer: false`，所以系统不会起 DHCP 服务端。
iOS 侧 DHCP 一旦失败会退化成 `169.254.x.x`（自动私有地址）并停止请求，因此：

- 脚本会起 `dnsmasq --interface=usb0 --port=0 ...`
- 即使 DHCP 失败，也可以让 iPad 手动填 IP（这是最稳的做法）

## 5. 那些“看起来能改、其实改不动”的节点

| 节点 | 现象 |
|---|---|
| `settings put global tether_offload_disabled 1` | 写入成功，但 `dumpsys tethering` 的 `enableBpfOffload` 仍为 `true`，配置不重读 |
| `/sys/class/power_supply/usb/input_current_limit` | 写 0 / 500000 均返回成功，读回仍是 `2000000`（被充电驱动覆盖） |
| `/sys/class/typec/port0/waiting_for_supplier` | 写入 `EPERM`（rc=1），只读 |

## 6. 供电方向（OPPO 平台的实现）

| 目标 | 做法 | 实测 |
|---|---|---|
| iPad 给手机供电 | `echo sink > /sys/class/typec/port0/power_role` | `online=1`，电池 `Trickle` |
| 手机给 iPad 供电 | `echo source > /sys/class/typec/port0/power_role` | `online=0`，手机输出供电 |
| **都不供电** | `echo source > power_role` → `sleep 1` → `echo 0 > /sys/class/oplus_chg/usb/otg_switch` | `online=0`，**NCM 链路不断**（carrier 仍为 1） |

顺序很重要：先声明 `source`（让对端停止送电），再关掉对外供电。

> 说明：`power_role` 是 Linux Type-C class 的通用属性；`otg_switch` 是 OPPO 私有节点，
> 其他厂商需要找对应的充电驱动节点（如小米的 `charge_disable`、散见于 `oplus_chg`/`vooc`/`mtk_switch_charging`）。
> **没有任何一家能真正做到“VBUS 完全不供电”**——USB 规范要求一方供电，这里只是让双方都不对外输出。

## 7. NAT 之后仍未通的排查顺序

1. `ip route get <外网IP> from 192.168.42.30 iif usb0` —— 看是否 `unreachable`
2. `ip rule show | grep -E '20400|20500'` —— 两条规则在不在、指向对不对
3. `iptables -L tetherctrl_FORWARD -n -v` —— ACCEPT 计数器有没有涨（涨了说明进了 iptables，没涨说明没进转发路径）
4. `tcpdump -i usb0 -n` / `tcpdump -i <上行> -n` —— 两头对比，找出包停在哪一跳
