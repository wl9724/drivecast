# DriveCast 鸿蒙 NEXT 端

鸿蒙 NEXT（HarmonyOS 5/6/7，纯血鸿蒙）手机上的 DriveCast：录屏编码成 H.264，经过配对认证、逐帧加密的 TCP 连接发给车机。
和 iPhone 端用**同一套协议**（[docs/protocol.md](../docs/protocol.md)「iPhone：TCP + 配对 + 加密」），车机端不用改。
**只能显示**，车机上不能操作手机：鸿蒙手机上的注入点击接口只开放给系统应用或 PC/2in1，第三方 App 没有办法。

> **状态：尚未在真机上验证。** CI 能证明它能编译打包、协议逻辑和测试向量一致，不能证明在手机上能录屏、能连上车机。

| 目录 | 内容 |
|---|---|
| `entry/src/main/cpp/` | NAPI 模块 `libcast.so`：`OH_AVScreenCapture`（surface 模式）直接画进 `OH_VideoEncoder` 的输入 surface，编码输出交给 ArkTS |
| `entry/src/main/ets/core/` | 帧编解码、配对 / 认证 / 加密（`@kit.CryptoArchitectureKit`）、TCP 连接、关键资产和首选项、协议自检 |
| `entry/src/main/ets/workers/Caster.ets` | 投屏 Worker：找车机、认证、收发加密帧、断线退避重连，录屏不随重连停止 |
| `entry/src/main/ets/pages/Index.ets` | 界面：车机列表（mDNS）、配对、开始 / 停止投屏、协议自检 |
| `entry/src/ohosTest/` | hypium 测试：和 App 里的「协议自检」是同一个函数 |
| `tools/` | 在 Node 上跑协议自检（用 `node:crypto` 模拟 cryptoFramework），CI 用 |

## 安装（自签名，不上架）

1. 从 [Actions](../../../actions) 里 **HarmonyOS** 工作流最新一次成功的运行下载 `DriveCast-harmony-unsigned-hap`，解压出 `entry-default-unsigned.hap`。
   **未签名的 HAP 装不上手机**，要用自己的华为账号签名：
2. 手机打开开发者模式：设置 → 关于本机 → 连续点"软件版本"7 次（会重启），再到 设置 → 系统 → 开发者选项 打开"USB 调试"（或"无线调试"）。
   开发者模式要一直开着：关掉后调试签名的 App 装不上（错误 17700052），社区反馈也打不开。
3. 二选一签名并安装：
   - **DevEco Studio**（Windows / macOS，官方）：打开本目录 `harmony/`，登录华为账号，File → Project Structure → Signing Configs 勾选
     "Automatically generate signature"，手机连上电脑后点运行。本项目用公开的 OpenHarmony SDK 构建，签名时勾选 "Support HarmonyOS"。
     自动签名每个账号 30 天内最多 150 次。
   - **小白调试助手**（第三方工具，Windows / macOS / Linux / 安卓）：用自己的华为账号登录，拖入 HAP 自动签名安装。
     这需要把华为账号交给第三方工具，是否符合华为的条款不清楚，自己权衡。
4. 证书有效期：华为账号**没实名认证的调试证书 14 天**、**实名认证后 1 年**，过期后 App 装不上也打不开，重新签名安装一次即可。
   调试证书绑定设备，每个账号每年最多登记 100 台设备。

上架应用市场（AppGallery）才能让普通用户直接安装、不开开发者模式。这需要维护者用实名认证的开发者账号申请发布证书、通过审核，
可能还要 APP 备案（只连车机、不上网的 App 能否按"单机 APP"免备案还不清楚），长时任务的用途也要过审。目前没有做。

## 使用

1. 手机和车机连到同一个 Wi-Fi：手机连车机的热点；或车机连手机的热点；或都连同一个路由器。
2. 配对（每台手机只做一次）：车机打开 DriveCast，点左侧"**添加 iPhone/鸿蒙**"，2 分钟内在手机的 DriveCast 里点找到的车机
   （找不到就手动输入车机屏幕上显示的地址），再输入车机上显示的 6 位**配对码**。输错一次这个码就作废，车机会显示新码。
3. 投屏：上车后打开 DriveCast，点"**开始投屏**"，在系统弹出的录屏确认框里点允许，等车机出现画面后再切到导航 App。

