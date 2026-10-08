import CoreMedia
import DriveCastCore
import Foundation
import ImageIO
import Network
import ReplayKit

/// 广播上传扩展：找到已配对的车机，认证后用加密连接把屏幕的 H.264 发过去，断了就退避重连。
/// iPhone 不能被反向控制，车机发来的 TOUCH / KEY / LAUNCH 一律忽略。状态都在 q 上。
class SampleHandler: RPBroadcastSampleHandler {
    private let q = DispatchQueue(label: "drivecast")
    private let browser = NWBrowser(for: .bonjourWithTXTRecord(type: serviceType, domain: nil), using: .tcp) // 不带 TXT 的话 metadata 一直是 .none
    private let wifi = NWPathMonitor(requiredInterfaceType: .wifi)
    private var phoneId = Data()
    private var link: Link?
    private var channel: SecureChannel? // 认证之后才有
    private var streaming = false       // 这条连接收到 HELLO 之后
    private var encoder: Encoder?
    private var early: (CVPixelBuffer, CGImagePropertyOrientation)? // 第一次 HELLO 之前的最新一帧（只持有一个 ReplayKit 缓冲），画面静止时也有东西可发
    private var lastHeard = Date()
    private var backoff: TimeInterval = 1
    private var rejected = 0 // 连续几次在认证阶段收到明文 BYE（内容未认证，可能是冒充车机的人发的）
    private var timer: DispatchSourceTimer?
    private var stopped = false

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        guard Store.groupOK else {
            return finishBroadcastWithError(Self.error("App Group 不可用（安装时签名工具改了名或去掉了），读不到配对信息。请打开 DriveCast App 查看说明"))
        }
        guard let id = Store.phoneId(create: false), !Store.cars.isEmpty else {
            return finishBroadcastWithError(Self.error("还没有和车机配对。请先在车机上点「添加 iPhone」，再在 DriveCast App 里配对"))
        }
        q.sync {
            phoneId = id
            browser.stateUpdateHandler = { [weak self] state in
                switch state {
                case .waiting(.dns(-65570)), .failed(.dns(-65570)): // kDNSServiceErr_PolicyDenied
                    self?.finish(LinkError.localNetworkDenied.localizedDescription)
                default: break
                }
            }
            browser.start(queue: q)
            wifi.start(queue: q)
            let t = DispatchSource.makeTimerSource(queue: q)
            t.schedule(deadline: .now() + 1, repeating: 1)
            t.setEventHandler { [weak self] in self?.watchdog() }
            t.resume()
            timer = t
            q.asyncAfter(deadline: .now() + 1) { [weak self] in self?.connect() } // 先等一会儿 Bonjour 结果
        }
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let px = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let o = (CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber)
            .flatMap { CGImagePropertyOrientation(rawValue: $0.uint32Value) } ?? .up
        q.sync { if let e = encoder { e.push(px, o) } else { early = (px, o) } }
    }

    override func broadcastFinished() {
        q.sync { stop() }
    }

    private static func error(_ reason: String) -> NSError {
        NSError(domain: "DriveCast", code: 1, userInfo: [NSLocalizedFailureReasonErrorKey: reason])
    }

    private func finish(_ reason: String) {
        guard !stopped else { return }
        stop()
        // 不在 q 上调：ReplayKit 接着可能调 broadcastFinished，那里要 q.sync
        DispatchQueue.global().async { self.finishBroadcastWithError(Self.error(reason)) }
    }

    private func stop() {
        stopped = true
        timer?.cancel()
        browser.cancel()
        wifi.cancel()
        if streaming, let ch = channel { link?.send(ch.seal(Msg.bye, Data("iPhone 停止了投屏".utf8))) }
        link?.close()
        link = nil
        encoder?.stop()
        encoder = nil
        early = nil
    }

    /// 找车的顺序：Bonjour（TXT id 是已配对的车机）→ 上次连上的地址 → Wi-Fi 网关（连车机热点时就是车机）→ 手动地址。
    private func connect() {
        guard !stopped, link == nil else { return }
        let paired = Set(Store.cars.keys)
        var list: [NWEndpoint] = browser.browseResults.compactMap {
            guard case .bonjour(let txt) = $0.metadata, let id = txt["id"], paired.contains(id.lowercased()) else { return nil }
            return $0.endpoint
        }
        list += Store.addresses.compactMap(Store.endpoint)
        list += wifi.currentPath.gateways.compactMap {
            guard case .hostPort(let host, _) = $0, let port = NWEndpoint.Port(rawValue: carPort) else { return nil }
            return .hostPort(host: host, port: port)
        }
        if let manual = Store.endpoint(Store.manual) { list.append(manual) }
        var seen = Set<NWEndpoint>()
        attempt(list.filter { seen.insert($0).inserted }[...])
    }

    private func attempt(_ list: ArraySlice<NWEndpoint>) {
        guard !stopped else { return }
        guard let endpoint = list.first else { return retryLater() }
        let l = Link(endpoint)
        link = l
        channel = nil
        streaming = false
        lastHeard = Date()
        l.onError = { [weak self] e in self?.dropped(l, e, rest: list.dropFirst()) }
        l.onReady = { [weak self] in
            l.send(magic)
            l.receive { self?.challenged(l, $0) }
        }
        l.start(q)
    }

    private func challenged(_ l: Link, _ f: Frame) {
        guard l === link else { return }
        do {
            let (_, reply, ch) = try authenticate(challenge: f, phoneId: phoneId, ltk: Store.ltk)
            l.send(reply)
            channel = ch
            lastHeard = Date()
            l.receive { [weak self] in self?.received(l, $0) }
        } catch {
            l.fail(error) // 没配对过这台车机等：试下一个地址
        }
    }

    private func dropped(_ l: Link, _ e: Error, rest: ArraySlice<NWEndpoint>) {
        guard l === link else { return }
        link = nil
        encoder?.pause()
        if (e as? LinkError) == .localNetworkDenied { return finish(LinkError.localNetworkDenied.localizedDescription) }
        if channel == nil { attempt(rest) } else { retryLater() } // 认证前失败换下一个地址；之后断开就退避重连
    }

    private func retryLater() {
        q.asyncAfter(deadline: .now() + backoff) { [weak self] in self?.connect() }
        backoff = min(backoff * 2, 8)
    }

    /// 车机每秒发 PING。Wi-Fi 断开时 TCP 自己发现不了，5 秒没消息就当断了（连接和握手也算在内）。
    private func watchdog() {
        if let l = link, Date().timeIntervalSince(lastHeard) > phoneWatchdog { l.fail(LinkError.timeout) }
    }

    private func received(_ l: Link, _ raw: Frame) {
        guard l === link, let ch = channel else { return }
        let f: Frame
        do {
            f = try ch.open(raw)
        } catch {
            // 认证没通过时车机发的是明文 BYE。它没有认证，同一热点上谁都能冒充车机发，
            // 所以只当普通失败处理；连续多次才停止广播并提示重新配对
            if raw.type == Msg.bye && !streaming {
                rejected += 1
                if rejected >= 3 { return finish("车机多次拒绝了这台 iPhone，可能在车机上清除过配对。请在 DriveCast App 里重新配对") }
            }
            return l.fail(error) // 解密失败立刻断开
        }
        lastHeard = Date()
        switch f.type {
        case Msg.hello:
            do { try hello(l, ch, Hello(f.payload)) } catch { return l.fail(error) }
        case Msg.requestKeyframe:
            encoder?.forceKeyframe()
        case Msg.bye:
            return l.fail(DCError.bye(f.text))
        default:
            break // PING 只说明车机还在；TOUCH / KEY / LAUNCH 忽略
        }
        if l === link { l.receive { [weak self] in self?.received(l, $0) } }
    }

    /// 车机的 HELLO 能解开，说明车机也持有 LTK。按 HELLO 的尺寸和码率（重新）建编码器。
    private func hello(_ l: Link, _ ch: SecureChannel, _ h: Hello) throws {
        if encoder?.hello != h {
            guard let e = Encoder(h, queue: q, carry: encoder?.last) else { return finish("无法创建 H.264 编码器") }
            encoder?.stop()
            encoder = e
            if let f = early { e.push(f.0, f.1) }
            early = nil
        }
        l.send(ch.seal(Msg.helloAck, helloAck())) // displayId = -1：不能反向控制
        streaming = true
        backoff = 1
        rejected = 0
        if let ep = l.conn.currentPath?.remoteEndpoint { Store.remember(ep) }
        encoder?.start { [weak l] type, payload, done in
            l?.send(ch.seal(type, payload), done: done)
        }
    }
}
