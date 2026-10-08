import Foundation

/// DriveCast 协议 v1，规范见 docs/protocol.md。多字节整数一律大端序。
/// 消息类型与 protocol/src/main/kotlin/org/drivecast/protocol/Protocol.kt 一致。
public enum Msg {
    public static let hello: UInt8 = 0x01
    public static let helloAck: UInt8 = 0x02
    public static let videoConfig: UInt8 = 0x10
    public static let videoFrame: UInt8 = 0x11
    public static let requestKeyframe: UInt8 = 0x12
    public static let touch: UInt8 = 0x20
    public static let key: UInt8 = 0x21
    public static let launch: UInt8 = 0x31
    public static let ping: UInt8 = 0x40
    public static let authChallenge: UInt8 = 0x50
    public static let authResponse: UInt8 = 0x51
    public static let pairStart: UInt8 = 0x52
    public static let pairKey: UInt8 = 0x53
    public static let pairCommit: UInt8 = 0x54
    public static let pairReveal: UInt8 = 0x55
    public static let notice: UInt8 = 0x7E
    public static let bye: UInt8 = 0x7F
}

public let protocolVersion = 1

/// iPhone 连上车机后先发这 4 字节（明文）。
public let magic = Data("DCv1".utf8)

/// 车机 TCP 端口（被占用时车机改用随机端口，Bonjour 里能查到）。
public let carPort: UInt16 = 27420
public let serviceType = "_drivecast._tcp"

/// 手机只收车机的控制消息，都很小。限制长度，免得陌生人一个帧头就撑爆扩展的内存。
public let maxIncoming = 1 << 16

/// 车机每秒发 PING；手机这么久收不到任何消息就当连接断了。
public let phoneWatchdog: TimeInterval = 5

public enum DCError: LocalizedError, Equatable {
    /// 对方发来 BYE。握手阶段是明文，内容未经认证
    case bye(String)
    /// 车机的 carId 不在钥匙串里：没和这台车机配对过
    case notPaired
    case bad(String)

    public var errorDescription: String? {
        switch self {
        case .bye(let reason): return "车机断开：\(reason)"
        case .notPaired: return "没有和这台车机配对过"
        case .bad(let message): return message
        }
    }
}

public struct Frame: Equatable {
    public let type: UInt8
    public let payload: Data

    public init(_ type: UInt8, _ payload: Data = Data()) {
        self.type = type
        self.payload = Data(payload) // 切片的下标不从 0 开始，统一拷成新的
    }

    public var text: String { String(decoding: payload, as: UTF8.self) }
}

/// type u8 · len u32 BE
func header(_ type: UInt8, _ len: Int) -> Data {
    var d = Data([type])
    d.appendBE(UInt32(len))
    return d
}

/// 整帧编成一块：一次 send 一帧，帧之间不会交错。
public func encodeFrame(_ type: UInt8, _ payload: Data = Data()) -> Data { header(type, payload.count) + payload }

/// 解析 5 字节帧头，返回类型和负载长度。
public func parseHeader(_ h: Data) throws -> (type: UInt8, len: Int) {
    var r = Reader(h)
    let type = try UInt8(r.uint(1)), len = try r.uint(4)
    guard len <= maxIncoming else { throw DCError.bad("帧长度异常：\(len)") }
    return (type, len)
}

/// 车 → 手：车机画面区域和期望的编码参数。
public struct Hello: Equatable {
    public let width: Int, height: Int, dpi: Int, fps: Int, bitrate: Int

    public init(_ p: Data) throws {
        var r = Reader(p)
        let v = try r.uint(1)
        guard v == protocolVersion else { throw DCError.bad("协议版本不一致：车机 \(v)，手机 \(protocolVersion)") }
        width = try r.uint(2)
        height = try r.uint(2)
        dpi = try r.uint(2)
        fps = try r.uint(1)
        bitrate = try r.uint(4)
    }
}

/// 手 → 车：displayId = -1 表示不能反向控制（iPhone）。
public func helloAck(displayId: Int32 = -1) -> Data {
    var d = Data([UInt8(protocolVersion)])
    d.appendBE(displayId)
    return d
}

/// 手 → 车：H.264 SPS/PPS（Annex B），车机据此（重新）创建解码器。
public func videoConfig(width: Int, height: Int, csd: Data) -> Data {
    var d = Data()
    d.appendBE(UInt16(width))
    d.appendBE(UInt16(height))
    return d + csd
}

/// 手 → 车：一帧 H.264（Annex B）。
public func videoFrame(ptsUs: UInt64, keyframe: Bool, data: Data) -> Data {
    var d = Data()
    d.appendBE(ptsUs)
    d.append(keyframe ? 1 : 0)
    return d + data
}

/// VideoToolbox 输出的是 AVCC（每个 NAL 前是大端长度），车机要的是 Annex B（每个 NAL 前是 00 00 00 01）。
public func annexB(_ avcc: [UInt8], lengthSize n: Int = 4) -> Data {
    var out = Data(capacity: avcc.count + 16), i = 0
    while i + n <= avcc.count {
        let len = avcc[i..<i + n].reduce(0) { $0 << 8 | Int($1) }
        i += n
        guard i + len <= avcc.count else { break }
        out.append(contentsOf: [0, 0, 0, 1])
        out.append(contentsOf: avcc[i..<i + len])
        i += len
    }
    return out
}

public extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

extension Data {
    mutating func appendBE<T: FixedWidthInteger>(_ v: T) { Swift.withUnsafeBytes(of: v.bigEndian) { append(contentsOf: $0) } }
}

/// 按顺序读大端字段，越界就抛错。
struct Reader {
    private let b: [UInt8]
    private var i = 0

    init(_ d: Data) { b = [UInt8](d) }

    mutating func take(_ n: Int) throws -> [UInt8] {
        guard n >= 0, i + n <= b.count else { throw DCError.bad("消息太短") }
        defer { i += n }
        return Array(b[i..<i + n])
    }

    mutating func uint(_ n: Int) throws -> Int { try take(n).reduce(0) { $0 << 8 | Int($1) } }
}
