# 原理，以及一堆实测结果

README 写的是故事，这篇写的是原因和读数。遇到同类问题的时候，希望你能从这儿少走几步。

下面所有数字、输出片段都是这台 OPPO PJX110（ColorOS 16 / Android 16，KernelSU → iPad iPadOS 27.3）上真实抓到的。

## RNDIS 为什么不行

Android 的 USB 网络共享默认 function 是 RNDIS（`mUsbTetheringFunction: RNDIS`，`sys.usb.state=rndis`，主机侧看到 `rndis0`）。

苹果这边，macOS、iOS、iPadOS 都没有 RNDIS 的主机驱动。所以手机侧 `rndis0` 状态再正常，iPad 也不会创建网络接口——它只是看到一个它不认识的 USB 设备，然后无视掉。

有人试图在电脑侧补驱动（HoRNDIS 之类），但那是 macOS kext 时代的方案，现在要动 SIP，还挺麻烦。手机侧切成 CDC-NCM 干净得多，因为苹果原生就支持。

## 切 NCM 时到底发生了什么

```
svc usb setFunctions ncm
```

这一条会让系统销毁现有 gadget 配置再按新 function 重建。所以：

- 正在跑的 adb 会断，usbmuxd 也会断
- 新网卡 `usb0` 出现，但初始是 **DOWN**，`carrier=0`
- 要 `ip link set usb0 up` 才会变 `UP,LOWER_UP`、`carrier=1`

补一句别处看不到的：函数名各家不一样。这台是 `ncm.gs6`，有的机器是 `ncm.usb0`，还有的只有 `ncm`。先 `ls /config/usb_gadget/g1/functions/` 看一眼比较稳。

关于会不会被回滚——HyperOS 上 `svc` 改完几秒内会被改回去，得用 `tether_force_usb_functions`；这台 ColorOS 16 不回滚，一直保持到重启。所以这个差异是 ROM 的，不是协议的。

## Tethering 那边为什么不管

切完之后的状态机长这样（`dumpsys tethering` 原文节选）：

```
Tether state:
  Upstream wanted: false
  Current upstream interface(s): null

Tetherable:
  tetherableUsbRegexs: [usb\d, rndis\d]
  tetherableNcmRegexs: [ncm\d]
  enableLegacyDhcpServer: false
  enableBpfOffload: true
```

`Upstream wanted: false` 是关键：状态机压根没被触发。于是：

- 不会给 `usb0` 配地址（日志里那句 `LinkAddresses: []` 就是这么来的，看着像"配了"，其实是空的）
- 不会起 DHCP（`enableLegacyDhcpServer: false`）
- 不会建 NAT 和 FORWARD
- `tc filter show dev usb0 ingress` 是空的，说明 eBPF 那边也没挂东西

我试过把 `settings put global tether_offload_disabled 1` 写进去，写成功了，但 `enableBpfOffload` 还是 `true`——这个开关只在 tethering 配置重读的时候生效，改完不重启它不理你。

结论就是：系统不管，只能自己配。这跟 Moto、Redmi 那些机器的行为不一样，它们会把 NCM 接口当正常下游接管（`usb0 - TetheredState`），所以那些项目切完就有网。

## 真正难的那部分：Android 的策略路由

手工配完地址、`ip_forward=1`、NAT、FORWARD，`iptables` 计数还是 0，包根本没进转发路径。

用这个查：

```
ip route get 223.5.5.5 from 192.168.42.30 iif usb0
→ RTNETLINK answers: Network is unreachable
```

原因在 Android 的路由结构上：

- `main` 表是空的（`ip route show table main` 没有 default）
- 默认路由在各接口自己的表里：`rmnet_data2` 表、`wlan0` 表
- 转发包的 `fwmark=0`，匹配不到任何接口表
- 最后落到那条兜底规则 `32000: from all unreachable`，被丢掉

所以需要一条只针对 `usb0` 进来的规则，把它导到上行的表去：

```
ip rule add iif usb0 lookup <上行表> pref 20500
```

