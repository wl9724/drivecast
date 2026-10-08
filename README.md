# DriveCast

让国内手机把导航、音乐等应用投到**安卓车机**上，并在车机屏幕上直接操作。

- 不依赖 CarLife / HiCar / CarPlay 等需要授权的协议
- 全部代码自研，只用公开的系统 API 和协议文档
- Apache-2.0 开源

> **状态：P0 技术验证中。** 目前只实现了车机端通过 USB 与手机完成 ADB 握手并执行 shell 命令，还不能投屏。尚未在真车上验证。

## 工作原理

车机作为 USB Host，用自写的 ADB 客户端连接手机（手机需打开 USB 调试），
在手机上以 shell 身份启动投屏服务：创建一块与车机分辨率相同的横屏虚拟屏，
H.264 编码后传回车机解码显示，车机的触摸再注入回这块虚拟屏。
手机自己的屏幕照常使用。

## 支持范围

| | 状态 |
|---|---|
| 安卓车机（后装大屏、允许侧载的原车如比亚迪 DiLink） | 目标 |
| 安卓手机 / 鸿蒙 2～4 | 目标（P1） |
| iPhone（ReplayKit，只能显示） | 规划（P3） |
| 鸿蒙 NEXT | 规划（P4） |
| 鸿蒙座舱、QNX、Linux、AliOS 等非安卓车机 | 不支持 |

## 试用 P0

1. 从 [Actions](../../actions) 最新一次成功构建下载 `drivecast-car-debug`，把 APK 装到车机上。
2. 手机打开**开发者选项 → USB 调试**（小米还需打开"USB 调试（安全设置）"）。
3. 用能传数据的 USB 线把手机连到车机，打开 DriveCast，点"连接手机并执行 echo hello"。
4. 允许 USB 权限；手机弹出授权框时勾选"一律允许"并确认。
5. 屏幕上出现 `echo hello → hello` 和手机型号即为成功。失败请把屏幕上的日志、车机型号和系统版本发到 Issues。

## 构建

需要 JDK 17 和 Android SDK（compileSdk 34）。

```sh
./gradlew :car-android:testDebugUnitTest :car-android:assembleDebug
```

## 路线图

| 阶段 | 内容 |
|---|---|
| P0 | 车机 USB ADB 握手 + shell（当前） |
| P1 | 协议 v1、车机解码与触控、安卓 Shell 模式投屏、简易桌面 |
| P2 | 安卓 App 模式、蓝牙握手 + Wi-Fi 无线 |
| P3 | iPhone ReplayKit |
| P4 | 鸿蒙 NEXT |

协议草案见 [docs/protocol.md](docs/protocol.md)。

## 许可证

[Apache-2.0](LICENSE)。本项目与 Google、Apple、华为、百度及任何车企无关。
