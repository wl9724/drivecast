# DriveCast 协议 v1（草案）

手机端各平台（安卓 Shell 模式、安卓 App 模式、iOS、鸿蒙 NEXT）与车机端之间共用的协议。
传输层可以是 ADB 流（`localabstract:drivecast`）或 TCP。

## 帧格式

```
┌──────────┬──────────────────┬───────────────┐
│ type u8  │ length u32 (BE)  │ payload[len]  │
└──────────┴──────────────────┴───────────────┘
```

- 多字节整数一律大端序。
- 坐标一律是**视频画面的像素坐标**，由车机端负责从屏幕坐标换算。

## 启动

车机通过 ADB（USB，或手机执行过 `tcpip:5555` 后的 TCP）把手机端服务推到
`/data/local/tmp/drivecast-server.apk`（`exec:head -c <字节数> > 路径`），再打开流
`exec:CLASSPATH=/data/local/tmp/drivecast-server.apk app_process / org.drivecast.server.Server 2>/dev/null`。
手机端监听抽象 Unix socket `drivecast` 后在这条流上输出魔数 `DCv1`，车机随即打开 `localabstract:drivecast`，
**协议跑在这个 socket 上**。exec 流保持打开，只用来维持进程：车机关闭它，手机端就退出。

不直接用 exec 流的 stdin/stdout：adbd 给 `exec:` 分配的是 raw 模式 PTY，每次 WRTE 往返只能搬约 4KB，
Wi-Fi 下（往返 10~20ms）吞吐低于视频码率；stderr 也会混进 PTY。

协议 socket 上，发送端（手机）也先输出魔数 `DCv1`，接收端跳过魔数之前的任何杂散数据。

## 消息

| 类型 | 名称 | 方向 | 负载布局 |
|---|---|---|---|
| `0x01` | HELLO | 车 → 手 | version u8 · width u16 · height u16 · dpi u16 · fps u8 · bitrate u32 |
| `0x02` | HELLO_ACK | 手 → 车 | version u8 · displayId i32 |
| `0x10` | VIDEO_CONFIG | 手 → 车 | width u16 · height u16 · SPS/PPS（H.264 Annex B） |
| `0x11` | VIDEO_FRAME | 手 → 车 | pts u64（μs） · flags u8（bit0 关键帧） · H.264 Annex B 数据 |
| `0x12` | REQUEST_KEYFRAME | 车 → 手 | 空（预留，未实现） |
| `0x20` | TOUCH | 车 → 手 | action u8（0 按下 / 1 抬起 / 2 移动 / 3 取消） · pointerId u8（v1 恒为 0） · x u16 · y u16 |
| `0x21` | KEY | 车 → 手 | action u8（0 按下 / 1 抬起） · keycode u16（Android KEYCODE_*） |
| `0x30` | APP_LIST | 双向 | 预留，未实现 |
| `0x31` | LAUNCH | 车 → 手 | 包名（UTF-8），在虚拟屏上启动其桌面入口 |
| `0x40` | PING | 车 → 手 | 空。车机每秒发一次 |
| `0x7E` | NOTICE | 手 → 车 | 给用户看的提示（UTF-8），不中断投屏。例如手机不允许模拟点击 |
| `0x7F` | BYE | 双向 | 原因（UTF-8 文本） |

- 宽高由车机按自己的画面区域给出，取 16 的倍数。
- **心跳**：adbd 不做 TCP 保活，无线断开（比如车机断电）时手机端的 stdin 不会结束。
  手机端 5 秒收不到车机的任何消息就自行退出；车机端 10 秒收不到任何数据（视频至少每 100ms 一帧）就判定断开并重连。
- 手机端启动时会结束上一个仍在运行的实例（pid 记在 `/data/local/tmp/drivecast-server.pid`）。
- 视频解码器在收到 VIDEO_CONFIG 时（重新）创建。

参考实现：`protocol/src/main/kotlin/org/drivecast/protocol/Protocol.kt`。

## 握手

1. 车机建立传输（ADB `localabstract:` 流或 TCP），手机输出魔数 `DCv1`。
2. 车 → 手 `HELLO`；手机据此创建虚拟屏和编码器。
3. 手 → 车 `HELLO_ACK`，然后 `VIDEO_CONFIG`。
4. 手机持续发送 `VIDEO_FRAME`；车机发送 `TOUCH` / `KEY`。
5. 车机解码器重建或丢帧后发送 `REQUEST_KEYFRAME`。

音频 v1 不走本协议：手机通过蓝牙 A2DP 连接车机播放。
