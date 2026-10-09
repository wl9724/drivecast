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
| `0x12` | REQUEST_KEYFRAME | 车 → 手 | 空。车机丢帧后发送（最多每秒一次），并丢弃之后的非关键帧直到收到关键帧 |
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

## iPhone：TCP + 配对 + 加密

iPhone 没有 ADB，由 iPhone 上的 DriveCast（ReplayKit 广播扩展）**主动连车机**。车机是 TCP 服务端：

鸿蒙 NEXT 手机（`harmony/`）用的是**同一套 TCP 协议**，消息、配对、加密完全一样，车机不区分两者：本节的"iPhone"同样指鸿蒙手机。


- 监听端口 **27420**（被占用时用随机端口），双栈，所有网卡。
- 通过 mDNS/Bonjour 广播服务 `_drivecast._tcp`，TXT 记录 `id=<carId 的 32 位十六进制>`。
- iPhone 找车机的顺序：Bonjour → 上次连上的地址 → Wi-Fi 网关（iPhone 连车机热点时网关就是车机）→ 用户手动输入的地址。

同一个热点上的任何人都能连这个端口，还能解密 WPA2 流量，所以 iPhone 连接**必须先认证，之后每一帧都加密**。

### 新增消息（握手阶段明文）

| 类型 | 名称 | 方向 | 负载 |
|---|---|---|---|
| `0x50` | AUTH_CHALLENGE | 车 → 手 | carId[16] · nonceC[16] |
| `0x51` | AUTH_RESPONSE | 手 → 车 | phoneId[16] · nonceP[16] · tag[32] |
| `0x52` | PAIR_START | 手 → 车 | phoneId[16] · pkP[65] · 名称（UTF-8，≤64 字节） |
| `0x53` | PAIR_KEY | 车 → 手 | pkC[65] |
| `0x54` | PAIR_COMMIT | 双向 | c[32] |
| `0x55` | PAIR_REVEAL | 双向 | n[16] |

失败时发明文 `BYE`（原因文本）后断开。魔数必须是 TCP 连接上的前 4 字节（不像 ADB 那样跳过杂散数据），
认证之前每帧负载不超过 256 字节，超过就断开。

### 常量（`||` 表示拼接，字符串都是 ASCII）

- `pk`：P-256 公钥，65 字节未压缩格式 `04 || X || Y`。收到对方公钥必须校验在曲线上。
- `code`：车机生成的 6 位随机数（0..999999），`bit_i = (code >> i) & 1`，i = 0..19。**每次配对都换新码，失败一次就作废。**
- `commitP_i = HMAC-SHA256(key = nP_i[16], "DCv1 P" || pkP || pkC || [0x80 | bit_i])`
- `commitC_i = HMAC-SHA256(key = nC_i[16], "DCv1 C" || pkC || pkP || [0x80 | bit_i])`
- `Z` = P-256 ECDH 共享密钥的 x 坐标（32 字节）
- `LTK = HKDF-SHA256(ikm = Z, salt = 32 个 0 字节, info = "DCv1 LTK" || carId || phoneId || pkP || pkC, L = 32)`
- `tag = HMAC-SHA256(LTK, "DCv1 AUTH" || carId || phoneId || nonceC || nonceP)`
- `sess = HKDF-SHA256(ikm = LTK, salt = nonceC || nonceP, info = "DCv1 SESS" || carId || phoneId, L = 32)`；
  `kP2C = sess[0:16]`（手 → 车），`kC2P = sess[16:32]`（车 → 手）

参考实现和自检：`tools/pairing_ref.py`；两端单元测试共用的测试向量：`docs/testvectors/ios-pairing.json`
（`python3 -I tools/pairing_ref.py vectors` 生成）。

### 已配对时（每次投屏）

1. 手 → 车：明文魔数 `DCv1`。
2. 车 → 手：`AUTH_CHALLENGE(carId, nonceC)`。
3. 手机有这个 carId 的 LTK：手 → 车 `AUTH_RESPONSE(phoneId, nonceP, tag)`。
4. 车机按 phoneId 查 LTK、校验 tag（常数时间比较），不对就 `BYE`。
5. 之后双方都用加密帧。车 → 手 `HELLO`，手 → 车 `HELLO_ACK(displayId = -1)`（-1 表示不能反向控制），
   然后是 `VIDEO_CONFIG` / `VIDEO_FRAME`；车机照常每秒发 `PING`。手机能解开车机的 `HELLO` 才说明车机也持有 LTK。
