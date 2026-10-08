import CryptoKit
import XCTest
@testable import DriveCastCore

/// 对照 docs/testvectors/ios-pairing.json（tools/pairing_ref.py 生成，车机端 Kotlin 测试用同一份）逐字节核对。
final class PairingTests: XCTestCase {
    struct Vectors: Decodable {
        struct Round: Decodable { let i: Int, bit: Int, nP: String, nC: String, commitP: String, commitC: String }
        struct Sealed: Decodable { let dir: String, type: UInt8, payload: String, sealed: String }
        let dP: String, dC: String, pkP: String, pkC: String, carId: String, phoneId: String, code: Int
        let rounds: [Round]
        let z: String, ltk: String, nonceC: String, nonceP: String, tag: String, kP2C: String, kC2P: String
        let frames: [Sealed]
    }

    static let v: Vectors = {
        let url = URL(fileURLWithPath: #filePath) // ios/Core/Tests/DriveCastCoreTests/
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("../../docs/testvectors/ios-pairing.json")
        return try! JSONDecoder().decode(Vectors.self, from: Data(contentsOf: url))
    }()

    var v: Vectors { Self.v }
    var carId: Data { h(v.carId) }
    var phoneId: Data { h(v.phoneId) }
    var pkP: Data { h(v.pkP) }
    var pkC: Data { h(v.pkC) }
    var ltk: Data { h(v.ltk) }

    func testKeysRoundsAndDerivation() throws {
        let skP = try P256.KeyAgreement.PrivateKey(rawRepresentation: h(v.dP))
        let skC = try P256.KeyAgreement.PrivateKey(rawRepresentation: h(v.dC))
        XCTAssertEqual(skP.publicKey.x963Representation, pkP)
        XCTAssertEqual(skC.publicKey.x963Representation, pkC)
        XCTAssertEqual(v.rounds.count, 20)
        for r in v.rounds {
            XCTAssertEqual(r.bit, v.code >> r.i & 1)
            XCTAssertEqual(commitment("DCv1 P", own: pkP, peer: pkC, nonce: h(r.nP), bit: r.bit), h(r.commitP))
            XCTAssertEqual(commitment("DCv1 C", own: pkC, peer: pkP, nonce: h(r.nC), bit: r.bit), h(r.commitC))
        }
        XCTAssertEqual(try sharedX(skP, pkC), h(v.z))
        XCTAssertEqual(try sharedX(skC, pkP), h(v.z))
        XCTAssertEqual(longTermKey(z: h(v.z), carId: carId, phoneId: phoneId, pkP: pkP, pkC: pkC), ltk)
        XCTAssertEqual(authTag(ltk: ltk, carId: carId, phoneId: phoneId, nonceC: h(v.nonceC), nonceP: h(v.nonceP)), h(v.tag))
        let k = sessionKeys(ltk: ltk, carId: carId, phoneId: phoneId, nonceC: h(v.nonceC), nonceP: h(v.nonceP))
        XCTAssertEqual(k.p2c, h(v.kP2C))
        XCTAssertEqual(k.c2p, h(v.kC2P))
    }

    /// RFC 5903 §8.1：CryptoKit 的原始共享密钥就是 x 坐标，和车机（Android JCA）一致。
    func testRfc5903EcdhIsXCoordinate() throws {
        let i = try P256.KeyAgreement.PrivateKey(rawRepresentation: h("C88F01F510D9AC3F70A292DAA2316DE544E9AAB8AFE84049C62A9C57862D1433"))
        let r = try P256.KeyAgreement.PrivateKey(rawRepresentation: h("C6EF9C5D78AE012A011164ACB397CE2088685D8F06BF9BE0B283AB46476BEE53"))
        let girx = h("D6840F6B42F6EDAFD13116E0E12565202FEF8E9ECE7DCE03812464D04B9442DE")
        XCTAssertEqual(try sharedX(i, r.publicKey.x963Representation), girx)
        XCTAssertEqual(try sharedX(r, i.publicKey.x963Representation), girx)
    }

    func testPairingClientMatchesVectors() throws {
        PairingClient.usedCodes = []
        let c = try client(Array(v.rounds.map { h($0.nP) }) + [h(v.nonceP)])
        XCTAssertEqual(try c.receive(Frame(Msg.authChallenge, carId + h(v.nonceC))),
                       .send(encodeFrame(Msg.pairStart, phoneId + pkP + Data("测试 iPhone".utf8))))
        XCTAssertEqual(try c.receive(Frame(Msg.pairKey, pkC)), .needCode)
        var out = try c.submit(code: v.code)
        for r in v.rounds {
            XCTAssertEqual(out, encodeFrame(Msg.pairCommit, h(r.commitP)), "round \(r.i)")
            XCTAssertEqual(try c.receive(Frame(Msg.pairCommit, h(r.commitC))), .send(encodeFrame(Msg.pairReveal, h(r.nP))))
            guard case .send(let next) = try c.receive(Frame(Msg.pairReveal, h(r.nC))) else { return XCTFail("round \(r.i)") }
            out = next
        }
        XCTAssertEqual(out, encodeFrame(Msg.authResponse, phoneId + h(v.nonceP) + h(v.tag)))
        let car = SecureChannel(tx: h(v.kC2P), rx: h(v.kP2C))
        XCTAssertEqual(try c.receive(frame(car.seal(Msg.notice, Data("paired".utf8)))), .paired(carId: carId, ltk: ltk))
    }

    func testPairingRejectsBadCarReveal() throws {
        PairingClient.usedCodes = []
        let c = try atCode()
        _ = try c.submit(code: v.code)
        _ = try c.receive(Frame(Msg.pairCommit, h(v.rounds[0].commitC)))
        XCTAssertThrowsError(try c.receive(Frame(Msg.pairReveal, h(v.rounds[1].nC))))
    }

    func testPairingRefusesReusedCode() throws {
        PairingClient.usedCodes = []
        _ = try atCode().submit(code: v.code)
        let c = try atCode()
        XCTAssertThrowsError(try c.submit(code: v.code))
        XCTAssertThrowsError(try c.submit(code: 1_000_000))
        XCTAssertNoThrow(try c.submit(code: 654321))
    }

    func testPairingRejectsOffCurveKeyAndBye() throws {
        let c = try client([])
        _ = try c.receive(Frame(Msg.authChallenge, carId + h(v.nonceC)))
        var bad = pkC
        bad[64] ^= 1 // Y 改一位就不在曲线上了
        XCTAssertThrowsError(try c.receive(Frame(Msg.pairKey, bad)))
        XCTAssertThrowsError(try publicKey(Data([2]) + pkC.dropFirst()))

        let d = try client([])
        XCTAssertThrowsError(try d.receive(Frame(Msg.bye, Data("不在配对模式".utf8)))) {
            XCTAssertEqual($0 as? DCError, .bye("不在配对模式"))
        }
    }

    func testAuthenticateAndSealedFrames() throws {
        let challenge = Frame(Msg.authChallenge, carId + h(v.nonceC))
        let (id, reply, ch) = try authenticate(challenge: challenge, phoneId: phoneId,
                                               ltk: { $0 == self.carId ? self.ltk : nil }, nonceP: h(v.nonceP))
        XCTAssertEqual(id, carId)
        XCTAssertEqual(reply, encodeFrame(Msg.authResponse, phoneId + h(v.nonceP) + h(v.tag)))
        for f in v.frames where f.dir == "p2c" {
            XCTAssertEqual(ch.seal(f.type, h(f.payload)), h(f.sealed))
        }
        for f in v.frames where f.dir == "c2p" {
            XCTAssertEqual(try ch.open(frame(h(f.sealed))), Frame(f.type, h(f.payload)))
        }
        XCTAssertThrowsError(try authenticate(challenge: challenge, phoneId: phoneId, ltk: { _ in nil })) {
            XCTAssertEqual($0 as? DCError, .notPaired)
        }
    }

    func testTamperReplayReorderRejected() throws {
        let f0 = frame(h(v.frames[0].sealed)), f1 = frame(h(v.frames[1].sealed))
        func car() -> SecureChannel { SecureChannel(tx: h(v.kC2P), rx: h(v.kP2C)) }
        let a = car()
        XCTAssertEqual(try a.open(f0).payload, Data("frame0".utf8))
        XCTAssertThrowsError(try a.open(f0)) // 重放
        XCTAssertThrowsError(try car().open(f1)) // 乱序 / 丢帧
        var t = f0.payload
        t[t.count - 1] ^= 1
        XCTAssertThrowsError(try car().open(Frame(f0.type, t))) // 改密文
        XCTAssertThrowsError(try car().open(Frame(Msg.videoConfig, f0.payload))) // 改帧头（AAD）
        XCTAssertThrowsError(try car().open(Frame(f0.type, Data(count: 15)))) // 比 tag 还短
    }

    private func client(_ randoms: [Data]) throws -> PairingClient {
        let key = try P256.KeyAgreement.PrivateKey(rawRepresentation: h(v.dP))
        var queue = randoms
        return PairingClient(phoneId: phoneId, name: "测试 iPhone", key: key) { n in
            XCTAssertEqual(n, 16)
            return queue.isEmpty ? randomBytes(n) : queue.removeFirst()
        }
    }

    private func atCode() throws -> PairingClient {
        let c = try client(v.rounds.map { h($0.nP) })
        _ = try c.receive(Frame(Msg.authChallenge, carId + h(v.nonceC)))
        XCTAssertEqual(try c.receive(Frame(Msg.pairKey, pkC)), .needCode)
        return c
    }
}

/// 线上的一整帧拆成 Frame。
func frame(_ wire: Data) -> Frame {
    let (type, len) = try! parseHeader(wire.prefix(5))
    precondition(len == wire.count - 5)
    return Frame(type, wire.dropFirst(5))
}

func h(_ hex: String) -> Data {
    var d = Data(), s = Substring(hex)
    while !s.isEmpty {
        d.append(UInt8(s.prefix(2), radix: 16)!)
        s = s.dropFirst(2)
    }
    return d
}
