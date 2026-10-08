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

车机通过 ADB 把手机端服务推到 `/data/local/tmp/drivecast-server.apk`，再打开流
`exec:CLASSPATH=/data/local/tmp/drivecast-server.apk app_process / org.drivecast.server.Server`。
这条流的 stdin/stdout 就是协议通道（v1 不需要端口转发，也不需要多条流）。

手机端启动后先输出 4 字节魔数 `DCv1`，车机跳过魔数之前的任何杂散输出。

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
| `0x40` | PING / PONG | 双向 | 预留，未实现 |
| `0x7F` | BYE | 双向 | 原因（UTF-8 文本） |

- 宽高由车机按自己的画面区域给出，取 16 的倍数。
- 视频解码器在收到 VIDEO_CONFIG 时（重新）创建。

参考实现：`protocol/src/main/kotlin/org/drivecast/protocol/Protocol.kt`。

## 握手

1. 车机建立传输（ADB 流或 TCP），手机输出魔数 `DCv1`。
2. 车 → 手 `HELLO`；手机据此创建虚拟屏和编码器。
3. 手 → 车 `HELLO_ACK`，然后 `VIDEO_CONFIG`。
4. 手机持续发送 `VIDEO_FRAME`；车机发送 `TOUCH` / `KEY`。
5. 车机解码器重建或丢帧后发送 `REQUEST_KEYFRAME`。

音频 v1 不走本协议：手机通过蓝牙 A2DP 连接车机播放。
