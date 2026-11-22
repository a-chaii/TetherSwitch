# 兼容性

## 自检清单（跑完再动手）

```sh
su -c '
echo "== USB gadget functions =="
ls /config/usb_gadget/g1/functions/ 2>/dev/null | grep -E "ncm|rndis"

echo "== tethering 配置 =="
dumpsys tethering | grep -E "tetherableUsbRegexs|tetherableNcmRegexs|enableBpfOffload|mUsbTetheringFunction"

echo "== 当前 usb 状态 =="
getprop sys.usb.state
'
```

判定：

| 结果 | 结论 |
|---|---|
| 有 `ncm.xxx` + `tetherableNcmRegexs` 非空 | 可以尝试 |
| 只有 `rndis.xxx` | 内核 gadget 不支持 NCM，**本项目无解** |
| 有 `ncm` 但 `dumpsys` 里没有 `tetherableNcmRegexs` | 仍可试（脚本用 `usb\d` 正则匹配），但要手工配 |
| `mUsbTetheringFunction: NCM` | 系统本身就偏向 NCM，可能不需要切换 |
| 切完 `dumpsys tethering` 出现 `usb0 - TetheredState` | 系统会自动接管 → **用别的项目更省事** |

---

## 已知设备

### 本项目实测

| 设备 | ROM | 结果 | 备注 |
|---|---|---|---|
| OPPO PJX110 | ColorOS 16 / Android 16 | ✅ 可用 | `TetheringManager` 不接管，需完整手工配置；`svc setFunctions` 不回滚；该机型存在 OTG 偶发重启的已知缺陷 |

### 其他项目覆盖（可参考，非本项目实测）

| 设备 | 项目 | 说明 |
|---|---|---|
| Motorola XT2601-1 (Android 16) | kernelsu-auto-ncm | 系统自动接管 `usb1`，只需切模式 |
| Redmi 14C (HyperOS 15) | dcode-co | `svc setFunctions` 会被回滚，需 `tether_force_usb_functions 1` + 重启 |

---

## 不支持的场景

- **无 root**：所有路径都依赖 `su`（iptables / ip rule / sysfs 写入）
- **内核无 NCM gadget**：只能走 WiFi 热点或 BT PAN
- **iPad 用 RNDIS**：不存在这种可能，Apple 没有该驱动
- **想同时充电**：单口 OTG 占用后无法同时接充电器（除非设备支持无线/旁路充电）

---

## 上报新机型时请附

```sh
su -c '
getprop ro.product.model; getprop ro.build.version.release
ls /config/usb_gadget/g1/functions/
dumpsys tethering | head -40
cat /sys/class/net/usb0/carrier 2>/dev/null
ip rule show | head -30
'
```

外加：是否用 `svc` 切成功、`dumpsys tethering` 里 `Upstream wanted` 的值、`watch.log` 内容。