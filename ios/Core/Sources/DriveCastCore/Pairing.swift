import CryptoKit
import Foundation

// iPhone ↔ 车机的配对、认证和加密帧，规范见 docs/protocol.md「iPhone：TCP + 配对 + 加密」，
// 参考模型 tools/pairing_ref.py，测试向量 docs/testvectors/ios-pairing.json。

public func randomBytes(_ n: Int) -> Data { SymmetricKey(size: SymmetricKeySize(bitCount: n * 8)).withUnsafeBytes { Data($0) } }

func mac(_ key: Data, _ parts: Data...) -> Data {
    Data(HMAC<SHA256>.authenticationCode(for: parts.reduce(Data(), +), using: SymmetricKey(data: key)))
}

func hkdf(_ ikm: Data, salt: Data, info: Data) -> Data {
    HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: 32)
        .withUnsafeBytes { Data($0) }
}

/// 常数时间比较，不因第一个不同字节的位置泄露时间。
func same(_ a: Data, _ b: Data) -> Bool { a.count == b.count && zip(a, b).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0 }

/// 解析对方的 65 字节公钥（04 || X || Y）。CryptoKit 会拒绝不在曲线上的点（测试里核对过）。
public func publicKey(_ x963: Data) throws -> P256.KeyAgreement.PublicKey {
    guard x963.count == 65, x963.first == 4, let k = try? P256.KeyAgreement.PublicKey(x963Representation: x963) else {
        throw DCError.bad("车机的公钥无效")
    }
    return k
}

/// ECDH 共享密钥 Z：对方公钥点乘后的 x 坐标，32 字节（RFC 5903）。
public func sharedX(_ sk: P256.KeyAgreement.PrivateKey, _ peer: Data) throws -> Data {
    try sk.sharedSecretFromKeyAgreement(with: publicKey(peer)).withUnsafeBytes { Data($0) }
}

/// 第 i 轮的逐位承诺。label 是 "DCv1 P"（手机）或 "DCv1 C"（车机），own 是自己的公钥。
public func commitment(_ label: String, own: Data, peer: Data, nonce: Data, bit: Int) -> Data {
    mac(nonce, Data(label.utf8), own, peer, Data([0x80 | UInt8(bit)]))
}

public func longTermKey(z: Data, carId: Data, phoneId: Data, pkP: Data, pkC: Data) -> Data {
    hkdf(z, salt: Data(count: 32), info: Data("DCv1 LTK".utf8) + carId + phoneId + pkP + pkC)
}

public func authTag(ltk: Data, carId: Data, phoneId: Data, nonceC: Data, nonceP: Data) -> Data {
    mac(ltk, Data("DCv1 AUTH".utf8), carId, phoneId, nonceC, nonceP)
}

/// 每个连接的会话密钥：(手 → 车, 车 → 手)。
public func sessionKeys(ltk: Data, carId: Data, phoneId: Data, nonceC: Data, nonceP: Data) -> (p2c: Data, c2p: Data) {
    let k = hkdf(ltk, salt: nonceC + nonceP, info: Data("DCv1 SESS".utf8) + carId + phoneId)
    return (Data(k.prefix(16)), Data(k.suffix(16)))
}

/// 认证之后双向的加密帧：type u8 · len u32 BE（= 明文长度 + 16）· AES-128-GCM 密文 || tag。
/// nonce = 4 个 0 字节 || 计数器 u64 BE，每个方向各自从 0 开始；AAD = 这一帧的 5 字节头。
/// 计数器不随帧发送（TCP 保证顺序），所以重放、乱序、丢帧都解不开。只在一个队列上用。
public final class SecureChannel {
    private let tx: SymmetricKey, rx: SymmetricKey
    private var txCount: UInt64 = 0, rxCount: UInt64 = 0

    public init(tx: Data, rx: Data) {
        self.tx = SymmetricKey(data: tx)
        self.rx = SymmetricKey(data: rx)
    }

    public func seal(_ type: UInt8, _ payload: Data = Data()) -> Data {
        let h = header(type, payload.count + 16)
        // 只有密钥或 nonce 长度不对才会抛错，这里都是固定长度
        let box = try! AES.GCM.seal(payload, using: tx, nonce: Self.nonce(&txCount), authenticating: h)
        return h + box.ciphertext + box.tag
    }

    /// 解不开就抛错，调用方要立刻断开。
    public func open(_ f: Frame) throws -> Frame {
        let n = Self.nonce(&rxCount)
        guard f.payload.count >= 16,
              let box = try? AES.GCM.SealedBox(nonce: n, ciphertext: f.payload.dropLast(16), tag: f.payload.suffix(16)),
              let plain = try? AES.GCM.open(box, using: rx, authenticating: header(f.type, f.payload.count))
        else { throw DCError.bad("解密失败") }
        return Frame(f.type, plain)
    }

    private static func nonce(_ count: inout UInt64) -> AES.GCM.Nonce {
        var d = Data(count: 4)
        d.appendBE(count)
        count += 1
        return try! AES.GCM.Nonce(data: d)
    }
}

func parseChallenge(_ f: Frame) throws -> (carId: Data, nonceC: Data) {
    if f.type == Msg.bye { throw DCError.bye(f.text) }
    guard f.type == Msg.authChallenge, f.payload.count == 32 else { throw DCError.bad("车机的握手消息不对") }
    return (Data(f.payload.prefix(16)), Data(f.payload.suffix(16)))
}

