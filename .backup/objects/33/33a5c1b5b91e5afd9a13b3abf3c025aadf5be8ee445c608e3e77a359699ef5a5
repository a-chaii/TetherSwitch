# Changelog

## 0.1.0 — 2026-11-22

首个公开版本。

**脚本**
- `tether_on.sh`：切 NCM、配 `usb0` 地址、开启转发、NAT、双向 FORWARD、两条策略路由、dnsmasq
- `tether_off.sh`：清理规则、停守护、恢复 MTP 与供电开关
- `tether_power.sh`：供电方向三档（sink / source / off）
- `tether_watch.sh`：看门狗（断线 20 秒自动清理、上行切换迁移、地址丢失自愈）

**App**
- Compose 界面：状态（carrier / IP / 上行 / 电量 / root）、开关、供电方向、开机自启、执行输出
- Quick Settings 磁贴（含 Toast 反馈与 busy 防抖）
- BootReceiver：开机约 20 秒后自动开启（默认关闭）

**文档**
- 实测原理（含 `Upstream wanted: false`、策略路由成因、无效 sysfs 节点记录）
- 排错手册（症状表 + 三处必查 + 抓包定位）
- 兼容性自检

**已知问题**
- 重启后需重新执行（NCM 会回到 RNDIS）
- `tether_force_usb_functions` 持久方案未在本机验证
- 测试机型存在 OTG 偶发重启（设备层面缺陷，非本工具引入）