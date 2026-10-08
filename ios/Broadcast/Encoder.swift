import CoreMedia
import DriveCastCore
import Foundation
import ImageIO
import QuartzCore
import VideoToolbox

/// H.264 编码：把 ReplayKit 的整屏画面等比缩放（两侧补黑）、按方向旋转成车机 HELLO 的宽高，
/// 竖屏 iPhone 在横屏车机上居中显示，车机不用做任何适配。
/// 扩展内存上限约 50MB：只用编码器缓冲池里车机大小的缓冲，不留整屏副本、不用 CIContext。所有方法都在 q 上调用。
final class Encoder {
    typealias Sink = (_ type: UInt8, _ payload: Data, _ done: @escaping () -> Void) -> Void

    let hello: Hello
    /// 最近一帧（自己的缓冲，不是 ReplayKit 的）。画面静止时 ReplayKit 可能不出帧，每 100ms 重新编码它。
    private(set) var last: CVPixelBuffer?
    private let q: DispatchQueue
    private var session: VTCompressionSession
    private var lowLatency: Bool
    private let scaler: VTPixelTransferSession
    private let rotator: VTPixelRotationSession
    private var rotated = CGImagePropertyOrientation.up
    private var tmp: CVPixelBuffer?
    private var timer: DispatchSourceTimer?
    private var lastAt = 0.0
    private var sink: Sink?
    private var gen = 0         // 每次换连接加一，旧连接的编码输出和发送回调都作废
    private var pending = 0     // 已提交编码、还没发完的帧
    private var keyNext = true
    private var keyAt = 0.0     // 上次强制关键帧的时间
    private var config: Data?   // 上次发给车机的 SPS/PPS

    init?(_ hello: Hello, queue: DispatchQueue, carry: CVPixelBuffer?) {
        var t: VTPixelTransferSession?, r: VTPixelRotationSession?
        guard hello.width > 0, hello.height > 0, let (s, ll) = Self.open(hello, lowLatency: true),
              VTPixelTransferSessionCreate(allocator: nil, pixelTransferSessionOut: &t) == noErr, let t,
              VTPixelRotationSessionCreate(nil, &r) == noErr, let r
        else { return nil }
        self.hello = hello
        q = queue
        session = s
        lowLatency = ll
        scaler = t
        rotator = r
        VTSessionSetProperty(t, key: kVTPixelTransferPropertyKey_ScalingMode, value: kVTScalingMode_Letterbox)
        VTSessionSetProperty(t, key: kVTPixelTransferPropertyKey_RealTime, value: kCFBooleanTrue)
        if let carry { last = convert(carry, .up) } // 车机换了分辨率：上一帧缩放过来，静止画面也有东西可发

        let tm = DispatchSource.makeTimerSource(queue: q)
        tm.schedule(deadline: .now() + 0.05, repeating: 0.05)
        tm.setEventHandler { [weak self] in
            guard let self, CACurrentMediaTime() - lastAt >= 0.1 else { return }
            encode() // 协议要求至少每 100ms 一帧
        }
        tm.resume()
        timer = tm
    }

    /// 新连接：强制关键帧并重发 VIDEO_CONFIG，马上发一帧。
    func start(_ s: @escaping Sink) {
        reset()
        sink = s
        encode()
    }

    func pause() {
        reset()
        sink = nil
    }

    func forceKeyframe() { keyNext = true }

    func stop() {
        timer?.cancel()
        sink = nil
        last = nil
        tmp = nil
        VTCompressionSessionInvalidate(session)
        VTPixelTransferSessionInvalidate(scaler)
        VTPixelRotationSessionInvalidate(rotator)
    }

    /// ReplayKit 的缓冲只在 processSampleBuffer 返回前有效，所以同步缩放进自己的缓冲。
    func push(_ src: CVPixelBuffer, _ o: CGImagePropertyOrientation) {
        guard let out = convert(src, o) else { return }
        last = out
        encode()
    }

