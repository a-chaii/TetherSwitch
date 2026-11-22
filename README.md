# TetherSwitch

> 这个仓库的代码是 AI 生成的。我负责提需求、上机测试、以及判断哪些说法能信。
> 所以你会看到一些奇怪的地方——注释比代码长、有些实现绕远路、个别地方可能是撞对的。
> 能用，但别当教科书看。发现问题欢迎提 issue。

插上线，iPad 就能上网。

先说为什么会有这个东西。

Android 的 USB 网络共享默认走 RNDIS。而苹果那边——macOS、iOS、iPadOS——从来没做过 RNDIS 的主机驱动，一个都没有。所以你把数据线插到 iPad 上、把「USB 网络共享」打开，iPad 那边连个以太网口都不会冒出来。不是你没设置对，是这条路压根不通。

能通的是 CDC-NCM。把手机的 USB gadget 从 RNDIS 切成 NCM，iPad 就会把线对面当成一块普通有线网卡。到这里为止都挺顺。

不顺的是下一步。

## 分岔路口

有些 ROM 会自己把活干完：新的 `usb0` 一出现，Tethering 服务就接管，配地址、起 DHCP、建 NAT，你插线开共享就行，剩下的它包了。

运气好是这类机器的话，别用我这个，用下面这些更省事：

