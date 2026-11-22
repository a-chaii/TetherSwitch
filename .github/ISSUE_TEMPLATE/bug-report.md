---
name: 不工作了
about: 插上没反应、有网卡但不通、切完自己关了……都往这儿写
---

# 出了什么事

（一两句话就行。比如「iPad 上根本没有以太网」「有以太网但一直转圈」「用着用着自己关了」）

# 你的机器

- 手机型号 / 系统版本：
- root 方式（KernelSU / Magisk / 其他）：
- 对面是什么设备（iPad / iPhone / Mac，系统版本）：
- 用的是 App 还是脚本：

# 请先跑这个，把输出贴上来

```sh
su -c '
getprop ro.product.model
getprop ro.build.version.release
getprop sys.usb.state
ls /config/usb_gadget/g1/functions/
dumpsys tethering | head -40
cat /sys/class/net/usb0/carrier 2>/dev/null
ip -o -4 addr show usb0
ip rule show | head -20
cat /proc/sys/net/ipv4/ip_forward
'
```

如果脚本跑过，再加一句日志：

```sh
su -c 'tail -20 /data/local/tmp/watch.log'
```

# 已经试过什么

（比如换线、重启、手动填 IP、删了规则重加。写了我就不重复问了）

---

不用写得很整齐，能看懂就行。如果只是问「我这台能不能用」，把上面那段里 `functions/` 和 `dumpsys tethering` 的结果贴出来就够了。