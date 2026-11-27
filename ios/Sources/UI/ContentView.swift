import SwiftUI

private extension Color {
    static let obBg = Color(red: 0.043, green: 0.063, blue: 0.125)      // #0B1020
    static let obCard = Color(red: 0.078, green: 0.110, blue: 0.200)    // #141C33
    static let obBorder = Color(red: 0.137, green: 0.188, blue: 0.337)  // #233056
    static let obCyan = Color(red: 0.133, green: 0.827, blue: 0.933)    // #22D3EE
    static let obIndigo = Color(red: 0.388, green: 0.400, blue: 0.945)  // #6366F1
    static let obGreen = Color(red: 0.063, green: 0.725, blue: 0.506)   // #10B981
}

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
                isPersian: isPersian
            )
            .tabItem {
                Label(isPersian ? "پل ارتباطی" : "Bridge", systemImage: "antenna.radiowaves.left.and.right")
            }

            DialerAndCallsView(dialNumber: $dialNumber, isPersian: isPersian)
                .tabItem {
                    Label(isPersian ? "تماس" : "Phone", systemImage: "phone.fill")
                }

            MessagesView(copiedOtpId: $copiedOtpId, isPersian: isPersian)
                .tabItem {
                    Label(isPersian ? "پیامک‌ها" : "Messages", systemImage: "message.fill")
                }

            SettingsView(isPersian: isPersian)
                .tabItem {
                    Label(isPersian ? "تنظیمات" : "Settings", systemImage: "gearshape.fill")
                }
        }
        .tint(.obCyan)
        .preferredColorScheme(.dark)
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
    let isPersian: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Hero Brand + Status Card
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(spacing: 14) {
                            Image("BrandLogo")
                                .resizable()
                                .scaledToFit()
                                .frame(width: 52, height: 52)
                                .clipShape(RoundedRectangle(cornerRadius: 13))
                                .shadow(color: .obCyan.opacity(0.35), radius: 10, y: 4)

                            VStack(alignment: .leading, spacing: 4) {
                                Text("OpenBridge")
                                    .font(.title2.bold())
                                HStack(spacing: 6) {
                                    Circle()
                                        .fill(statusColor)
                                        .frame(width: 9, height: 9)
                                    Text(statusTitle)
                                        .font(.subheadline.weight(.medium))
                                        .foregroundStyle(statusColor)
                                }
                            }
                            Spacer()
                        }

                        if !bridge.pairedAndroidName.isEmpty {
                            HStack {
                                Image(systemName: "iphone.gen1.and.arrow.left")
                                    .foregroundStyle(Color.obCyan)
                                Text(bridge.pairedAndroidName)
                                    .font(.subheadline.weight(.semibold))
                                Spacer()
                                if bridge.connectionStatus == .disconnected {
                                    Button(isPersian ? "اتصال مجدد" : "Reconnect") {
                                        bridge.startScan()
                                    }
                                    .buttonStyle(.borderedProminent)
                                    .controlSize(.small)
                                }
                            }
                            .padding(10)
                            .background(Color.black.opacity(0.25))
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    .padding(18)
                    .background(
                        LinearGradient(
                            colors: [Color(red: 0.09, green: 0.145, blue: 0.329), Color.obCard],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 22)
                            .stroke(statusColor.opacity(0.45), lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 22))

                    // Active Incoming Call Banner
                    if let active = bridge.activeCall {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                Image(systemName: "phone.arrow.down.left.fill")
                                    .foregroundStyle(.orange)
                                Text(isPersian ? "تماس ورودی: \(active.displayCaller)" : "Incoming: \(active.displayCaller)")
                                    .font(.headline)
                                Spacer()
                            }
                            HStack(spacing: 10) {
                                Button {
                                    bridge.sendCallAction(action: "ACCEPT", number: active.number)
                                } label: {
                                    Label(isPersian ? "پاسخ" : "Answer", systemImage: "phone.fill")
                                        .frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.borderedProminent)
                                .tint(.obGreen)

                                Button(role: .destructive) {
                                    bridge.sendCallAction(action: "REJECT", number: active.number)
                                } label: {
                                    Label(isPersian ? "رد" : "Reject", systemImage: "phone.down.fill")
                                        .frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.borderedProminent)

                                Button {
                                    bridge.sendCallAction(action: "QUICK_SMS", number: active.number)
                                } label: {
                                    Image(systemName: "message.fill")
                                }
                                .buttonStyle(.bordered)
                            }
                        }
                        .padding(16)
                        .background(Color.obCard)
                        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.orange.opacity(0.6), lineWidth: 1))
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                    }

                    // Android Device Vitals Grid
                    if let status = bridge.deviceStatus {
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                            VitalTile(
                                icon: status.charging ? "battery.100.bolt" : "battery.75",
                                title: isPersian ? "باتری اندروید" : "Battery",
                                value: "\(status.battery)%",
                                tint: status.battery > 20 ? .obGreen : .red
                            )
                            VitalTile(
                                icon: "antenna.radiowaves.left.and.right",
                                title: status.operatorName,
                                value: "\(status.signal)/4",
                                tint: .obCyan
                            )
                        }
                        if let ip = status.wifiIp, !ip.isEmpty {
                            HStack {
                                Image(systemName: "wifi")
                                    .foregroundStyle(Color.obCyan)
                                Text(isPersian ? "آی‌پی صدای هات‌اسپات: \(ip)" : "Hotspot Audio IP: \(ip)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Spacer()
                            }
                            .padding(12)
                            .background(Color.obCard)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                    }

                    // Pairing Card
                    VStack(spacing: 10) {
                        Button {
                            showScanner = true
                        } label: {
                            Label(
                                isPersian ? "اسکن کد QR گوشی اندروید" : "Scan Android Pairing QR",
                                systemImage: "qrcode.viewfinder"
                            )
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.obIndigo)

                        Button {
                            showPasteAlert = true
                        } label: {
                            Label(
                                isPersian ? "چسباندن دستی متن QR" : "Paste QR JSON Manually",
                                systemImage: "doc.on.clipboard"
                            )
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding(16)
                    .background(Color.obCard)
                    .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.obBorder, lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                }
                .padding(16)
            }
            .background(Color.obBg.ignoresSafeArea())
            .navigationTitle(isPersian ? "داشبورد" : "Dashboard")
        }
    }

    private var statusColor: Color {
        switch bridge.connectionStatus {
        case .connected: return .obGreen
        case .scanning: return .orange
        case .disconnected: return .red
        case .notPaired: return .gray
        }
    }

    private var statusTitle: String {
        switch bridge.connectionStatus {
        case .connected: return isPersian ? "متصل و رمزنگاری‌شده" : "Connected • E2EE Active"
        case .scanning: return isPersian ? "در جستجوی گوشی اندروید..." : "Searching for Android..."
        case .disconnected: return isPersian ? "اتصال قطع است" : "Disconnected"
        case .notPaired: return isPersian ? "آماده جفت‌سازی" : "Ready to Pair"
        }
    }
}

