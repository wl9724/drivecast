import XCTest
@testable import DriveCastCore

final class ProtocolTests: XCTestCase {
    func testFrameCodec() throws {
        XCTAssertEqual(encodeFrame(Msg.videoFrame, Data([1, 2])), Data([0x11, 0, 0, 0, 2, 1, 2]))
        XCTAssertEqual(magic, Data([0x44, 0x43, 0x76, 0x31]))
        let (type, len) = try parseHeader(Data([0x40, 0, 0, 1, 0]))
        XCTAssertEqual(type, Msg.ping)
        XCTAssertEqual(len, 256)
        XCTAssertThrowsError(try parseHeader(Data([0x01, 0, 0x10, 0, 1]))) // 超过 maxIncoming
        XCTAssertThrowsError(try parseHeader(Data([0x01, 0, 0])))
    }

    func testPayloads() throws {
        // version 1 · 1280 · 720 · dpi 160 · 30fps · 4 Mbps
        let hello = try Hello(Data([1, 0x05, 0x00, 0x02, 0xD0, 0, 160, 30, 0, 0x3D, 0x09, 0x00]))
        XCTAssertEqual([hello.width, hello.height, hello.dpi, hello.fps, hello.bitrate], [1280, 720, 160, 30, 4_000_000])
        XCTAssertThrowsError(try Hello(Data([2, 0x05, 0x00, 0x02, 0xD0, 0, 160, 30, 0, 0x3D, 0x09, 0x00])))
        XCTAssertThrowsError(try Hello(Data([1, 0x05])))
        XCTAssertEqual(helloAck(), Data([1, 0xFF, 0xFF, 0xFF, 0xFF]))
        XCTAssertEqual(videoConfig(width: 1280, height: 720, csd: Data([9])), Data([0x05, 0x00, 0x02, 0xD0, 9]))
        XCTAssertEqual(videoFrame(ptsUs: 258, keyframe: true, data: Data([7])), Data([0, 0, 0, 0, 0, 0, 1, 2, 1, 7]))
    }

    func testAnnexB() {
        XCTAssertEqual(annexB([0, 0, 0, 2, 0x65, 0xAA, 0, 0, 0, 1, 0x06]), Data([0, 0, 0, 1, 0x65, 0xAA, 0, 0, 0, 1, 0x06]))
        XCTAssertEqual(annexB([0, 2, 0x41, 0xBB], lengthSize: 2), Data([0, 0, 0, 1, 0x41, 0xBB]))
        XCTAssertEqual(annexB([0, 0, 0, 9, 0x65]), Data()) // 截断的 NAL 丢掉
    }
}
