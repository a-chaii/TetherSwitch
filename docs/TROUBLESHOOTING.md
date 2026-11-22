# 踩坑记录

按我实际遇到的顺序写的。症状在前，原因在后，命令都能直接粘。

## 先跑这个

```sh
su -c '
echo "usb.state = $(getprop sys.usb.state)"
echo "carrier   = $(cat /sys/class/net/usb0/carrier 2>/dev/null)"
echo "forward   = $(cat /proc/sys/net/ipv4/ip_forward)"
ip -o -4 addr show usb0
ip rule show | grep -E "20400|20500"
'
```

通了的时候大概是这样：

```
usb.state = ncm
carrier   = 1
forward   = 1
5: usb0    inet 192.168.42.129/24 brd 192.168.42.255 scope global usb0
20400: from all to 192.168.42.0/24 iif wlan0 lookup main
20500: from all iif usb0 lookup wlan0
```

对着看就行。

## iPad 上压根没有「以太网」这一项

gadget 还是 RNDIS。

```sh
getprop sys.usb.state      # 显示 rndis 就是没切
svc usb setFunctions ncm
```

切完确认一下变 `ncm` 了。

如果你是 HyperOS，可能会发现几秒后又变回 `rndis`。那是 ROM 在改回去，`svc` 这条路走不通，得试：

```sh
settings put global tether_force_usb_functions 1
# 然后重启一次
```

这个开关只在开机时读一次，改完不重启不生效。我这台 ColorOS 没这个问题，所以这条对我没用上，不能给你打包票。

## 有以太网，但显示「未连接」

`usb0` 起来了但没 up：

```sh
cat /sys/class/net/usb0/carrier    # 0
ip link set usb0 up
cat /sys/class/net/usb0/carrier    # 变成 1 才对
```

第一次遇到时我以为是线的问题，换了两根线。

## iPad 显示 169.254.x.x

DHCP 没成，iOS 已经退化了。

要么让 dnsmasq 起来（`pgrep -f "dnsmasq --interface=usb0"` 看进程在不在），要么直接手动填 IP——手动填更快，而且不用管 iOS 那个「失败就不再请求」的脾气。

手动配置见 README 的「iPad 那边」。

## 有 IP，但网页打不开

八成是策略路由。这一步要查三处：

```sh
# 1. 转发查路
ip route get 223.5.5.5 from 192.168.42.30 iif usb0
# 输出 unreachable → 少了 iif usb0 lookup <上行> 那条

# 2. 回程查路
ip rule show | grep 20400
# 没有这条 → 包能出去回不来，iPad 一直转圈

# 3. 转发计数
iptables -L tetherctrl_FORWARD -n -v | head -6
# usb0 -> 上行 那行数字不动 → 包根本没进转发路径
```

## 加完规则好了半分钟又不行

回程那条漏了。

补充一条就够，别图省事写成全局：

```sh
ip rule add iif <上行> lookup main pref 20400 to 192.168.42.0/24
```

加 `to 192.168.42.0/24` 是为了只影响 iPad 那段，不然会干涉手机自己的流量。

## 手机从移动数据切到 WiFi 后 iPad 断网

上行写死了。

```sh
ip rule show | grep -E "20400|20500"
```

如果这里指着 `rmnet_data2`，但你当前其实在用 WiFi（`ip route show table all | grep default` 能看到 `wlan0`），就是这个问题。把旧规则删掉，按新的上行重建。脚本和看门狗现在都会自动处理这个。

## 开着开着它自己关了

看门狗干的。

它每 2 秒看一次 `carrier`，连续 10 次是 0（或者 `usb0` 消失）就当成拔线，自动清理然后退出。所以：

- 线松了 → 20 秒后自己关，正常
- 手抖把线拔了又插 → 也可能触发，重开一次就行

日志在 `/data/local/tmp/watch.log`，最后一行会写原因：

```
[watch] link down (zero=10 miss=0), auto cleanup
```

## 反复切换十几次以后彻底不通了

gadget 状态被搞脏了。

```sh
svc usb setFunctions none
# 等几秒
svc usb setFunctions ncm
```

不行就重启。我这台最惨的一次是切到一半整机重启了（见 README 里写的那条缺陷），重启后 `sys.usb.state` 回到 `rndis`，从头来一遍就好。

## App 点了没反应 / 提示未授权

KernelSU 没给 su 权限。第一次点「开启共享」时会弹框，选允许，并勾上记住选择，不然每次都要点。

磁贴点了显示「处理中…」是在切 gadget，要几秒，别连点。

## 日志在哪儿

| 想看什么 | 文件 |
|---|---|
| dnsmasq | `/data/local/tmp/dns.log` |
| DHCP 租约 | `/data/local/tmp/leases` |
| 看门狗 | `/data/local/tmp/watch.log` |

```sh
su -c 'tail -20 /data/local/tmp/watch.log'
```

## 还是不行

在 issue 里贴 `docs/COMPATIBILITY.md` 最后那段要求的输出，我看看。抓包也行：

```sh
timeout 5 /system/bin/tcpdump -i usb0 -n
timeout 5 /system/bin/tcpdump -i <上行> -n
```

两头的对比能直接看出包死在哪一跳。