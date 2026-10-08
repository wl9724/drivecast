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

## 消息

| 类型 | 名称 | 方向 | 负载 |
|---|---|---|---|
| `0x01` | HELLO | 车 → 手 | 协议版本、屏幕宽/高/DPI、支持的解码格式、最高帧率 |
| `0x02` | HELLO_ACK | 手 → 车 | 协议版本、实际宽高/帧率/码率、能力位（可否反向控制、可否虚拟屏） |
| `0x10` | VIDEO_CONFIG | 手 → 车 | 编码格式、宽、高、SPS/PPS |
| `0x11` | VIDEO_FRAME | 手 → 车 | pts u64（μs）、flags u8（bit0 = 关键帧）、NAL 数据 |
| `0x12` | REQUEST_KEYFRAME | 车 → 手 | 空 |
| `0x20` | TOUCH | 车 → 手 | action u8、pointerId u8、x u16、y u16 |
| `0x21` | KEY | 车 → 手 | action u8、keycode u16（Android KEYCODE_*） |
| `0x30` | APP_LIST | 双向 | 请求为空；响应为应用列表（包名、名称、图标 PNG） |
| `0x31` | LAUNCH | 车 → 手 | 包名（UTF-8），在虚拟屏上启动 |
| `0x40` | PING / PONG | 双向 | 时间戳 u64，用于测量往返延迟 |
| `0x7F` | BYE | 双向 | 原因码 u8 |

各字段的精确字节布局在 P1 实现时随代码一起定稿。

## 握手

1. 车机建立传输（ADB 流或 TCP）。
2. 车 → 手 `HELLO`。
3. 手 → 车 `HELLO_ACK`，然后 `VIDEO_CONFIG`。
4. 手机持续发送 `VIDEO_FRAME`；车机发送 `TOUCH` / `KEY`。
5. 车机解码器重建或丢帧后发送 `REQUEST_KEYFRAME`。

音频 v1 不走本协议：手机通过蓝牙 A2DP 连接车机播放。