加完包能出去了，但 iPad 还是打不开网页，而且情况变得奇怪：`tcpdump -i usb0` 能看到 iPad 发 SYN，`tcpdump -i rmnet_data2` 能看到对端的 SYN-ACK，可 iPad 收不到，手机上却能看到自己往外发 `ICMP net unreachable`。

回程的包同样落到了 unreachable 上——它从 `rmnet_data2` 进来，`fwmark` 依然是 0。所以要再补一条，只对 iPad 那个网段生效，别影响正常流量：

```
ip rule add iif <上行> lookup main pref 20400 to 192.168.42.0/24
```

两条都加上以后，抓包里第一次出现了真正的双向数据：

```
17.57.145.134.5223 > 192.168.42.30.58506: Flags [P.], seq ... 1348 bytes
192.168.42.30.58506 > 17.57.145.134.5223: Flags [.], ack ...
```

`17.57.145.134` 是 Apple 的 CDN，看到这个就知道 iPad 真的在正常收发了。

## 上行不能写死

第一次跑通的时候脚本里写的是 `rmnet_data2`，因为当时手机在用移动数据。

后来手机连着 WiFi 又点了一次共享，iPad 上一秒能用，下一秒就断。原因是默认路由搬到了 `wlan0`，而 NAT 和两条规则还指着 `rmnet_data2`。

现在的做法是按 `wlan0 / wlan1 / eth0 / rmnet_data0..3` 顺序找"存在且有 default 路由"的那个，WiFi 排前面。看门狗里也有一份同样的探测，上行变了就把规则搬过去。

## DHCP 和 iOS 的脾气

`enableLegacyDhcpServer: false` 意味着系统不会起 DHCP 服务端，所以脚本自己拉一个 `dnsmasq --interface=usb0 --port=0`。

但更值得注意的是 iPad 那边：DHCP 一旦失败，它会自己挑一个 `169.254.x.x`（自动私有地址）然后**停止请求 DHCP**。表现就是「以太网出现了，但显示未连接」，抓包只能看到它在发 mDNS。

一旦进入这个状态，你在这边怎么修 DHCP 它都不会回来重新要地址，得手动填或者把线拔了重插。所以我在 README 里直接建议手动配 IP。

## 试过但没用的节点

记一下，省得你也去试：

- `settings put global tether_offload_disabled 1`：写成功，`enableBpfOffload` 不变
- `/sys/class/power_supply/usb/input_current_limit`：写 0 或 500000 都返回成功，读回来还是 `2000000`，被充电驱动按当前协商结果覆盖掉了
- `/sys/class/typec/port0/waiting_for_supplier`：写进去 `EPERM`，只读
- `/sys/devices/platform/soc/a600000.ssusb/mode`：厂商节点，能写，但这台机器上写它有过把整机搞重启的记录，不建议碰

## 供电那三个状态

顺带记下实测读数（`/sys/class/typec/port0/power_role`）：

```
sink    → online=1，电池 Trickle，iPad 在给手机供电
source  → online=0，手机对外供电
source + otg_switch=0 → online=0，carrier 仍为 1，双方都不供，网还在
```

`otg_switch` 在 `/sys/class/oplus_chg/usb/` 下，OPPO 私有。`power_role` 是 Linux Type-C class 的标准属性，别家应该也有类似的。

顺序得注意：先 `source`，等一秒，再关 `otg_switch`。反过来的话会先出现一段两边都不供的空档，接口容易断。

## 怎么确认真的通了

别看「有没有分到 IP」，iOS 会留着过期的租约，看着像通了其实早死了。看这三处：

1. `ip route get <外网> from 192.168.42.30 iif usb0` —— 不该是 unreachable
2. `ip rule show | grep -E '20400|20500'` —— 两条都在，指向的接口是当前上行
3. `iptables -L tetherctrl_FORWARD -n -v` 里 `usb0 -> 上行` 的计数在涨

再补一句：`ping` 不通不代表没网。iOS 默认不回 ICMP，我在这上面浪费了半小时，差点去改防火墙。用 `http://223.5.5.5` 或者 `capture.apple.com` 试。