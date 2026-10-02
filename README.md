# Enco X3 LC3

为 OPPO Enco X3 补充 LE Audio / LC3 连接与回连流程的 LSPosed 模块。**公开版可配置自己的耳机地址，不需要修改 APK。**

目前依据一加 11（PHB110）、ColorOS 16.0.5 / Android 16 的蓝牙实现开发。它调用 ROM 已有的 HFP、LE Audio、CSIP 和音量控制服务，不提供缺失的蓝牙驱动或系统服务。

## 下载和验证状态

[下载 v0.15 公开预览版](https://github.com/Sakurakilove/enco-x3-lc3/releases/tag/v0.15-public-preview)

| 版本 / 场景 | 验证情况 |
| --- | --- |
| v0.14，一加 11 + Enco X3 | 已实机确认双耳 LC3、耳机/手机音量控制、回盒后恢复连接 |
| v0.14，完全断联后先取左耳再取右耳 | 用户单独手动测试，确认正常 |
| LC3 音频证据 | 原生日志显示 48 kHz、10 ms、每耳 155 字节帧、两个独立 CIS，左右耳加密连接 |
| v0.15 新增的地址配置和自动识别入口 | 已编译、签名校验和主机逻辑测试；尚未完成手机实测 |
| 其他机型、其他 ColorOS 版本、其他耳机 | 尚未验证 |

v0.15 保留 v0.14 的连接流程，增加本机目标地址配置和自动识别；不再包含开发者的耳机地址。它以 **Pre-release** 发布。不能把 v0.14 的实测结果当成所有机型或新增入口都已验证。

## 使用条件

- 手机已 Root，安装可正常工作的 LSPosed。
- 耳机为 Enco X3，先在系统蓝牙里正常配对。
- ROM 已具备可工作的 LE Audio、CSIP 和 VCP 服务；本模块使用的是所检查 ColorOS 实现的内部接口。
- 测试手机此前已打开系统 LE Audio 相关属性并设置设备准入配置。公开模块不会自动修改系统属性或白名单；尚未验证其他手机初始配置能否直接使用。
- 相同耳机不代表其他手机 ROM 一定兼容。仅硬件支持 LC3、但系统缺少 LE Audio 实现时，本模块无法补齐整个协议栈。

## 安装和首次配置

1. 从 Release 下载 `Enco-X3-LC3-v0.15-public-preview.apk` 和 `enco-lc3-control.sh`，安装 APK。
2. 在 LSPosed 启用 **Enco X3 LC3**，作用域勾选 **蓝牙 / com.android.bluetooth**。公开版不需要勾选系统框架、设置或无线耳机应用。
3. 先在系统蓝牙里正常配对 Enco X3，双耳保持盒外。
4. 在 Termux 执行自动配置，不用填写地址：

```sh
su -c 'sh /sdcard/Download/enco-lc3-control.sh configure'
```

自动识别只读取系统已配对设备的名称、地址和缓存服务 UUID，匹配 Enco X3 与经典 A2DP/HFP 服务，以区分主耳机和独立 LE 成员。已有的目标仍然配对时会沿用保存选择，不会自动换到另一副。

若配对了多副 Enco X3、设备改过名字或缓存服务不足，脚本会报错。可先普通连接一次后重试，或从系统蓝牙的已配对设备详情获取**主地址**并手动指定：

```sh
su -c 'sh /sdcard/Download/enco-lc3-control.sh configure AA:BB:CC:DD:EE:FF'
```

请替换示例地址，不要使用扫描到的随机地址或第二只耳机的独立 LE 地址。切换到另一副耳机时，先断开旧耳机并显式指定新主地址。

脚本会重启蓝牙进程，让 LSPosed 加载新版，然后保存地址并请求连接。`result=1` 表示模块接收/执行了该步骤；实际 LC3 成功需要声音、LE Audio 状态或原生日志确认。

地址保存在蓝牙服务的私有配置中，**只需配置一次**。以后模块随正常连接工作，不需要每次执行脚本。切换到另一副耳机时，先断开旧耳机，再重新配置新主地址。第二只耳机由原生 CSIS/SIRK 匹配识别，无需手工填入。

无须为每次版本更新重启整部手机，更新后重启蓝牙进程即可。

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

## 实现

- 只作用于已配置、已配对的主耳机和原生 CSIS 确认的同组另一只耳机。
- 经 HFP 调用 ROM 的原生 `setLeAudioStatus(device, 2)`，观察真正的 `+MTK=FFFAFB000101FF` 广播请求发送，再排队正常 LE/CSIP 状态机连接。
- 为目标连接绕过 ROM 的缓存 UUID 门槛；仍由真实 GATT 服务、PACS/ASCS、CSIS/SIRK 和原生加密配对决定是否能连接。
- 处理特定 ROM 缓存清理误删同组成员、普通蓝牙激活覆盖已选 LE、配置在自动重配对时丢失，以及真实 LE 成员的 VCP 连接。
- 回连重试次数和时间窗口有界；手动断开、关闭 LE 或恢复普通蓝牙会停止相应强开流程。
- 地址设置和恢复命令的广播接收器要求 `BLUETOOTH_PRIVILEGED`，脚本以 Root 调用。

不伪造 UUID、编解码器、连接结果或 CSIS 组成员；不绕过原生配对安全检查。源码中的类名、方法名和字段名依赖该 ColorOS 实现。

## 已知边界

测试手机在一次回盒回连时仍发生过底层自动重建配对；v0.14 能恢复 LE 配置并连接双耳，根因和回连耗时仍有优化空间。模块没有合并系统设置中的两条耳机记录。

长时间播放、通话结束后的音乐恢复、手机重启、长时间回盒，以及不同系统版本还需要进一步测试。公开版默认没有目标地址，未配置时不会主动选择其他已配对耳机。

## 欢律 / 无线耳机应用

公开版只 Hook 蓝牙服务，未修改欢律或无线耳机的降噪、手势、均衡器、查找耳机或固件升级逻辑。此前用户反馈实际使用没有问题，但没有逐项记录所有功能测试。

本地检查的无线耳机 17.6.3 和欢律 17.6.5 均包含 LE Audio 控制通道处理。无线耳机应用的功能过滤逻辑按机型配置处理高音质、空间音频、个性化听感、多设备、佩戴检测、游戏模式和固件升级等功能；某些操作可能要求先恢复 SPP 控制连接，是否触发取决于机型配置和当前状态。不能仅凭应用界面正常就判定所有操作均兼容。

建议在 LC3 播放时确认降噪/通透、手势、均衡器、查找耳机和电量更新；再单独确认通话、游戏/空间音频及多设备切换。固件升级仍未实测，需检查应用是否要求切换连接模式。LDAC 等经典蓝牙编码与 LE Audio 的 LC3 是不同播放模式，切换编码后需重新核实当前实际连接。

模块不读取或导出蓝牙配对密钥，不需要用户填密钥。连接、加密与同组耳机的 SIRK 匹配由系统原生蓝牙完成。

## Enco X4 适配资料采集

Release 附带 `collect-enco-x4.sh`。将它交给有 Enco X4 的用户，先正常连接 X4、双耳取出并播放音乐，在 MT 管理器中以 Root 权限执行。

脚本启动即开始记录，默认给用户 30 秒准备，准备期间也持续记录；随后再留 8 秒复现操作，并读取系统属性、蓝牙缓存/服务/组状态及音量路由。所有结果汇总到同一份日志，总计通常约 40–50 秒。可传入 0–120 秒的准备时间，例如 `sh collect-enco-x4.sh 60`。结果位于 `/sdcard/Download/Enco-LC3/X4/enco-x4-日期-时间-进程号.txt`。

脚本不重启蓝牙、不取消配对、不修改设置，不读取蓝牙配置密钥文件或 btsnoop 原始包。日志保留设备名称、地址和组信息以帮助分析，公开发布前需隐去无关设备信息。请同时注明手机系统、耳机固件和实际播放编码。

**这只是 X4 的资料采集工具。当前模块的自动识别仅支持 X3，没有验证或宣称可直接强开 X4。**

## 构建和检查

当前验证过的构建环境为 Termux，工具包括 JDK、`d8`、`aapt`、`apksigner`、Python、curl 和 sha256sum。其他环境需自行提供相同工具。

```sh
bash tools/fetch-build-libs.sh
python tests/verify-policy-recovery.py
python tests/verify-target-config.py
bash build.sh
```

依赖脚本从 Maven Central 和官方 Xposed API 站点下载仅用于编译的 API JAR，并验证固定 SHA-256。JAR 不入库。

构建产物为 `build/Enco-X3-LC3.apk`。首次本地构建默认生成自己的 `signing.p12`；私钥已被 `.gitignore` 排除。官方 Release 的签名与原 v0.14 保持一致。使用自己的签名构建时，不能直接覆盖不同签名的已安装 APK。

可通过环境变量指定自己的签名文件和密码：

```sh
export ENCO_SIGNING_KEYSTORE=/path/to/your-signing.p12
export ENCO_SIGNING_PASSWORD='your-local-password'
bash build.sh
```

主机测试直接提取生产恢复/配置方法，使用假的 Android 服务检查地址校验、自动识别主耳/歧义拒绝、持久化失败、切换目标、显式关闭、暂停、未配对和连接策略恢复。它们不验证无线连接或手机内部 Hook 是否兼容。

## 参考

- [Xposed API](https://api.xposed.info/)
- [Android Bluetooth 模块](https://android.googlesource.com/platform/packages/modules/Bluetooth/)

项目以对所测试 ROM 的代码分析和实际原生连接日志为依据。仓库不分发厂商反编译源码或固件。
