// swift-tools-version:5.9
import PackageDescription

// 纯逻辑：帧编解码、配对/认证密码学、加密帧。App 和广播扩展共用，`swift test` 可在 macOS 上直接跑
let package = Package(
    name: "DriveCastCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "DriveCastCore", targets: ["DriveCastCore"])],
    targets: [
        .target(name: "DriveCastCore"),
        .testTarget(name: "DriveCastCoreTests", dependencies: ["DriveCastCore"]),
    ]
)
