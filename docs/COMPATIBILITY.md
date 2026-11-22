# 哪些机器能用

老实说：我只在一台手机上验证过。下面写清楚怎么自己判断。

## 先跑这个

```sh
su -c '
echo "== gadget 里有哪些 function =="
ls /config/usb_gadget/g1/functions/ | grep -E "ncm|rndis"

echo "== tethering 认什么网卡 =="
dumpsys tethering | grep -E "tetherableUsbRegexs|tetherableNcmRegexs|mUsbTetheringFunction"

echo "== 现在 USB 是什么 =="
getprop sys.usb.state
'
```

## 怎么读结果

**`functions/` 里有 `ncm.xxx`** —— 内核支持 NCM，可以往下走。

只有 `rndis.xxx` 的话就到此为止了，这台机器没法走这条路，只能 WiFi 热点或者蓝牙。这不是软件能解决的。

**切完 NCM 之后，再跑一次 `dumpsys tethering`**，看有没有这一行：

```
usb0 - TetheredState
```

有，说明系统自己接管了：地址、DHCP、NAT 它都会配好。这种情况**不需要这个仓库**，用 [lipangeng/kernelsu-auto-ncm](https://github.com/lipangeng/kernelsu-auto-ncm) 或 [DurkaEbanaya/usb-tether-mode-switcher](https://github.com/DurkaEbanaya/usb-tether-mode-switcher) 更省事，它们就是干这个的。

如果只有 `Upstream wanted: false`，和我这台一样，那就往下看。

## 我这台

OPPO PJX110，ColorOS 16 / Android 16，KernelSU。gadget 里有 `rndis.rndis`、`gsi.rndis`、`ncm.gs6` 三个。切 NCM 不会被回滚，重启后回 RNDIS。

Tethering 不接管，全部得手工配。供电用的是 `oplus_chg` 那套私有节点。

结果：iPad（iPadOS 27.3）能正常上网，WiFi 和移动数据两种上行都试过。

这台机器还有个毛病：切 USB function 的时候偶尔会把整机搞重启。看着像 OTG 供电设计上的问题，跟软件无关，别的机型应该不会遇到。

## 别人的机器（不是我测的）

- Motorola XT2601-1：系统自动接管 `usb1`，切完就有网
- Redmi 14C / HyperOS 15：`svc setFunctions` 会被回滚，要用 `tether_force_usb_functions` + 重启

这两条都是从别人仓库里读来的，我没条件复现，仅供参考。

## 明确不行的

- 没有 root 的机器，所有操作都做不了
- 内核里没有 NCM gadget 的机器
- 想一边共享一边插充电器：单口 OTG 同时只能干一件事，除非机器支持旁路充电

## 想报个新机型

给我这些就够了：

```sh
su -c '
getprop ro.product.model
getprop ro.build.version.release
ls /config/usb_gadget/g1/functions/
dumpsys tethering | head -40
cat /sys/class/net/usb0/carrier 2>/dev/null
ip rule show | head -30
'
```

再说一句：`svc usb setFunctions ncm` 成功了没有、有没有被回滚、`watch.log` 里有没有报错。

有截图或者抓包更好，但也别勉强。