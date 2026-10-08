# DriveCast iPhone 端

ReplayKit 广播扩展把 iPhone 屏幕编码成 H.264，经过配对认证、逐帧加密的 TCP 连接发给车机（协议见 [docs/protocol.md](../docs/protocol.md)）。只能显示，车机上不能操作 iPhone。

| 目录 | 内容 |
|---|---|
| `Core/` | Swift 包 DriveCastCore：帧编解码、配对/认证、加密帧（`swift test --package-path ios/Core`） |
| `App/` | 主 App：找车机、配对、开始投屏按钮 |
| `Broadcast/` | 广播上传扩展：连车机、缩放旋转、H.264 编码 |
| `Shared/` | App 和扩展共用：TCP 连接、钥匙串和设置 |

## 构建

需要 macOS + Xcode 26 和 XcodeGen：`xcodegen generate --spec ios/project.yml`，再打开 `ios/DriveCast.xcodeproj`。
Actions 里的 `DriveCast-unsigned-ipa` 是未签名的包，要自己重签名后才能装。

## 签名注意

App 和扩展靠 App Group `group.io.github.drivecast` 共享配对信息（钥匙串访问组和 UserDefaults），两个 target 都要带上它。
用自己的开发者账号签名时 App Group 名字通常要改：把 `project.yml` 里的 group 和 `Shared/Store.swift` 里的 `Store.group` 改成同一个。
有的侧载工具会自动给 App Group 改名，这时 App 首页会提示"App Group 不可用"，投屏扩展读不到配对信息。