/// 已配对时（每次投屏）回应车机的 AUTH_CHALLENGE。ltk 按 carId 查，查不到就是没和这台车机配对过。
/// 之后双方都用返回的加密通道；手机能解开车机的 HELLO 才说明车机也持有 LTK。
public func authenticate(
    challenge f: Frame, phoneId: Data, ltk lookup: (Data) -> Data?, nonceP: Data = randomBytes(16)
) throws -> (carId: Data, reply: Data, channel: SecureChannel) {
    let (carId, nonceC) = try parseChallenge(f)
    guard let ltk = lookup(carId) else { throw DCError.notPaired }
    let tag = authTag(ltk: ltk, carId: carId, phoneId: phoneId, nonceC: nonceC, nonceP: nonceP)
    let k = sessionKeys(ltk: ltk, carId: carId, phoneId: phoneId, nonceC: nonceC, nonceP: nonceP)
    return (carId, encodeFrame(Msg.authResponse, phoneId + nonceP + tag), SecureChannel(tx: k.p2c, rx: k.c2p))
}

/// 配对（只在 App 里做）。蓝牙 LE Secure Connections 的 Passkey Entry：ECDH 之后对配对码的 20 位逐位承诺、揭示，
/// 旁听者拿不到 LTK，中间人骗过车机的概率约 2×10⁻⁵。和传输无关：调用方先发 magic，把收到的每一帧交给 receive。
public final class PairingClient {
    public enum Out: Equatable {
        case send(Data)
        /// 车机已显示配对码，等用户输入后调 submit
        case needCode
        case paired(carId: Data, ltk: Data)
    }

    /// 本次运行里用过的配对码。同一个码再用一次，旁听者或中间人就能多拿到几位，规范要求作废。
    public static var usedCodes = Set<Int>()

    private enum State { case challenge, key, code, commit(Int), reveal(Int), confirm(SecureChannel), done }

    private var state = State.challenge
    private let phoneId: Data, name: String, sk: P256.KeyAgreement.PrivateKey, pkP: Data, random: (Int) -> Data
    private var carId = Data(), nonceC = Data(), pkC = Data(), code = 0, nP = Data(), commitC = Data(), ltk = Data()

    /// key、random 只在测试里替换成固定值。
    public init(phoneId: Data, name: String, key: P256.KeyAgreement.PrivateKey = .init(),
                random: @escaping (Int) -> Data = randomBytes) {
        self.phoneId = phoneId
        self.name = name
        sk = key
        pkP = key.publicKey.x963Representation
        self.random = random
    }

    /// 处理车机发来的一帧。抛错时调用方断开连接。
    public func receive(_ f: Frame) throws -> Out {
        if f.type == Msg.bye {
            state = .done
            throw DCError.bye(f.text)
        }
        switch state {
        case .challenge:
            (carId, nonceC) = try parseChallenge(f)
            state = .key
            return .send(encodeFrame(Msg.pairStart, phoneId + pkP + Self.truncated(name)))
        case .key:
            try expect(f, Msg.pairKey, 65)
            _ = try publicKey(f.payload) // 校验在曲线上
            pkC = f.payload
            state = .code
            return .needCode
        case .commit(let i):
            try expect(f, Msg.pairCommit, 32)
            commitC = f.payload
            state = .reveal(i)
            return .send(encodeFrame(Msg.pairReveal, nP))
        case .reveal(let i):
            try expect(f, Msg.pairReveal, 16)
            guard same(commitC, commitment("DCv1 C", own: pkC, peer: pkP, nonce: f.payload, bit: bit(i))) else {
                state = .done
                throw DCError.bad("车机的配对码和输入的不一致，请在车机上重新开始配对")
            }
            if i < 19 { return .send(nextCommit(i + 1)) }
            let z = try sharedX(sk, pkC)
            ltk = longTermKey(z: z, carId: carId, phoneId: phoneId, pkP: pkP, pkC: pkC)
            let nonceP = random(16)
            let k = sessionKeys(ltk: ltk, carId: carId, phoneId: phoneId, nonceC: nonceC, nonceP: nonceP)
            state = .confirm(SecureChannel(tx: k.p2c, rx: k.c2p))
            let tag = authTag(ltk: ltk, carId: carId, phoneId: phoneId, nonceC: nonceC, nonceP: nonceP)
            return .send(encodeFrame(Msg.authResponse, phoneId + nonceP + tag))
        case .confirm(let channel):
            // 车机校验 AUTH_RESPONSE 后发加密的 NOTICE("paired")：能解开才说明车机算出了同一个 LTK
            guard try channel.open(f).type == Msg.notice else { throw DCError.bad("车机没有确认配对") }
            state = .done
            return .paired(carId: carId, ltk: ltk)
        case .code, .done:
            throw DCError.bad("车机发来了意外的消息")
        }
    }

    /// 用户输入车机上显示的 6 位配对码，返回第一轮承诺。
    public func submit(code c: Int) throws -> Data {
        guard case .code = state else { throw DCError.bad("现在不能输入配对码") }
        guard (0..<1_000_000).contains(c) else { throw DCError.bad("配对码是 6 位数字") }
        guard Self.usedCodes.insert(c).inserted else { throw DCError.bad("这个配对码已经用过，请在车机上重新开始配对") }
        code = c
        return nextCommit(0)
    }

    private func nextCommit(_ i: Int) -> Data {
        nP = random(16)
        state = .commit(i)
        return encodeFrame(Msg.pairCommit, commitment("DCv1 P", own: pkP, peer: pkC, nonce: nP, bit: bit(i)))
    }

    private func bit(_ i: Int) -> Int { code >> i & 1 }

    private func expect(_ f: Frame, _ type: UInt8, _ len: Int) throws {
        guard f.type == type, f.payload.count == len else { throw DCError.bad("车机发来了意外的消息") }
    }

    /// 名称最多 64 字节 UTF-8，按字符截断。
    static func truncated(_ s: String) -> Data {
        var d = Data()
        for ch in s {
            let b = Data(String(ch).utf8)
            if d.count + b.count > 64 { break }
            d += b
        }
        return d
    }
}
