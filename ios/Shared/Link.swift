import DriveCastCore
import Foundation
import Network

enum LinkError: LocalizedError {
    case localNetworkDenied, closed, timeout

    var errorDescription: String? {
        switch self {
        case .localNetworkDenied: return "DriveCast 没有本地网络权限：请在 设置 → 隐私与安全性 → 本地网络 中打开 DriveCast"
        case .closed: return "车机断开了连接"
        case .timeout: return "车机没有响应"
        }
    }
}

/// 到车机的一条 TCP 连接，按帧收发。回调都在 start 给的队列上；出错只回调一次，回调前连接已关闭。
final class Link {
    let conn: NWConnection
    var onReady: () -> Void = {}
    var onError: (Error) -> Void = { _ in }
    private var closed = false

    init(_ endpoint: NWEndpoint) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 5
        conn = NWConnection(to: endpoint, using: NWParameters(tls: nil, tcp: tcp))
    }

    deinit { conn.cancel() }

    func start(_ queue: DispatchQueue) {
        conn.stateUpdateHandler = { [weak self] state in
            guard let self, !closed else { return }
            switch state {
            case .ready: onReady()
            case .waiting(let e):
                // waiting 时 NWConnection 会自己一直重试；这里直接算失败，调用方好换下一个地址
                fail(conn.currentPath?.unsatisfiedReason == .localNetworkDenied ? LinkError.localNetworkDenied : e)
            case .failed(let e): fail(e)
            default: break
            }
        }
        conn.start(queue: queue)
    }

    func send(_ data: Data, done: (() -> Void)? = nil) {
        conn.send(content: data, completion: .contentProcessed { [weak self] e in
            done?()
            if let e { self?.fail(e) }
        })
    }

    /// 收一帧：5 字节帧头，再按长度收负载。
    func receive(_ handler: @escaping (Frame) -> Void) {
        conn.receive(minimumIncompleteLength: 5, maximumLength: 5) { [weak self] head, _, _, e in
            guard let self, !closed else { return }
            guard let head, head.count == 5 else { return fail(e ?? LinkError.closed) }
            do {
                let (type, len) = try parseHeader(head)
                if len == 0 { return handler(Frame(type)) }
                conn.receive(minimumIncompleteLength: len, maximumLength: len) { [weak self] body, _, _, e in
                    guard let self, !closed else { return }
                    guard let body, body.count == len else { return fail(e ?? LinkError.closed) }
                    handler(Frame(type, body))
                }
            } catch {
                fail(error)
            }
        }
    }

    func fail(_ e: Error) {
        guard !closed else { return }
        let report = onError
        close()
        report(e)
    }

    /// 主动关闭，不回调 onError。
    func close() {
        closed = true
        onReady = {}
        onError = { _ in } // 回调里常引用这条连接自己，清掉免得循环引用
        conn.cancel()
    }
}