    private func convert(_ src: CVPixelBuffer, _ o: CGImagePropertyOrientation) -> CVPixelBuffer? {
        guard let pool = VTCompressionSessionGetPixelBufferPool(session) else { return nil }
        var out: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &out) == kCVReturnSuccess, let out else { return nil }
        // ReplayKit 的缓冲一直是竖的，方向在 RPVideoSampleOrientationKey 里。按 WebRTC / LiveKit 的 ReplayKit 实现，
        // .left 顺时针转 90°、.right 逆时针转 90°（和 ImageIO 的 EXIF 定义相反）。
        // ponytail: 还没在真机上核对，左右各横屏一个 App 试一下，反了就对调 CW90 / CCW90
        let rotation: CFString? = switch o {
        case .left: kVTRotation_CW90
        case .right: kVTRotation_CCW90
        case .down: kVTRotation_180
        default: nil
        }
        guard let rotation else { return VTPixelTransferSessionTransferImage(scaler, from: src, to: out) == noErr ? out : nil }
        // 旋转不能缩放：先缩放进宽高对调的临时缓冲，再转进编码器的缓冲
        let (w, h) = o == .down ? (hello.width, hello.height) : (hello.height, hello.width)
        if tmp.map({ CVPixelBufferGetWidth($0) != w || CVPixelBufferGetHeight($0) != h }) ?? true {
            tmp = nil
            CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                                [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary, &tmp)
        }
        if o != rotated {
            VTSessionSetProperty(rotator, key: kVTPixelRotationPropertyKey_Rotation, value: rotation)
            rotated = o
        }
        guard let tmp, VTPixelTransferSessionTransferImage(scaler, from: src, to: tmp) == noErr,
              VTPixelRotationSessionRotateImage(rotator, tmp, out) == noErr
        else { return nil }
        return out
    }

    /// 不超过车机要的帧率；发送中的帧到 2 个就跳过（不排队，内存有限）。跳过的 last 留着，定时器稍后会补上。
    private func encode() {
        guard let buf = last, sink != nil, pending < 2,
              CACurrentMediaTime() - lastAt >= 0.9 / Double(max(hello.fps, 1)) else { return }
        pending += 1
        lastAt = CACurrentMediaTime()
        // 低延迟模式是无限 GOP，车机丢一帧就花屏到下个关键帧：和 Android 端一样每 10 秒一个
        if lastAt - keyAt >= 10 { keyNext = true }
        if keyNext { keyAt = lastAt }
        let props = keyNext ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary : nil
        keyNext = false
        let g = gen
        let err = VTCompressionSessionEncodeFrame(
            session, imageBuffer: buf, presentationTimeStamp: CMTime(seconds: lastAt, preferredTimescale: 1_000_000),
            duration: .invalid, frameProperties: props, infoFlagsOut: nil
        ) { [weak self] status, _, sb in
            // 可能在别的线程、也可能在 EncodeFrame 里同步回调：只能 async 回到 q
            self?.q.async { self?.emit(g, status == noErr ? sb : nil) }
        }
        if err != noErr { rebuild() }
    }

    private func emit(_ g: Int, _ sb: CMSampleBuffer?) {
        guard g == gen else { return }
        guard let sb, let sink, let fd = CMSampleBufferGetFormatDescription(sb), let bb = CMSampleBufferGetDataBuffer(sb) else {
            pending -= 1 // 编码器丢了这一帧
            return
        }
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[String: Any]]
        let key = (attachments?.first?[kCMSampleAttachmentKey_NotSync as String] as? Bool) != true
        var count = 0, nalLength: Int32 = 4
        CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: 0, parameterSetPointerOut: nil,
            parameterSetSizeOut: nil, parameterSetCountOut: &count, nalUnitHeaderLengthOut: &nalLength)
        if key {
            var csd = Data()
            for i in 0..<count {
                var p: UnsafePointer<UInt8>?, n = 0
                CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: i, parameterSetPointerOut: &p,
                    parameterSetSizeOut: &n, parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                if let p {
                    csd.append(contentsOf: [0, 0, 0, 1])
                    csd.append(p, count: n)
                }
            }
            // 头文件说低延迟码控只支持 High：SPS 的 profile_idc 不是 66（Baseline）就换普通模式重建
            if lowLatency, csd.count > 5, csd[5] != 66 {
                lowLatency = false
                return rebuild()
            }
            if csd != config {
                config = csd
                sink(Msg.videoConfig, videoConfig(width: hello.width, height: hello.height, csd: csd)) {}
            }
        }
        let n = CMBlockBufferGetDataLength(bb)
        var avcc = [UInt8](repeating: 0, count: n)
        guard CMBlockBufferCopyDataBytes(bb, atOffset: 0, dataLength: n, destination: &avcc) == kCMBlockBufferNoErr else {
            pending -= 1
            return
        }
        let pts = UInt64(max(0, CMSampleBufferGetPresentationTimeStamp(sb).seconds) * 1_000_000)
        sink(Msg.videoFrame, videoFrame(ptsUs: pts, keyframe: key, data: annexB(avcc, lengthSize: Int(nalLength)))) { [weak self] in
            if self?.gen == g { self?.pending -= 1 }
        }
    }

    /// 编码出错（如 App 切后台后会话失效）或要换模式：重建会话，从关键帧和 VIDEO_CONFIG 重新开始。
    private func rebuild() {
        VTCompressionSessionInvalidate(session)
        if let (s, ll) = Self.open(hello, lowLatency: lowLatency) {
            session = s
            lowLatency = ll
        }
        reset()
    }

    private func reset() {
        gen += 1
        pending = 0
        keyNext = true
        config = nil
    }

    /// 先试低延迟码控 + Constrained Baseline（老车机只保证 Baseline），不行就普通模式 + Baseline。
    private static func open(_ h: Hello, lowLatency: Bool) -> (VTCompressionSession, Bool)? {
        if lowLatency, let s = makeSession(h, lowLatency: true) { return (s, true) }
        return makeSession(h, lowLatency: false).map { ($0, false) }
    }

    private static func makeSession(_ h: Hello, lowLatency: Bool) -> VTCompressionSession? {
        let spec = lowLatency ? [kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true] as CFDictionary : nil
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferWidthKey: h.width, kCVPixelBufferHeightKey: h.height,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
        ]
        var s: VTCompressionSession?
        guard VTCompressionSessionCreate(
            allocator: nil, width: Int32(h.width), height: Int32(h.height), codecType: kCMVideoCodecType_H264,
            encoderSpecification: spec, imageBufferAttributes: attrs as CFDictionary, compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil, compressionSessionOut: &s
        ) == noErr, let s else { return nil }
        let profile = lowLatency ? kVTProfileLevel_H264_ConstrainedBaseline_AutoLevel : kVTProfileLevel_H264_Baseline_AutoLevel
        guard VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ProfileLevel, value: profile) == noErr else {
            VTCompressionSessionInvalidate(s)
            return nil
        }
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: NSNumber(value: h.fps))
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AverageBitRate, value: NSNumber(value: h.bitrate))
        // AverageBitRate 只是目标，DataRateLimits 是硬上限：每秒最多 1.5 倍码率
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_DataRateLimits, value: [Double(h.bitrate) / 8 * 1.5, 1] as CFArray)
        VTCompressionSessionPrepareToEncodeFrames(s)
        return s
    }
}