private struct VitalTile: View {
    let icon: String
    let title: String
    let value: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(tint)
            Text(value)
                .font(.title2.bold())
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color.obCard)
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.obBorder, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

private struct DialerAndCallsView: View {
    @EnvironmentObject var bridge: BridgeManager
    @Binding var dialNumber: String
    let isPersian: Bool
    @State private var showContactPicker = false
    @State private var showHistory = false

    private let keys: [[(String, String)]] = [
        [("1", ""), ("2", "ABC"), ("3", "DEF")],
        [("4", "GHI"), ("5", "JKL"), ("6", "MNO")],
        [("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ")],
        [("*", ""), ("0", "+"), ("#", "")]
    ]

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                // Mode indicator pill
                HStack {
                    Image(systemName: bridge.voiceMode == .sip ? "network" : "waveform")
                        .foregroundStyle(Color.obCyan)
                    Text(modeBadgeText)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button {
                        showContactPicker = true
                    } label: {
                        Label(isPersian ? "مخاطبین" : "Contacts", systemImage: "person.crop.circle")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                .padding(.horizontal, 20)

                Spacer(minLength: 4)

                // Dialed number display
                Text(dialNumber.isEmpty ? (isPersian ? "شماره را وارد کنید" : "Enter Number") : dialNumber)
                    .font(.system(size: dialNumber.isEmpty ? 20 : 32, weight: .semibold, design: .rounded))
                    .foregroundStyle(dialNumber.isEmpty ? .secondary : .primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .padding(.horizontal, 24)
                    .frame(height: 46)

                // Keypad
                VStack(spacing: 12) {
                    ForEach(0..<keys.count, id: \.self) { row in
                        HStack(spacing: 20) {
                            ForEach(keys[row], id: \.0) { digit, letters in
                                Button {
                                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                    dialNumber.append(digit)
                                } label: {
                                    VStack(spacing: 1) {
                                        Text(digit)
                                            .font(.system(size: 26, weight: .medium, design: .rounded))
                                        if !letters.isEmpty {
                                            Text(letters)
                                                .font(.system(size: 9, weight: .bold))
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    .frame(width: 72, height: 72)
                                    .background(Color.obCard)
                                    .overlay(Circle().stroke(Color.obBorder, lineWidth: 1))
                                    .clipShape(Circle())
                                }
                                .buttonStyle(.plain)
                                .simultaneousGesture(
                                    LongPressGesture(minimumDuration: 0.4).onEnded { _ in
                                        if digit == "0" {
                                            dialNumber.append("+")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Call + Backspace row
                HStack(spacing: 28) {
                    Button {
                        showHistory = true
                    } label: {
                        Image(systemName: "clock.arrow.circlepath")
                            .font(.title2)
                            .frame(width: 56, height: 56)
                    }
                    .foregroundStyle(.secondary)

                    Button {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        bridge.initiateCall(number: dialNumber)
                    } label: {
                        Image(systemName: "phone.fill")
                            .font(.title)
                            .foregroundStyle(.white)
                            .frame(width: 72, height: 72)
                            .background(Color.obGreen)
                            .clipShape(Circle())
                            .shadow(color: Color.obGreen.opacity(0.4), radius: 10, y: 4)
                    }
                    .disabled(dialNumber.trimmingCharacters(in: .whitespaces).isEmpty)

                    Button {
                        if !dialNumber.isEmpty {
                            dialNumber.removeLast()
                        }
                    } label: {
                        Image(systemName: "delete.left.fill")
                            .font(.title2)
                            .frame(width: 56, height: 56)
                    }
                    .foregroundStyle(.secondary)
                    .disabled(dialNumber.isEmpty)
                }
                .padding(.top, 4)

                Spacer(minLength: 8)
            }
            .padding(.top, 10)
            .background(Color.obBg.ignoresSafeArea())
            .navigationTitle(isPersian ? "شماره‌گیر" : "Phone")
            .sheet(isPresented: $showContactPicker) {
                ContactPickerView { number, name in
                    dialNumber = number
                    showContactPicker = false
                    bridge.log("Picked contact: \(name) (\(number))")
                }
            }
            .sheet(isPresented: $showHistory) {
                NavigationStack {
                    List(bridge.calls) { call in
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(call.displayCaller).font(.headline)
                                Text("\(call.state) • \(call.number)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if !call.number.isEmpty {
                                Button(isPersian ? "تماس" : "Call") {
                                    showHistory = false
                                    bridge.initiateCall(number: call.number, displayName: call.displayCaller)
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                            }
                        }
                    }
                    .navigationTitle(isPersian ? "تاریخچه تماس" : "Recent Calls")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(isPersian ? "بستن" : "Close") { showHistory = false }
                        }
                    }
                }
            }
        }
    }

    private var modeBadgeText: String {
        switch bridge.voiceMode {
        case .localWifi: return isPersian ? "حالت تماس CallKit + صدای هات‌اسپات" : "CallKit + Local Wi-Fi Audio"
        case .sip: return isPersian ? "حالت تماس SIP (\(bridge.sipStatusText))" : "SIP VoIP (\(bridge.sipStatusText))"
        case .remoteOnly: return isPersian ? "فقط کنترل تماس اندروید" : "Android Remote Control"
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
                        isPersian ? "هنوز پیامکی دریافت نشده" : "No Messages Yet",
                        systemImage: "message"
                    )
                } else {
                    ScrollView {
                        LazyVStack(spacing: 12) {
                            ForEach(bridge.messages) { sms in
                                VStack(alignment: .leading, spacing: 8) {
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
                                        .foregroundStyle(.secondary)
                                    if !sms.otp.isEmpty {
                                        HStack {
                                            Text(sms.otp)
                                                .font(.system(.title3, design: .monospaced).bold())
                                                .foregroundStyle(Color.obGreen)
                                            Spacer()
                                            Button {
                                                UIPasteboard.general.string = sms.otp
                                                UINotificationFeedbackGenerator().notificationOccurred(.success)
                                                copiedOtpId = sms.id
                                            } label: {
                                                Label(
                                                    copiedOtpId == sms.id ? (isPersian ? "کپی شد" : "Copied") : (isPersian ? "کپی کد" : "Copy OTP"),
                                                    systemImage: copiedOtpId == sms.id ? "checkmark" : "doc.on.doc"
                                                )
                                            }
                                            .buttonStyle(.borderedProminent)
                                            .tint(copiedOtpId == sms.id ? .obGreen : .obCyan)
                                            .controlSize(.small)
                                        }
                                        .padding(10)
                                        .background(Color.obGreen.opacity(0.12))
                                        .clipShape(RoundedRectangle(cornerRadius: 12))
                                    }
                                }
                                .padding(14)
                                .background(Color.obCard)
                                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.obBorder, lineWidth: 1))
                                .clipShape(RoundedRectangle(cornerRadius: 16))
                            }
                        }
                        .padding(16)
                    }
                }
            }
            .background(Color.obBg.ignoresSafeArea())
            .navigationTitle(isPersian ? "پیامک‌ها" : "Messages")
        }
    }
}

private struct SettingsView: View {
    @EnvironmentObject var bridge: BridgeManager
    let isPersian: Bool
    @State private var showSipSheet = false
    @State private var showDiagnostics = false

    var body: some View {
        NavigationStack {
            List {
                Section(isPersian ? "حالت مکالمه (CallKit)" : "Calling & Voice Mode") {
                    Picker(isPersian ? "حالت صدا" : "Voice Mode", selection: $bridge.voiceMode) {
                        Text(isPersian ? "صدای محلی + CallKit" : "Local Wi-Fi + CallKit").tag(VoiceCallMode.localWifi)
                        Text(isPersian ? "تلفن اینترنتی (SIP)" : "SIP VoIP + CallKit").tag(VoiceCallMode.sip)
                        Text(isPersian ? "فقط کنترل از راه دور" : "Remote Control Only").tag(VoiceCallMode.remoteOnly)
                    }
                    Button {
                        showSipSheet = true
                    } label: {
                        HStack {
                            Label(isPersian ? "تنظیمات حساب SIP / VoIP" : "SIP / VoIP Account", systemImage: "server.rack")
                            Spacer()
                            Text(bridge.sipConfig.isConfigured ? bridge.sipStatusText : (isPersian ? "تنظیم نشده" : "Not set"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Section(isPersian ? "زبان برنامه" : "Language") {
                    Picker(isPersian ? "زبان" : "Language", selection: $bridge.appLanguage) {
                        Text(isPersian ? "سیستم" : "System").tag("system")
                        Text("English").tag("en")
                        Text("فارسی").tag("fa")
                    }
                    .pickerStyle(.segmented)
                }

                Section(isPersian ? "عیب‌یابی و اتصال" : "Connection & Diagnostics") {
                    Button {
                        bridge.sendPing()
                    } label: {
                        Label(isPersian ? "تست ارتباط (Ping)" : "Ping Android", systemImage: "bolt.horizontal")
                    }
                    Button {
                        showDiagnostics = true
                    } label: {
                        Label(isPersian ? "کنسول فنی و لاگ‌ها" : "Diagnostics & Logs", systemImage: "terminal")
                    }
                    Button(role: .destructive) {
                        bridge.unpair()
                    } label: {
                        Label(isPersian ? "لغو جفت‌سازی" : "Unpair Device", systemImage: "xmark.circle")
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color.obBg.ignoresSafeArea())
            .navigationTitle(isPersian ? "تنظیمات" : "Settings")
            .sheet(isPresented: $showSipSheet) {
                SipSetupSheet(isPersian: isPersian)
            }
            .sheet(isPresented: $showDiagnostics) {
                NavigationStack {
                    List(bridge.logs, id: \.self) { line in
                        Text(line)
                            .font(.system(size: 11, design: .monospaced))
                    }
                    .navigationTitle(isPersian ? "لاگ‌های فنی" : "Debug Console")
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button(isPersian ? "بستن" : "Close") { showDiagnostics = false }
                        }
                        ToolbarItemGroup(placement: .topBarTrailing) {
                            Button(isPersian ? "کپی" : "Copy") {
                                UIPasteboard.general.string = bridge.logs.joined(separator: "\n")
                            }
                            Button(isPersian ? "پاک" : "Clear") {
                                bridge.clearLogs()
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct SipSetupSheet: View {
    @EnvironmentObject var bridge: BridgeManager
    @Environment(\.dismiss) private var dismiss
    let isPersian: Bool
    @State private var server = ""
    @State private var port = "5060"
    @State private var username = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                Section(isPersian ? "مشخصات سرور SIP" : "SIP Server Details") {
                    TextField(isPersian ? "آدرس سرور (مثلاً sip.example.ir)" : "Server Host", text: $server)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField(isPersian ? "پورت (5060)" : "Port (5060)", text: $port)
                        .keyboardType(.numberPad)
                    TextField(isPersian ? "نام کاربری / شماره داخلی" : "Username", text: $username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    SecureField(isPersian ? "رمز عبور" : "Password", text: $password)
                }

                Section {
                    Button(isPersian ? "ذخیره و اتصال (Register)" : "Save & Register") {
                        bridge.sipConfig = SipAccountConfig(
                            server: server.trimmingCharacters(in: .whitespaces),
                            port: Int(port) ?? 5060,
                            username: username.trimmingCharacters(in: .whitespaces),
                            password: password
                        )
                        bridge.voiceMode = .sip
                        bridge.registerSip()
                        dismiss()
                    }
                    .disabled(server.isEmpty || username.isEmpty)
                }
            }
            .navigationTitle("SIP / VoIP")
            .onAppear {
                server = bridge.sipConfig.server
                port = String(bridge.sipConfig.port)
                username = bridge.sipConfig.username
                password = bridge.sipConfig.password
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(isPersian ? "بستن" : "Close") { dismiss() }
                }
            }
        }
    }
}
