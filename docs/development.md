# 开发与构建

[返回首页](../README.md)

## 构建和检查

构建需要 JDK（含 `javac`、`jar`、`keytool`）、Android Build Tools（`d8`、`aapt`、`apksigner`）、Python、curl 和 sha256sum。已在 Termux 和 Linux 环境构建；请将这些工具加入 `PATH`。

```sh
bash tools/fetch-build-libs.sh
python tests/verify-policy-recovery.py
python tests/verify-target-config.py
python tests/verify-control-recovery.py
python tests/verify-setup-lifecycle.py
python tests/verify-log-store.py
python tests/verify-mode-choice.py
bash build.sh
```

依赖脚本从 Maven Central 和官方 Xposed API 站点下载仅用于编译的 API JAR，并验证固定 SHA-256。JAR 不入库。

构建产物为 `build/Enco-X3-LC3.apk`。首次本地构建默认生成自己的 `signing.p12`；私钥已被 `.gitignore` 排除。安装或升级前请查看对应 Release 的签名说明。使用自己的签名构建时，不能直接覆盖不同签名的已安装 APK。

可通过环境变量指定自己的签名文件和密码：

```sh
export ENCO_SIGNING_KEYSTORE=/path/to/your-signing.p12
export ENCO_SIGNING_PASSWORD='your-local-password'
bash build.sh
```

主机测试直接提取生产恢复/配置/分组控制方法，使用假的 Android 服务检查地址校验、自动识别主耳/歧义拒绝、持久化失败、切换目标、显式关闭、暂停、未配对和连接策略恢复、晚到的 ALLOWED 策略、单成员 VCP、重复连接抑制、电量及媒体/通话授权。它们不验证无线连接或手机内部 Hook 是否兼容。

## 实现

- 只作用于已配置、已配对的主耳机和原生 CSIS 确认的同组另一只耳机。
- 经 HFP 调用 ROM 的原生 `setLeAudioStatus(device, 2)`，观察真正的 `+MTK=FFFAFB000101FF` 广播请求发送，再排队正常 LE/CSIP 状态机连接。
- 为目标连接绕过 ROM 的缓存 UUID 门槛；仍由真实 GATT 服务、PACS/ASCS、CSIS/SIRK 和原生加密配对决定是否能连接。
- 处理特定 ROM 缓存清理误删同组成员、普通蓝牙激活覆盖已选 LE、配置在自动重配对时丢失，以及真实 LE 成员的 VCP 连接。
- 回连重试次数和时间窗口有界；手动断开、关闭 LE 或恢复普通蓝牙会停止相应强开流程。
- 地址设置和恢复命令的广播接收器要求 `BLUETOOTH_PRIVILEGED`，脚本以 Root 调用。

不伪造 UUID、编解码器、连接结果或 CSIS 组成员；不绕过原生配对安全检查。源码中的类名、方法名和字段名依赖该 ColorOS 实现。

## 分组控制恢复

在真实 LE 成员连接、原生 CSIS 成员可用、LE 策略恢复为 ALLOWED 或原生组建立后，统一检查同组已连接成员。首次检查立即进行，随后间隔 1.5、2.5、4、6、8 秒重试，总窗口 22 秒；同一目标的并行触发会合并。

- CSIP 分组控制和 VCP 音量控制走原生状态机。已在连接中的设备不重复排队。
- 缓存里确实有 BAS 服务时，以正常接口恢复电量连接。
- 媒体/通话服务使用原生授权查询入口，UNKNOWN 由系统现有 LE/CSIP 策略处理；保留 DENIED。
- 所有恢复限于已配对、已选择 LE、实际属于同组的目标成员。明确关闭的连接策略、暂停和手动断开均保留。

## 参考

- [Xposed API](https://api.xposed.info/)
- [Android Bluetooth 模块](https://android.googlesource.com/platform/packages/modules/Bluetooth/)

项目以对所测试 ROM 的代码分析和实际原生连接日志为依据。仓库不分发厂商反编译源码或固件。
