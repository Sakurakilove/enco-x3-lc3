# 命令行与诊断

[返回首页](../README.md)

普通安装只需要 APK。以下操作使用 Release 附带的 `enco-lc3-control.sh`，下载后放在手机的 `Download` 目录，以 Root 执行；APK 内置同一脚本。

## 一键配置

```sh
su -c 'sh /sdcard/Download/enco-lc3-control.sh setup'
```

自动识别不明确时，可在 `setup` 后传入已配对主耳机地址。`configure [auto 或主地址]` 只运行重启、配置和连接请求，不包含一键流程的双耳等待检查。

## 恢复普通蓝牙和采集状态

暂停强开并恢复目标耳机的普通通话/媒体连接策略：

```sh
su -c 'sh /sdcard/Download/enco-lc3-control.sh restore'
```

随后需要再次请求 LC3：

```sh
su -c 'sh /sdcard/Download/enco-lc3-control.sh start'
```

只读采集模块和蓝牙状态，并将结果写入 Termux 当前目录：

```sh
su -c 'sh /sdcard/Download/enco-lc3-control.sh collect' > lc3-report.txt
```

排查时可提交系统版本、耳机型号、具体操作和裁剪后的相关日志。日志可能包含设备地址和名称，请在公开提交前删去无关个人信息。仓库不包含原始手机日志、蓝牙配对密钥或 APK 签名私钥。

## 单独恢复控制服务

在已经选择并连接 LE 组时运行：

```sh
su -c 'sh /sdcard/Download/enco-lc3-control.sh repair-controls'
```

此命令不重启蓝牙，不重置音量或左右平衡，也不修改配对密钥。`result=1` 只表示恢复任务已排队，不代表双耳已经就绪。

只需要重新加载模块时，可以使用 `restart` 重启蓝牙进程；所有已连接的蓝牙设备会暂时断开。

## Enco X4 适配资料采集

Release 附带 `collect-enco-x4.sh`。将它交给有 Enco X4 的用户，先正常连接 X4、双耳取出并播放音乐，在 MT 管理器中以 Root 权限执行。

脚本启动即开始记录，默认给用户 30 秒准备，准备期间也持续记录；随后再留 8 秒复现操作，并读取系统属性、蓝牙缓存/服务/组状态及音量路由。所有结果汇总到同一份日志，总计通常约 40–50 秒。可传入 0–120 秒的准备时间，例如 `sh collect-enco-x4.sh 60`。结果位于 `/sdcard/Download/Enco-LC3/X4/enco-x4-日期-时间-进程号.txt`。

脚本不重启蓝牙、不取消配对、不修改设置，不读取蓝牙配置密钥文件或 btsnoop 原始包。日志保留设备名称、地址和组信息以帮助分析，公开发布前需隐去无关设备信息。请同时注明手机系统、耳机固件和实际播放编码。

**这只是 X4 的资料采集工具。当前模块的自动识别仅支持 X3，没有验证或宣称可直接强开 X4。**
