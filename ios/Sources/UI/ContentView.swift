import SwiftUI

struct ContentView: View {
    @EnvironmentObject var bridge: BridgeManager
    @State private var showScanner = false
    @State private var showPasteAlert = false
    @State private var pastedQrText = ""
    @State private var dialNumber = ""
    @State private var copiedOtpId: String?

    private var effectiveLocale: Locale {
        if bridge.appLanguage == "system" { return .autoupdatingCurrent }
        return Locale(identifier: bridge.appLanguage)
    }

    private var isPersian: Bool {
        if bridge.appLanguage == "fa" { return true }
        if bridge.appLanguage == "en" { return false }
        return Locale.current.language.languageCode?.identifier == "fa"
    }

    var body: some View {
        TabView {
            BridgeHomeView(
                showScanner: $showScanner,
                showPasteAlert: $showPasteAlert,
                dialNumber: $dialNumber,
                isPersian: isPersian
            )
            .tabItem {
                Label(isPersian ? "پل ارتباطی" : "Bridge", systemImage: "antenna.radiowaves.left.and.right")
            }

            MessagesView(copiedOtpId: $copiedOtpId, isPersian: isPersian)
                .tabItem {
                    Label(isPersian ? "پیامک‌ها" : "Messages", systemImage: "message.fill")
                }

            CallsView(isPersian: isPersian)
                .tabItem {
                    Label(isPersian ? "تماس‌ها" : "Calls", systemImage: "phone.fill")
                }

            DebugSettingsView(isPersian: isPersian)
                .tabItem {
                    Label(isPersian ? "لاگ و تنظیمات" : "Debug", systemImage: "terminal.fill")
                }
        }
        .environment(\.locale, effectiveLocale)
        .environment(\.layoutDirection, isPersian ? .rightToLeft : .leftToRight)
        .sheet(isPresented: $showScanner) {
            NavigationStack {
                QrScannerView { scanned in
                    _ = bridge.pairFromQrString(scanned)
                    showScanner = false
                }
                .navigationTitle(isPersian ? "اسکن QR اندروید" : "Scan Android QR")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(isPersian ? "بستن" : "Close") {
                            showScanner = false
                        }
                    }
                }
            }
        }
        .alert(isPersian ? "چسباندن کد QR" : "Paste QR JSON", isPresented: $showPasteAlert) {
            TextField("{...}", text: $pastedQrText)
            Button(isPersian ? "اتصال" : "Pair") {
                _ = bridge.pairFromQrString(pastedQrText)
                pastedQrText = ""
            }
            Button(isPersian ? "لغو" : "Cancel", role: .cancel) {}
        }
    }
}

