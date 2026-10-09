import DriveCastCore
import Foundation
import Network

/// 前台找车机和配对。App 一打开就用 Bonjour 找车机：这也是为了弹出"本地网络"授权，
/// 广播扩展在后台弹不出来，没授权时它的连接会被系统直接拒绝。所有回调都在主队列上。
final class Model: ObservableObject {
    struct Car: Identifiable {
        let id: String // Bonjour 服务名
        let carId: String
        let endpoint: NWEndpoint
    }

    enum Phase: Equatable { case idle, connecting, code, working, done(String), failed(String) }

    @Published var found: [Car] = []
    @Published var localNetworkDenied = false
    @Published var paired = Store.cars
    @Published var phase = Phase.idle
    @Published var codeError = ""

    private let browser = NWBrowser(for: .bonjourWithTXTRecord(type: serviceType, domain: nil), using: .tcp) // 不带 TXT 的话 metadata 一直是 .none
    private var link: Link?
    private var client: PairingClient?
    private var carName = ""

    init() {
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            var seen = Set<String>() // 同一台车机可能从几个网卡上都看得到
            self?.found = results.compactMap { r -> Car? in
                guard case .service(let name, _, _, _) = r.endpoint, seen.insert(name).inserted else { return nil }
                guard case .bonjour(let txt) = r.metadata else { return Car(id: name, carId: "", endpoint: r.endpoint) }
                return Car(id: name, carId: txt["id"]?.lowercased() ?? "", endpoint: r.endpoint)
            }.sorted { $0.id < $1.id }
        }
        browser.stateUpdateHandler = { [weak self] state in
            switch state {
            case .waiting(.dns(-65570)), .failed(.dns(-65570)): self?.localNetworkDenied = true // kDNSServiceErr_PolicyDenied
            case .ready: self?.localNetworkDenied = false
            default: break
            }
        }
        browser.start(queue: .main)
    }

    /// 车机上要先点"添加 iPhone/鸿蒙"（车机只在配对模式下接受配对）。
    func pair(_ endpoint: NWEndpoint, carName: String, deviceName: String) {
        cancel()
        guard let phoneId = Store.phoneId(create: true) else {
            return phase = .failed("钥匙串不可用，无法保存配对信息（App Group 有问题，见上方说明）")
        }
        let c = PairingClient(phoneId: phoneId, name: deviceName.isEmpty ? "iPhone" : deviceName)
        let l = Link(endpoint)
        client = c
        link = l
        self.carName = carName
        phase = .connecting
        codeError = ""
        l.onError = { [weak self] e in self?.phase = .failed(e.localizedDescription) }
        l.onReady = { [weak self] in
            l.send(magic)
            self?.read(l)
        }
        l.start(.main)
    }

    /// 用户输入车机上显示的 6 位配对码。
    func submit(_ text: String) {
        guard let l = link, let c = client else { return }
        guard text.count == 6, let code = Int(text) else { return codeError = "请输入车机上显示的 6 位数字" }
        do {
            let commit = try c.submit(code: code)
            l.send(commit)
            phase = .working
            codeError = ""
        } catch {
            codeError = error.localizedDescription
        }
    }

    func cancel() {
        link?.close()
        link = nil
        client = nil
        phase = .idle
    }

    func forget(_ carIdHex: String) {
        Store.forget(carIdHex)
        paired = Store.cars
    }

    private func read(_ l: Link) {
        l.receive { [weak self] f in
            guard let self, l === link, let c = client else { return }
            do {
                switch try c.receive(f) {
                case .send(let d):
                    l.send(d)
                    read(l)
                case .needCode:
                    phase = .code
                    read(l) // 等输入时车机也可能超时发 BYE
                case .paired(let carId, let ltk):
                    // 记下解析好的地址：扩展里 Bonjour 找不到车机时（如车机连 iPhone 热点）也能直接连
                    if let ep = l.conn.currentPath?.remoteEndpoint { Store.remember(ep) }
                    l.close()
                    guard Store.savePairing(carId: carId, ltk: ltk, name: carName) else {
                        return phase = .failed("保存配对信息失败（钥匙串不可用）")
                    }
                    paired = Store.cars
                    phase = .done("已和「\(carName)」配对。以后点「开始投屏」就会自动连接这台车机。")
                }
            } catch {
                l.fail(error)
            }
        }
    }
}