6. 新的 iPhone 连接**认证通过后**才可能顶替当前的投屏，陌生人连上来不会把正在用的投屏挤掉：
   同一台 iPhone 重连会顶替自己的旧连接；另一台 iPhone 正在投屏时，新来的会收到加密的 `BYE` 被拒绝。
   认证握手总时限 10 秒；配对从显示配对码起总时限 90 秒（每次读也是 90 秒）。同一 IP 同时只允许一个握手。

### 配对（只在手机的 DriveCast App 里做，车机上要先点"添加 iPhone/鸿蒙"）

1. 同上 1、2。手机没有这个 carId 的 LTK：手 → 车 `PAIR_START(phoneId, pkP, 名称)`。
2. 车机不在配对模式（"添加 iPhone/鸿蒙" 打开后 2 分钟内）就 `BYE`。否则生成临时 P-256 密钥和新配对码，
   **在车机屏幕上显示配对码**，车 → 手 `PAIR_KEY(pkC)`。配对期间连接超时放宽到 90 秒。
3. 用户在 iPhone 上输入配对码。同一个码失败过就不再用，提示在车机上重新开始。
4. i = 0..19 每轮：手 → 车 `PAIR_COMMIT(commitP_i)`；车 → 手 `PAIR_COMMIT(commitC_i)`；
   手 → 车 `PAIR_REVEAL(nP_i)`，车机校验 commitP_i，不对就 `BYE` 并作废配对码；
   车 → 手 `PAIR_REVEAL(nC_i)`，手机校验 commitC_i，不对就断开。
5. 双方算出 LTK。手 → 车 `AUTH_RESPONSE`（用第 1 步的 nonceC），车机校验通过后保存（phoneId → LTK、名称），
   车 → 手发一条加密的 `NOTICE("paired")`；手机能解开才保存 LTK，然后双方断开。
   配对模式下最多失败 3 次。

这是蓝牙 LE Secure Connections 的 Passkey Entry 做法：逐位承诺让旁听者拿不到 LTK，
中间人在配对那一次骗过车机的概率约 2×10⁻⁵。平台 API 里没有 PAKE，这是只用 P-256、HMAC、AES-GCM 能做到的最好方案。

### 加密帧（认证之后，双向）

```
type u8 · len u32 BE (= 明文长度 + 16) · AES-128-GCM 密文 || tag[16]
```

- 密钥：手 → 车用 kP2C，车 → 手用 kC2P。
- nonce：`00 00 00 00 || ctr u64 BE`，每个方向各自从 0 开始、每帧加 1，不随帧发送（TCP 保证顺序）。
- AAD：这一帧的 5 字节头。
- 解密失败立刻断开。每个连接的会话密钥都不同，跨连接重放无效。

### iPhone 端的视频

- 广播扩展把屏幕缩放（保持比例、两侧补黑）、按 `RPVideoSampleOrientationKey` 旋转后，编码成车机 `HELLO` 给出的宽高，
  所以竖屏 iPhone 在横屏车机上居中显示，车机不用做任何适配。
- H.264 Constrained Baseline（老车机只保证支持 Baseline），画面静止时每 100ms 重发上一帧。
- 不能反向控制，车机发来的 `TOUCH` / `KEY` / `LAUNCH` 一律忽略。

### 鸿蒙 NEXT 端的视频

- 系统录屏（`OH_AVScreenCapture`，整屏）按车机 `HELLO` 的宽高建虚拟屏、等比缩放补黑边，直接画进硬件 H.264 编码器的输入 surface，
  Baseline（设备不接受时退回编码器默认），画面静止时编码器每 100ms 重复上一帧，每 10 秒一个关键帧。
- 每次开始录屏系统都要用户确认，所以车机断开重连时录屏不停；重连后重发缓存的 `VIDEO_CONFIG` 并请求关键帧。
- 系统 GCM 在没有输入数据时不把 AAD 算进 tag，空负载帧（`PING`、`REQUEST_KEYFRAME`）的 tag 由鸿蒙端自己算（GHASH），
  测试向量 `frames[1]` 就是这种情况。
- 同样不能反向控制，`HELLO_ACK` 回 `displayId = -1`，`TOUCH` / `KEY` / `LAUNCH` 一律忽略。