private struct BridgeHomeView: View {
    @EnvironmentObject var bridge: BridgeManager
    @Binding var showScanner: Bool
    @Binding var showPasteAlert: Bool
    @Binding var dialNumber: String
    let isPersian: Bool

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 12) {
                        Circle()
                            .fill(statusColor)
                            .frame(width: 12, height: 12)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(statusTitle)
                                .font(.headline)
                            if !bridge.pairedAndroidName.isEmpty {
                                Text(bridge.pairedAndroidName)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        if bridge.connectionStatus == .disconnected {
                            Button(isPersian ? "اتصال" : "Reconnect") {
                                bridge.startScan()
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    .padding(.vertical, 4)

                    if let active = bridge.activeCall {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(isPersian ? "تماس ورودی: \(active.displayCaller)" : "Incoming Call: \(active.displayCaller)")
                                .font(.headline)
                                .foregroundStyle(.orange)
                            HStack {
                                Button(role: .destructive) {
                                    bridge.sendCallAction(action: "REJECT", number: active.number)
                                } label: {
                                    Text(isPersian ? "رد تماس" : "Reject")
                                }
                                .buttonStyle(.borderedProminent)

                                Button {
                                    bridge.sendCallAction(action: "QUICK_SMS", number: active.number)
                                } label: {
                                    Text(isPersian ? "رد با پیامک" : "Busy SMS")
                                }
                                .buttonStyle(.bordered)
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }

                if let status = bridge.deviceStatus {
                    Section(isPersian ? "وضعیت گوشی اندروید" : "Android Device Status") {
                        HStack {
                            Label(isPersian ? "باتری" : "Battery", systemImage: status.charging ? "battery.100.bolt" : "battery.75")
                            Spacer()
                            Text("\(status.battery)%")
                                .fontWeight(.semibold)
                        }
                        HStack {
                            Label(isPersian ? "اپراتور" : "Carrier", systemImage: "antenna.radiowaves.left.and.right")
                            Spacer()
                            Text("\(status.operatorName) (\(status.signal)/4)")
                        }
                    }
                }

                Section(isPersian ? "شماره‌گیری با اندروید" : "Dial via Android") {
                    HStack {
                        TextField(isPersian ? "شماره تلفن..." : "Phone number...", text: $dialNumber)
                            .keyboardType(.phonePad)
                        Button(isPersian ? "تماس" : "Call") {
                            bridge.sendDialRequest(number: dialNumber)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(dialNumber.trimmingCharacters(in: .whitespaces).isEmpty || bridge.connectionStatus != .connected)
                    }
                }

                Section(isPersian ? "جفت‌سازی" : "Pairing") {
                    Button {
                        showScanner = true
                    } label: {
                        Label(isPersian ? "اسکن کد QR اندروید" : "Scan Pairing QR Code", systemImage: "qrcode.viewfinder")
                    }
                    Button {
                        showPasteAlert = true
                    } label: {
                        Label(isPersian ? "چسباندن دستی متن QR" : "Paste QR Payload", systemImage: "doc.on.clipboard")
                    }
                }
            }
            .navigationTitle("OpenBridge")
        }
    }

    private var statusColor: Color {
        switch bridge.connectionStatus {
        case .connected: return .green
        case .scanning: return .orange
        case .disconnected: return .red
        case .notPaired: return .gray
        }
    }

    private var statusTitle: String {
        switch bridge.connectionStatus {
        case .connected: return isPersian ? "متصل به اندروید" : "Connected"
        case .scanning: return isPersian ? "در جستجوی اندروید..." : "Searching for Android..."
        case .disconnected: return isPersian ? "قطع شده" : "Disconnected"
        case .notPaired: return isPersian ? "هنوز جفت نشده" : "Not Paired"
        }
    }
}

private struct MessagesView: View {
    @EnvironmentObject var bridge: BridgeManager
    @Binding var copiedOtpId: String?
    let isPersian: Bool

    var body: some View {
        NavigationStack {
            Group {
                if bridge.messages.isEmpty {
                    ContentUnavailableView(
                        isPersian ? "پیامکی دریافت نشده" : "No Messages Yet",
                        systemImage: "message"
                    )
                } else {
                    List(bridge.messages) { sms in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(sms.displaySender)
                                    .font(.headline)
                                Spacer()
                                Text(Date(timeIntervalSince1970: Double(sms.ts) / 1000.0), style: .time)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Text(sms.body)
                                .font(.subheadline)
                            if !sms.otp.isEmpty {
                                HStack {
                                    Text("OTP: \(sms.otp)")
                                        .font(.system(.subheadline, design: .monospaced))
                                        .fontWeight(.bold)
                                    Spacer()
                                    Button(copiedOtpId == sms.id ? (isPersian ? "کپی شد" : "Copied") : (isPersian ? "کپی کد" : "Copy OTP")) {
                                        UIPasteboard.general.string = sms.otp
                                        copiedOtpId = sms.id
                                    }
                                    .buttonStyle(.borderedProminent)
                                    .controlSize(.small)
                                }
                                .padding(.top, 4)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle(isPersian ? "پیامک‌ها" : "Messages")
        }
    }
}

private struct CallsView: View {
    @EnvironmentObject var bridge: BridgeManager
    let isPersian: Bool

    var body: some View {
        NavigationStack {
            Group {
                if bridge.calls.isEmpty {
                    ContentUnavailableView(
                        isPersian ? "تماسی ثبت نشده" : "No Call Events",
                        systemImage: "phone"
                    )
                } else {
                    List(bridge.calls) { call in
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(call.displayCaller)
                                    .font(.headline)
                                Text(call.number)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if !call.number.isEmpty {
                                Button(isPersian ? "تماس" : "Call") {
                                    bridge.sendDialRequest(number: call.number)
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }
                        }
                    }
                }
            }
            .navigationTitle(isPersian ? "تماس‌ها" : "Calls")
        }
    }
}

private struct DebugSettingsView: View {
    @EnvironmentObject var bridge: BridgeManager
    let isPersian: Bool

    var body: some View {
        NavigationStack {
            List {
                Section(isPersian ? "زبان برنامه" : "Language") {
                    Picker(isPersian ? "زبان" : "Language", selection: $bridge.appLanguage) {
                        Text(isPersian ? "پیش‌فرض سیستم" : "System").tag("system")
                        Text("English").tag("en")
                        Text("فارسی").tag("fa")
                    }
                    .pickerStyle(.segmented)
                }

                Section {
                    Button(isPersian ? "تست ارتباط (Ping)" : "Send Ping") {
                        bridge.sendPing()
                    }
                    Button(isPersian ? "لغو جفت‌سازی" : "Unpair Device", role: .destructive) {
                        bridge.unpair()
                    }
                }

                Section {
                    HStack {
                        Text("Debug Logs")
                            .font(.headline)
                        Spacer()
                        Button(isPersian ? "کپی" : "Copy") {
                            UIPasteboard.general.string = bridge.logs.joined(separator: "\n")
                        }
                        Button(isPersian ? "پاک کردن" : "Clear") {
                            bridge.clearLogs()
                        }
                    }
                    ForEach(bridge.logs, id: \.self) { line in
                        Text(line)
                            .font(.system(size: 11, design: .monospaced))
                    }
                }
            }
            .navigationTitle(isPersian ? "لاگ و تنظیمات" : "Debug & Settings")
        }
    }
}
