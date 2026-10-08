import DriveCastCore
import Foundation
import Network
import Security

/// App 和广播扩展共享的数据：phoneId 和每台车机的 LTK 放钥匙串（访问组用 App Group），
/// 其余不保密的设置放 App Group 的 UserDefaults。
enum Store {
    /// AltStore 会把 App Group 改名成 "<group>.<TEAMID>"，并把真名写进 App 和扩展 Info.plist 的 ALTAppGroups。
    static let group = (Bundle.main.object(forInfoDictionaryKey: "ALTAppGroups") as? [String])?.first {
        $0.hasPrefix("group.io.github.drivecast")
    } ?? "group.io.github.drivecast"
    static let defaults = UserDefaults(suiteName: group) ?? .standard

    /// 签名工具把 App Group 改了名或去掉时为 false：扩展读不到 App 里的配对信息。
    static var groupOK: Bool { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) != nil }

    /// 已配对的车机：carId（十六进制）→ 名称。
    static var cars: [String: String] {
        get { defaults.dictionary(forKey: "cars") as? [String: String] ?? [:] }
        set { defaults.set(newValue, forKey: "cars") }
    }

    /// 用户手动输入的车机地址，找车的最后一步。
    static var manual: String { defaults.string(forKey: "manual") ?? "" }

    /// 上次连上的地址，最近的在前。
    static var addresses: [String] { defaults.stringArray(forKey: "addresses") ?? [] }

    static func remember(_ ep: NWEndpoint) {
        guard case .hostPort(let host, let port) = ep else { return }
        let s: String
        switch host {
        case .ipv4(let a): s = "\(a):\(port.rawValue)"
        case .ipv6(let a): s = "[\(a)]:\(port.rawValue)"
        case .name(let n, _): s = "\(n):\(port.rawValue)"
        @unknown default: return
        }
        defaults.set(Array(([s] + addresses.filter { $0 != s }).prefix(4)), forKey: "addresses")
    }

    /// "192.168.43.1"、"192.168.43.1:27420"、"[fe80::1%en0]:27420"、"fe80::1"，不写端口就是 27420。
    static func endpoint(_ text: String) -> NWEndpoint? {
        var host = Substring(text.trimmingCharacters(in: .whitespaces)), port = carPort
        if host.hasPrefix("["), let end = host.firstIndex(of: "]") {
            let rest = host[host.index(after: end)...]
            if rest.hasPrefix(":") { guard let p = UInt16(rest.dropFirst()) else { return nil }; port = p }
            host = host[host.index(after: host.startIndex)..<end]
        } else if host.filter({ $0 == ":" }).count == 1, let i = host.firstIndex(of: ":") {
            guard let p = UInt16(host[host.index(after: i)...]) else { return nil }
            port = p
            host = host[..<i]
        }
        guard !host.isEmpty, let p = NWEndpoint.Port(rawValue: port) else { return nil }
        return .hostPort(host: NWEndpoint.Host(String(host)), port: p)
    }

    /// 这台 iPhone 的 16 字节随机 ID，第一次配对时生成。
    static func phoneId(create: Bool) -> Data? {
        if let id = secret("phoneId") { return id }
        guard create else { return nil }
        let id = randomBytes(16)
        return setSecret("phoneId", id) ? id : nil
    }

    static func ltk(_ carId: Data) -> Data? { secret("car." + carId.hex) }

    static func savePairing(carId: Data, ltk: Data, name: String) -> Bool {
        guard setSecret("car." + carId.hex, ltk) else { return false }
        cars[carId.hex] = name
        return true
    }

    static func forget(_ carIdHex: String) {
        SecItemDelete(query("car." + carIdHex) as CFDictionary)
        cars[carIdHex] = nil
    }

    private static func query(_ account: String) -> [CFString: Any] {
        [kSecClass: kSecClassGenericPassword, kSecAttrService: "io.github.drivecast",
         kSecAttrAccount: account, kSecAttrAccessGroup: group]
    }

    private static func secret(_ account: String) -> Data? {
        var q = query(account)
        q[kSecReturnData] = true
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    private static func setSecret(_ account: String, _ data: Data) -> Bool {
        SecItemDelete(query(account) as CFDictionary)
        var q = query(account)
        q[kSecValueData] = data
        // 扩展在锁屏后也要能读：首次解锁后可用，且不随备份迁移到别的设备
        q[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }
}