- [lipangeng/kernelsu-auto-ncm](https://github.com/lipangeng/kernelsu-auto-ncm) —— KernelSU 模块，等原厂 RNDIS 真的进到共享状态后切 NCM，Moto 上验证过
- [DurkaEbanaya/usb-tether-mode-switcher](https://github.com/DurkaEbanaya/usb-tether-mode-switcher) —— 小 App，改 `tether_force_usb_functions` 切模式
- [dcode-co/android-usb-tethering-ncm](https://github.com/dcode-co/android-usb-tethering-ncm) —— 红米→macOS，踩坑记录写得很细，HyperOS 会回滚 `svc setFunctions` 这件事就是从他那儿知道的

我这台不在其中。

OPPO PJX110，ColorOS 16。切完 NCM，`dumpsys tethering` 很干脆：

```
Tether state:
  Upstream wanted: false
```

翻译成人话：系统不打算管这个接口。地址不给，DHCP 不起，NAT 不建，iptables 一条不动。接口是我切出来的，烂摊子也得我收。

## 卡住我的那一晚上

手动配完地址和 NAT，`iptables` 的计数一动不动。抓包一看，iPad 在发 SYN，`usb0` 上有，上行口没有。

查了半天：

```
ip route get 223.5.5.5 from 192.168.42.30 iif usb0
→ Network is unreachable
```

`main` 表里是空的。Android 不用传统的默认路由，它靠 fwmark 和一堆策略表，默认路由住在 `rmnet_data2`、`wlan0` 这些以接口命名的表里。转发的包 `fwmark=0`，一路走到 `32000: from all unreachable`，然后被扔掉。

加了一条 `iif usb0 lookup <上行>`，包出去了。但 iPad 还是打不开网页。

再抓：SYN 出去了，对端 SYN-ACK 也回来了，可 iPad 收不到，手机反而在朝外发 `ICMP net unreachable`。回程的包同样落到了 unreachable 上。又加一条只针对 `192.168.42.0/24` 的回程规则，`iif <上行> lookup main`。

然后就通了。抓包里第一次看到 `17.57.145.134.5223` 和 iPad 正常握手的时候，说实话挺爽的。

这两条规则（出向 `20500`、回程 `20400`）后来成了这个仓库里最要紧的东西。原理写在 [docs/PRINCIPLES.md](docs/PRINCIPLES.md)，不想听故事可以直接去那儿看。

## 里面有什么

`scripts/` 是四个纯 shell 脚本，不装 App 也能用。`app/` 是个带控制中心磁贴的安卓 App，纯粹因为我实在不想每次都开终端敲 `su -c`。

脚本做的事：

- 切 NCM，把 `usb0` up 起来（不 up 的话 carrier 一直是 0，会以为是线的问题）
- 配地址、开转发、NAT、双向 FORWARD
- 加那两条策略路由
- 起 dnsmasq
- 挂一个看门狗

看门狗是被坑出来的。有次手机连的是 WiFi，而脚本里上行写死了 `rmnet_data2`，iPad 上一秒有网下一秒断。现在上行是自动探测的，`wlan0` 优先。顺带它还管两件事：线拔了超过 20 秒自动收摊切回 MTP；接口被系统重建、地址丢了，它自己补回去。

App 比脚本多的：磁贴、电量、开机自启（默认关）、供电方向。

## 供电方向

iPad 插上来会主动给手机供电。但它给的那点电流不够手机花，结果就是电池一路往下掉，「在充电」和「在掉电」同时成立。

翻了半天找到两个节点：`/sys/class/typec/port0/power_role` 可以写 `sink`/`source`，OPPO 还有个私有的 `otg_switch`。

| 想要的 | 怎么弄 |
|---|---|
| iPad 给手机供电 | `power_role=sink`，`otg_switch=1` |
| 手机给 iPad 供电 | `power_role=source`，`otg_switch=1` |
| 谁也别供 | 先 `source`，等一秒，再 `otg_switch=0` |

最后那档的顺序不能反，先声明 `source` 让对面停止送电，再关掉自己的输出，网不会断。

这一段只在 OPPO 上验过。`power_role` 是标准 Type-C 属性，各家应该都有；`otg_switch` 是 OPPO 私有的，别的厂商得去找自己充电驱动里的对应节点。脚本里写的是「找不到就跳过」，不会卡住。

## 怎么用

只有脚本的话，把 `scripts/` 推到手机：

```sh
adb push scripts/ /data/local/tmp/
adb shell su -c 'chmod 755 /data/local/tmp/scripts/*.sh'
adb shell su -c 'sh /data/local/tmp/scripts/tether_on.sh'
```

上行会自己找，不用传参。关掉用 `tether_off.sh`。

要 App 的话：

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

装完下拉控制中心，编辑磁贴，把「网络共享」拖进去。

顺手记一句构建的坑：官方 `aapt2` 在 arm64 Linux 上跑不了，仓库里 `tools/aapt2` 是 ReVanced 编译的 arm64 版本，`setup_android_env.sh` 会把它换上去。我一开始不知道，浪费了一晚上在报错上。

## iPad 那边

设置 → 以太网 → 配置 IP → 手动：

| IP 地址 | `192.168.42.30` |
|---|---|
| 子网掩码 | `255.255.255.0` |
| 路由器 | `192.168.42.129` |
| DNS | `8.8.8.8` |

DHCP 也行，但你得接受 iOS 的脾气：一旦 DHCP 失败，它会自己退化到 `169.254.x.x`，然后不再请求 DHCP。看现象就是「以太网有了，但显示未连接」，很容易以为是自己配错了。手动填省事。

## 说清楚不行的部分

- **要 root。** 没 root 什么都干不了，iptables、ip rule、sysfs 全要。
- **不是所有机型都行。** 内核 gadget 里没有 `ncm` 功能的，这条路直接堵死，只能走 WiFi 热点。
- **切换会重建 gadget。** 正在跑的 adb 会断，传文件的时候别点。
- **我这台 PJX110 在切 USB 功能时偶尔会把整机搞重启**，碰上过一次，`uptime` 从几分钟重新数。看着像这台机器 OTG 供电设计上的毛病，不是通病，但你要有心理准备。
- **重启不保留。** 重启后回到 RNDIS，得重新开（或者把 App 里的开机自启打开）。
- 有人说 `settings put global tether_force_usb_functions 1` 能让切换持久化。我这台没验证过，HyperOS 上确实有效（见上面 dcode-co 的记录），我把它写进排错手册当备选，不敢保证。
- 不动 system/vendor，不改 `persist.sys.usb.config`。想还原就跑 `tether_off.sh`。

## 我这台的实测

| | |
|---|---|
| 手机 | OPPO PJX110，ColorOS 16 / Android 16 |
| root | KernelSU |
| 平板 | iPad，iPadOS 27.3 |
| 上行 | WiFi（`wlan0`）和移动数据（`rmnet_data2`）都试过 |
| 结果 | TCP 双向通，Safari 正常 |

别的机型我没试过，自检命令在 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。

## 文件

```
app/                    App（Compose 界面 + 磁贴 + 开机广播）
scripts/tether_on.sh    切 NCM、配网络栈、起看门狗
scripts/tether_off.sh   清干净、回 MTP
scripts/tether_power.sh 供电三档
scripts/tether_watch.sh 看门狗
docs/                   原理、排错、兼容性
tools/aapt2             arm64 的 aapt2
```

## 谢谢

- [ReVanced/aapt2](https://github.com/ReVanced/aapt2)，arm64 构建全靠它
- 上面提到的那几个项目，尤其是 dcode-co 那篇记录，省了我不少时间
- 还有帮我确认「iPad 不回 ping 是正常的」的那位，我差点又去改防火墙

MIT。

---

**English, briefly:** Android's USB tethering defaults to RNDIS, which Apple never supported. Switching the gadget to CDC-NCM makes the iPad see a normal Ethernet adapter. On phones whose TetheringManager then takes over, existing projects already solve it — use those. This repo is for the other case: the ROM leaves the NCM interface alone (`Upstream wanted: false`), so everything has to be done by hand, including the two policy-routing rules that most people miss. Tested on one phone (OPPO PJX110, ColorOS 16, KernelSU) with an iPad. Requires root. See [docs](docs).