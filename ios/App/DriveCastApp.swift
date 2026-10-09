import Network
import ReplayKit
import SwiftUI

@main
struct DriveCastApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @StateObject private var model = Model()
    // UIDevice.name 从 iOS 16 起只返回"iPhone"，车机上显示的名字让用户自己填
    @AppStorage("deviceName", store: Store.defaults) private var deviceName = "我的 iPhone"
    @AppStorage("manual", store: Store.defaults) private var manual = ""
    @State private var code = ""

    var body: some View {
        NavigationStack {
            Form {
                if !Store.groupOK {
                    Section {
                        Text("App Group「\(Store.group)」不可用：安装时的签名工具改了它的名字或没有保留它。投屏扩展读不到这里的配对信息，无法投屏。请换用能保留 App Group 的签名方式（见项目 ios/README.md）。")
                            .foregroundStyle(.red)
                    }
                }
                if model.localNetworkDenied {
                    Section {
                        Text("DriveCast 没有本地网络权限，找不到车机。请在 设置 → 隐私与安全性 → 本地网络 中打开 DriveCast。")
                            .foregroundStyle(.red)
                    }
                }
                Section("开始投屏") {
                    HStack(spacing: 16) {
                        BroadcastPicker().frame(width: 56, height: 56)
                        Text("点左边的按钮，再点「开始直播」。停止时点状态栏的红色标记，或在控制中心里停止。")
                    }
                }
                Section("使用前（请停车时设置）") {
                    Text("设置 → 显示与亮度 → 自动锁定 选「永不」：锁屏会结束投屏。")
                    Text("声音请让 iPhone 通过蓝牙连接车机播放。")
                    Text("车机上只能看，不能操作 iPhone（iOS 不允许反向控制）。")
                    Text("iPhone 和车机要连在同一个 Wi-Fi 或热点上。")
                }
                Section {
                    if model.found.isEmpty {
                        Text("正在查找…").foregroundStyle(.secondary)
                    }
                    ForEach(model.found) { car in
                        Button {
                            pair(car.endpoint, car.id)
                        } label: {
                            HStack {
                                Text(car.id)
                                Spacer()
                                Text(model.paired[car.carId] == nil ? "配对" : "已配对，重新配对").foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("附近的车机")
                } footer: {
                    Text("先在车机的 DriveCast 里点「添加 iPhone/鸿蒙」，再点这里的车机，然后输入车机上显示的配对码。")
                }
                Section {
                    TextField("例如 192.168.43.1", text: $manual)
                        .keyboardType(.numbersAndPunctuation)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("用这个地址配对") {
                        if let ep = Store.endpoint(manual) { pair(ep, manual) }
                    }
                    .disabled(Store.endpoint(manual) == nil)
                } header: {
                    Text("车机地址")
                } footer: {
                    Text("上面找不到车机时手动输入，地址显示在车机的 DriveCast 里。投屏时找不到车机也会试这个地址。")
                }
                Section("这台 iPhone 在车机上显示的名字") {
                    TextField("名字", text: $deviceName)
                }
                if !model.paired.isEmpty {
                    let cars = model.paired.sorted { $0.value < $1.value }
                    Section("已配对的车机（左滑删除）") {
                        ForEach(cars, id: \.key) { Text($0.value) }
                            .onDelete { rows in rows.forEach { model.forget(cars[$0].key) } }
                    }
                }
            }
            .navigationTitle("DriveCast")
            .sheet(isPresented: Binding(get: { model.phase != .idle }, set: { if !$0 { model.cancel() } })) {
                PairSheet(model: model, code: $code)
            }
        }
    }

    private func pair(_ endpoint: NWEndpoint, _ name: String) {
        code = ""
        model.pair(endpoint, carName: name, deviceName: deviceName)
    }
}

struct PairSheet: View {
    @ObservedObject var model: Model
    @Binding var code: String

    var body: some View {
        NavigationStack {
            Form {
                switch model.phase {
                case .idle, .connecting:
                    ProgressView("正在连接车机…")
                case .code:
                    Section {
                        TextField("6 位配对码", text: $code).keyboardType(.numberPad)
                        Button("确定") { model.submit(code) }
                    } footer: {
                        Text(model.codeError.isEmpty ? "输入车机屏幕上显示的配对码。" : model.codeError)
                            .foregroundStyle(model.codeError.isEmpty ? Color.secondary : Color.red)
                    }
                case .working:
                    ProgressView("正在配对…")
                case .done(let message):
                    Text(message)
                case .failed(let message):
                    Text(message).foregroundStyle(.red)
                    Text("请在车机上重新点「添加 iPhone/鸿蒙」后再试。").foregroundStyle(.secondary)
                }
            }
            .navigationTitle("配对")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                Button(isDone ? "完成" : "取消") { model.cancel() }
            }
        }
    }

    private var isDone: Bool { if case .done = model.phase { return true } else { return false } }
}

/// 系统的广播选择按钮。只列出自己的扩展，不显示麦克风开关（声音走蓝牙）。
struct BroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let v = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 56, height: 56))
        // 从包里读扩展的 bundle id：重签名工具可能改了它
        v.preferredExtension = Bundle.main.builtInPlugInsURL
            .flatMap { Bundle(url: $0.appendingPathComponent("DriveCastBroadcast.appex")) }?.bundleIdentifier
        v.showsMicrophoneButton = false
        return v
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}