注意：

- **每次开始录屏系统都会弹确认框**（鸿蒙不允许第三方 App 跳过），所以请停车时点开始。之后车机断开重连（熄火、Wi-Fi 闪断）不会再弹，
  录屏一直开着直到你点"停止投屏"或在状态栏的录屏胶囊里停止。
- 请保持手机**屏幕常亮、导航 App 在前台**：锁屏或熄屏后能否继续录屏还没验证，很可能会中断。
- 声音请让手机通过**蓝牙**连接车机播放（不录音频）。
- 车机上**只能看，不能操作手机**。
- 系统会遮挡隐私内容：密码框、支付页面等隐私窗口在车机上是黑块（只遮那一块，不是整屏变黑），输入法、通知栏、通话界面也可能被遮挡。
- 竖屏手机在横屏车机上居中显示，两侧是黑边。来电时继续投屏（声音走蓝牙）。
- 车机上点"**清除 iPhone/鸿蒙 配对**"会删掉所有手机的配对，之后要重新配对。

## 构建

CI（`.github/workflows/harmony.yml`）在 ubuntu 上用**公开的 OpenHarmony SDK**（`openharmony-rs/setup-ohos-sdk`，API 20）和 npm 上的
`@ohos/hvigor` / `@ohos/hvigor-ohos-plugin` 无头构建，不需要登录华为账号下载 HarmonyOS 命令行工具，做法和 Servo 一样：

```sh
cd harmony
export NODE_PATH=$HVIGOR_PATH/node_modules   # npm install @ohos/hvigor@5 @ohos/hvigor-ohos-plugin@5（registry https://repo.harmonyos.com/npm/）
node $HVIGOR_PATH/node_modules/@ohos/hvigor/bin/hvigor.js --no-daemon assembleHap -p product=default -p buildMode=release
```

`runtimeOS` 是 OpenHarmony、`compatibleSdkVersion` 是 20（HarmonyOS 6.0.0）。用到的录屏、编解码、密码、网络、关键资产都是 OpenHarmony 的公开 API，
华为文档说明 OpenHarmony 工程可以用 HarmonyOS 证书签名后装到鸿蒙手机上。用 DevEco Studio 打开 `harmony/` 也能直接构建和跑 ohosTest。

协议逻辑改动后可以先在电脑上跑：`harmony/tools/selftest-node.sh`（Node 22.6+）。

### CI 验证了什么

| | |
|---|---|
| ArkTS 编译和类型检查、NAPI 模块（C++）编译链接、打包未签名 HAP | ✅ 每次构建 |
| 协议逻辑对照 `docs/testvectors/ios-pairing.json`（Node 上用 `node:crypto` 模拟 cryptoFramework） | ✅ |
| App 里打包的测试向量和 `docs/testvectors/` 一致 | ✅ |
| 系统 cryptoFramework 的实际行为（空负载 GCM、PKCS#8 私钥导入、不在曲线上的点） | ❌ 要在真机上点 App 里的「协议自检」 |
| ohosTest（hypium） | ❌ CI 没有 ohpm 装 hypium，只编译不了；用 DevEco Studio 跑 |
| 录屏、编码器输出格式（Annex B / CODEC_DATA）、长时任务、mDNS、连车机 | ❌ 要真机 |

## 已知限制和待真机确认

- 只能显示，不能反向控制（见上）。HELLO_ACK 回 `displayId = -1`，车机不发触摸。
- 车机分辨率变了（换了一台车机）只能重新开始录屏，会再弹一次确认框。
- 地图 App（花瓣地图、高德）的地图画面能否录到、`SetCanvasRotation` 横屏效果、画面静止时编码器是否按 100ms 重复上一帧，都要真机确认。
- 编码器不接受 Baseline + CBR 时会退回默认设置，老车机可能解不了 High profile。
- 车机连手机热点时，mDNS 能否在热点网卡上工作没有验证，找不到就用上次连上的地址或手动地址。只用 IPv4。
- 后台保活用的是"录制"类长时任务（`audioRecording`），系统是否认可录屏投屏属于这一类要真机确认。
- GCM 空负载（车机每秒发的 PING）的 tag 是自己算的（系统 GCM 不给数据时不算 AAD），App 里的「协议自检」会核对。
