import SwiftUI

private extension Color {
    static let obBg = Color(red: 0.043, green: 0.063, blue: 0.125)      // #0B1020
    static let obCard = Color(red: 0.078, green: 0.110, blue: 0.200)    // #141C33
    static let obCardOut = Color(red: 0.055, green: 0.165, blue: 0.180) // #0E2A2E
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

private struct ActiveCallBanner: View {
    @EnvironmentObject var bridge: BridgeManager
    let call: RelayedCall
    let isPersian: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: call.state == "RINGING" ? "phone.arrow.down.left.fill" : "phone.connection.fill")
                    .foregroundStyle(call.state == "RINGING" ? .orange : .obGreen)
                VStack(alignment: .leading, spacing: 2) {
                    Text(call.displayCaller)
                        .font(.headline)
                    Text(stateSubtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                audioBadge
            }

            if call.state == "RINGING" {
                HStack(spacing: 10) {
                    Button {
                        bridge.answerActiveCall()
                    } label: {
                        Label(isPersian ? "پاسخ" : "Answer", systemImage: "phone.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.obGreen)

                    Button(role: .destructive) {
                        bridge.endCurrentCall()
                    } label: {
                        Label(isPersian ? "رد" : "Reject", systemImage: "phone.down.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)

                    Button {
                        bridge.sendCallAction(action: "QUICK_SMS", number: call.number)
                    } label: {
                        Image(systemName: "message.fill")
                    }
                    .buttonStyle(.bordered)
                }
            } else {
                VStack(spacing: 8) {
                    if bridge.voiceMode == .sip {
                        Label(
                            isPersian ? "صدای دوطرفه مستقیم SIP (کیفیت کامل حتی در کیف)" : "Full-Duplex SIP VoIP (Bag-Ready)",
                            systemImage: "checkmark.shield.fill"
                        )
                        .font(.caption2)
                        .foregroundStyle(Color.obCyan)
                    } else if bridge.isAudioDirect {
                        Label(
                            isPersian ? "صدای مستقیم تماس فعال است (حالت داخل کیف)" : "Direct Call Audio Active (\(bridge.audioPathDescription))",
                            systemImage: "checkmark.seal.fill"
                        )
                        .font(.caption2)
                        .foregroundStyle(Color.obGreen)
                    } else if !bridge.audioPathDescription.isEmpty {
                        Label(
                            isPersian ? "فالبک میکروفون اندروید (برای داخل کیف، Shizuku را در اندروید تایید کنید)" : "Mic Relay Fallback (Enable Shizuku on Android for in-bag)",
                            systemImage: "exclamationmark.triangle.fill"
                        )
                        .font(.caption2)
                        .foregroundStyle(.orange)
                    }

                    HStack(spacing: 10) {
                        Button {
                            bridge.toggleMute()
                        } label: {
                            Label(
                                bridge.isCallMuted ? (isPersian ? "باصدا" : "Unmute") : (isPersian ? "بی‌صدا" : "Mute"),
                                systemImage: bridge.isCallMuted ? "mic.slash.fill" : "mic.fill"
                            )
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .tint(bridge.isCallMuted ? .orange : .obCyan)

                        Button {
                            bridge.toggleSpeaker()
                        } label: {
                            Label(
                                isPersian ? "اسپیکر" : "Speaker",
                                systemImage: bridge.isSpeakerOn ? "speaker.wave.3.fill" : "speaker.wave.1.fill"
                            )
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .tint(bridge.isSpeakerOn ? .obCyan : .secondary)

                        Button(role: .destructive) {
                            bridge.endCurrentCall()
                        } label: {
                            Label(isPersian ? "قطع" : "End", systemImage: "phone.down.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                    }

                    if !bridge.isAudioDirect && bridge.voiceMode != .sip {
                        Button {
                            bridge.toggleAndroidSpeaker()
                        } label: {
                            Label(
                                bridge.androidSpeakerOn
                                    ? (isPersian ? "اسپیکر اندروید: روشن" : "Android Speaker: ON")
                                    : (isPersian ? "روشن کردن اسپیکر گوشی اندروید" : "Enable Android Speakerphone"),
                                systemImage: bridge.androidSpeakerOn ? "speaker.wave.2.circle.fill" : "speaker.wave.2.circle"
                            )
                            .font(.caption)
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .tint(bridge.androidSpeakerOn ? .orange : .secondary)
                    }
                }
            }
        }
        .padding(16)
        .background(Color.obCard)
        .overlay(
            RoundedRectangle(cornerRadius: 18)
                .stroke((call.state == "RINGING" ? Color.orange : Color.obGreen).opacity(0.65), lineWidth: 1.2)
        )
        .clipShape(RoundedRectangle(cornerRadius: 18))
    }

    private var stateSubtitle: String {
        switch call.state {
        case "RINGING": return isPersian ? "تماس ورودی در حال زنگ خوردن..." : "Incoming Call Ringing..."
        case "DIALING": return isPersian ? "در حال برقراری تماس..." : "Dialing via Android..."
        case "OFFHOOK": return isPersian ? "مکالمه برقرار است" : "In Active Call"
        default: return call.state
        }
    }

    @ViewBuilder
    private var audioBadge: some View {
        let (text, color): (String, Color) = {
            if bridge.voiceMode == .sip {
                return (isPersian ? "صدای SIP" : "SIP Audio", .obCyan)
            }
            if bridge.audioTransportMode == "ble_adpcm" {
                switch bridge.audioLinkState {
                case .connected: return (isPersian ? "بلوتوث ۳۲k ✓" : "BLE 32k ✓", .obGreen)
                case .connecting, .reconnecting: return (isPersian ? "اتصال بلوتوث..." : "BLE Audio...", .orange)
                case .failed: return (isPersian ? "خطای صدا" : "Audio Error", .red)
                case .idle: return (isPersian ? "آماده بلوتوث" : "BLE Ready", .obCyan)
                }
            }
            switch bridge.audioLinkState {
            case .connected: return (isPersian ? "وای‌فای HD ✓" : "Wi-Fi HD ✓", .obGreen)
            case .connecting, .reconnecting: return (isPersian ? "اتصال صدا..." : "Connecting...", .orange)
            case .failed: return (isPersian ? "فالبک بلوتوث" : "BLE Fallback", .orange)
            case .idle:
                let hasIp = !(bridge.deviceStatus?.wifiIp ?? "").isEmpty
                return hasIp
                    ? (isPersian ? "وای‌فای + بلوتوث" : "Wi-Fi + BLE", .obCyan)
                    : (isPersian ? "بلوتوث خالص آماده" : "Pure BLE Ready", .obCyan)
            }
        }()
        Text(text)
            .font(.caption2.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.16))
            .foregroundStyle(color)
            .clipShape(Capsule())
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

                    if let active = bridge.activeCall {
                        ActiveCallBanner(call: active, isPersian: isPersian)
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
                        HStack(spacing: 10) {
                            Image(systemName: (status.wifiIp ?? "").isEmpty ? "waveform.path.ecg" : "wifi")
                                .foregroundStyle((status.wifiIp ?? "").isEmpty ? Color.obGreen : Color.obCyan)
                            VStack(alignment: .leading, spacing: 2) {
                                if let ip = status.wifiIp, !ip.isEmpty {
                                    Text(isPersian ? "وای‌فای HD (\(ip)) + فالبک بلوتوث خالص آماده" : "Hotspot Wi-Fi HD (\(ip)) + Pure-BLE Fallback Ready")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                } else {
                                    Text(isPersian ? "صدای تماس بدون وای‌فای (بلوتوث خالص ۳۲k) آماده است" : "Pure-BLE Call Audio Ready (No Wi-Fi / Hotspot Needed)")
                                        .font(.caption)
                                        .foregroundStyle(Color.obGreen)
                                }
                                if let cap = bridge.voiceCapability {
                                    Text(cap.summary)
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                            }
                            Spacer()
                            if bridge.isProbingVoice {
                                ProgressView().controlSize(.small)
                            } else {
                                Button(isPersian ? "تست صدا" : "Probe") {
                                    bridge.requestVoiceCompatibilityCheck()
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.mini)
                            }
                        }
                        .padding(12)
                        .background(Color.obCard)
                        .clipShape(RoundedRectangle(cornerRadius: 14))

                        // Full Android Sync Card (Contacts, SMS History, Call Log)
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Label(isPersian ? "همگام‌سازی کامل گوشی اندروید" : "Android Full Sync", systemImage: "arrow.triangle.2.circlepath.circle.fill")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(Color.obCyan)
                                Spacer()
                                if bridge.isSyncing {
                                    ProgressView().controlSize(.small)
                                } else {
                                    Button(isPersian ? "بروزرسانی" : "Sync Now") {
                                        bridge.requestSync()
                                    }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                                }
                            }
                            HStack(spacing: 14) {
                                Text(isPersian ? "مخاطبین: \(bridge.androidContacts.count)" : "Contacts: \(bridge.androidContacts.count)")
                                Text(isPersian ? "پیامک‌ها: \(bridge.messages.count)" : "SMS: \(bridge.messages.count)")
                                Text(isPersian ? "تماس‌ها: \(bridge.calls.count)" : "Calls: \(bridge.calls.count)")
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        }
                        .padding(14)
                        .background(Color.obCard)
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.obBorder, lineWidth: 1))
                        .clipShape(RoundedRectangle(cornerRadius: 16))
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
    @State private var showSimTools = false

    private let keys: [[(String, String)]] = [
        [("1", ""), ("2", "ABC"), ("3", "DEF")],
        [("4", "GHI"), ("5", "JKL"), ("6", "MNO")],
        [("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ")],
        [("*", ""), ("0", "+"), ("#", "")]
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    if let active = bridge.activeCall {
                        ActiveCallBanner(call: active, isPersian: isPersian)
                            .padding(.horizontal, 16)
                    }

                    // Mode indicator + Actions row
                    HStack(spacing: 8) {
                        Image(systemName: bridge.voiceMode == .sip ? "network" : "waveform")
                            .foregroundStyle(Color.obCyan)
                        Text(modeBadgeText)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        Spacer()
                        Button {
                            showSimTools = true
                        } label: {
                            Label(isPersian ? "USSD / دایورت" : "SIM / USSD", systemImage: "number.square")
                                .font(.caption.weight(.semibold))
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)

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

                    SimSelectorBar(isPersian: isPersian)
                        .padding(.horizontal, 20)

                    // Dialed number display
                    Text(dialNumber.isEmpty ? (isPersian ? "شماره را وارد کنید" : "Enter Number") : dialNumber)
                        .font(.system(size: dialNumber.isEmpty ? 20 : 32, weight: .semibold, design: .rounded))
                        .foregroundStyle(dialNumber.isEmpty ? .secondary : .primary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .padding(.horizontal, 24)
                        .frame(height: 44)

                    // Keypad
                    VStack(spacing: 12) {
                        ForEach(0..<keys.count, id: \.self) { row in
                            HStack(spacing: 20) {
                                ForEach(keys[row], id: \.0) { digit, letters in
                                    Button {
                                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                        dialNumber.append(digit)
                                        bridge.sendDtmfIfInSipCall(digit)
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
                }
                .padding(.vertical, 10)
            }
            .background(Color.obBg.ignoresSafeArea())
            .navigationTitle(isPersian ? "شماره‌گیر" : "Phone")
            .sheet(isPresented: $showContactPicker) {
                UnifiedContactsSheet(isPersian: isPersian) { number, name in
                    dialNumber = number
                    showContactPicker = false
                    bridge.log("Picked contact: \(name) (\(number))")
                }
            }
            .sheet(isPresented: $showSimTools) {
                SimToolsSheet(isPersian: isPersian)
            }
            .sheet(isPresented: $showHistory) {
                NavigationStack {
                    List(bridge.calls) { call in
                        HStack(spacing: 12) {
                            Image(systemName: callIcon(call.state))
                                .foregroundStyle(callColor(call.state))
                            VStack(alignment: .leading, spacing: 4) {
                                Text(call.displayCaller).font(.headline)
                                HStack(spacing: 6) {
                                    Text(call.number)
                                    Text("•")
                                    Text(Date(timeIntervalSince1970: Double(call.ts) / 1000.0), style: .time)
                                }
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
                        ToolbarItem(placement: .topBarTrailing) {
                            Button {
                                bridge.requestSync()
                            } label: {
                                Image(systemName: "arrow.clockwise")
                            }
                        }
                    }
                }
            }
        }
    }

    private func callIcon(_ state: String) -> String {
        switch state {
        case "MISSED", "REJECTED": return "phone.arrow.down.left"
        case "OUTGOING", "DIALING": return "phone.arrow.up.right"
        default: return "phone.arrow.down.left.fill"
        }
    }

    private func callColor(_ state: String) -> Color {
        switch state {
        case "MISSED": return .red
        case "REJECTED": return .orange
        case "OUTGOING", "DIALING": return .obGreen
        default: return .obCyan
        }
    }

    private var modeBadgeText: String {
        switch bridge.voiceMode {
        case .localWifi: return isPersian ? "CallKit + بلوتوث خالص / وای‌فای HD" : "CallKit + Pure-BLE / Wi-Fi Audio"
        case .sip: return isPersian ? "حالت تماس SIP (\(bridge.sipStatusText))" : "SIP VoIP (\(bridge.sipStatusText))"
        case .remoteOnly: return isPersian ? "فقط کنترل تماس اندروید" : "Android Remote Control"
        }
    }
}

private struct MessagesView: View {
    @EnvironmentObject var bridge: BridgeManager
    @Binding var copiedOtpId: String?
    let isPersian: Bool

    @State private var showComposer = false
    @State private var composeRecipient = ""
    @State private var composeContactName = ""

    private var threads: [SmsConversationThread] {
        SmsConversationThread.group(bridge.messages)
    }

    private var recentOtps: [RelayedSms] {
        Array(bridge.messages.filter { !$0.otp.isEmpty && !$0.isOutgoingMessage }.prefix(5))
    }

    var body: some View {
        NavigationStack {
            Group {
                if threads.isEmpty {
                    VStack(spacing: 16) {
                        ContentUnavailableView(
                            isPersian ? "هنوز پیامکی نیست" : "No Messages Yet",
                            systemImage: "message"
                        )
                        Button {
                            composeRecipient = ""
                            composeContactName = ""
                            showComposer = true
                        } label: {
                            Label(isPersian ? "ارسال پیامک جدید" : "Send New SMS", systemImage: "square.and.pencil")
                                .font(.headline)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.obCyan)
                    }
                } else {
                    ScrollView {
                        LazyVStack(spacing: 12) {
                            if !recentOtps.isEmpty {
                                RecentOtpStrip(otps: recentOtps, copiedOtpId: $copiedOtpId, isPersian: isPersian)
                            }

                            ForEach(threads) { thread in
                                NavigationLink {
                                    SmsConversationDetailView(
                                        threadId: thread.id,
                                        initialTitle: thread.displayTitle,
                                        initialAddress: thread.primaryAddress,
                                        copiedOtpId: $copiedOtpId,
                                        isPersian: isPersian
                                    )
                                } label: {
                                    ThreadSummaryRow(thread: thread, isPersian: isPersian)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(16)
                    }
                }
            }
            .background(Color.obBg.ignoresSafeArea())
            .navigationTitle(isPersian ? "پیامک‌ها" : "Messages")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        bridge.requestSync()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    Button {
                        composeRecipient = ""
                        composeContactName = ""
                        showComposer = true
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                }
            }
            .sheet(isPresented: $showComposer) {
                SmsComposerSheet(
                    initialRecipient: composeRecipient,
                    initialContactName: composeContactName,
                    isPersian: isPersian
                )
            }
        }
    }
}

private struct RecentOtpStrip: View {
    let otps: [RelayedSms]
    @Binding var copiedOtpId: String?
    let isPersian: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(isPersian ? "کدهای تایید اخیر (کپی سریع)" : "Recent OTPs (1-Tap Copy)")
                .font(.caption.bold())
                .foregroundStyle(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(otps) { sms in
                        Button {
                            UIPasteboard.general.string = sms.otp
                            UINotificationFeedbackGenerator().notificationOccurred(.success)
                            copiedOtpId = sms.id
                        } label: {
                            HStack(spacing: 8) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(sms.displaySender)
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                    Text(sms.otp)
                                        .font(.system(.subheadline, design: .monospaced).bold())
                                        .foregroundStyle(Color.obGreen)
                                }
                                Image(systemName: copiedOtpId == sms.id ? "checkmark.circle.fill" : "doc.on.doc")
                                    .foregroundStyle(copiedOtpId == sms.id ? Color.obGreen : Color.obCyan)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(Color.obCard)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(copiedOtpId == sms.id ? Color.obGreen : Color.obBorder, lineWidth: 1)
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}

private struct ThreadSummaryRow: View {
    let thread: SmsConversationThread
    let isPersian: Bool

    private var initials: String {
        let ch = thread.displayTitle.trimmingCharacters(in: .whitespaces).first
        return ch.map { String($0).uppercased() } ?? "#"
    }

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(thread.latestMessage.isOutgoingMessage ? Color.obCyan.opacity(0.2) : Color.obIndigo.opacity(0.25))
                    .frame(width: 44, height: 44)
                Text(initials)
                    .font(.headline.bold())
                    .foregroundStyle(Color.obCyan)
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(thread.displayTitle)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Spacer()
                    Text(Date(timeIntervalSince1970: Double(thread.latestMessage.ts) / 1000.0), style: .time)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                HStack(spacing: 6) {
                    if thread.latestMessage.isOutgoingMessage {
                        Image(systemName: "arrowshape.turn.up.right.fill")
                            .font(.caption2)
                            .foregroundStyle(Color.obCyan)
                    }
                    Text(thread.latestMessage.body)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                    Spacer()
                    if thread.messagesAscending.count > 1 {
                        Text("\(thread.messagesAscending.count)")
                            .font(.caption2.bold())
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(Color.obBorder)
                            .clipShape(Capsule())
                    }
                }
            }
        }
        .padding(14)
        .background(Color.obCard)
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.obBorder, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

private struct SmsConversationDetailView: View {
    @EnvironmentObject var bridge: BridgeManager
    let threadId: String
    let initialTitle: String
    let initialAddress: String
    @Binding var copiedOtpId: String?
    let isPersian: Bool

    @State private var replyText = ""

    private var currentThread: SmsConversationThread? {
        SmsConversationThread.group(bridge.messages).first(where: { $0.id == threadId })
    }

    private var threadMessages: [RelayedSms] {
        currentThread?.messagesAscending ?? []
    }

    private var displayTitle: String {
        currentThread?.displayTitle ?? initialTitle
    }

    private var recipientAddress: String {
        currentThread?.primaryAddress ?? initialAddress
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(threadMessages) { sms in
                            SmsBubbleView(sms: sms, copiedOtpId: $copiedOtpId, isPersian: isPersian)
                                .id(sms.id)
                        }
                    }
                    .padding(16)
                }
                .onAppear {
                    if let lastId = threadMessages.last?.id {
                        proxy.scrollTo(lastId, anchor: .bottom)
                    }
                }
                .onChange(of: threadMessages.count) {
                    if let lastId = threadMessages.last?.id {
                        withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
                    }
                }
            }

            Divider()
                .overlay(Color.obBorder)

            HStack(spacing: 10) {
                TextField(
                    isPersian ? "پاسخ پیامکی..." : "Reply via Android SIM...",
                    text: $replyText,
                    axis: .vertical
                )
                .lineLimit(1...4)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.obCard)
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.obBorder, lineWidth: 1))
                .clipShape(RoundedRectangle(cornerRadius: 18))

                Button {
                    let body = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !body.isEmpty, !recipientAddress.isEmpty else { return }
                    bridge.sendSms(
                        recipient: recipientAddress,
                        body: body,
                        contactName: displayTitle == recipientAddress ? "" : displayTitle
                    )
                    replyText = ""
                } label: {
                    Image(systemName: "paperplane.fill")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(Color.obCyan)
                        .clipShape(Circle())
                }
                .disabled(
                    replyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                    recipientAddress.isEmpty ||
                    bridge.connectionStatus != .connected
                )
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color.obCard.opacity(0.6))
        }
        .background(Color.obBg.ignoresSafeArea())
        .navigationTitle(displayTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if !recipientAddress.isEmpty {
                    Button {
                        bridge.initiateCall(number: recipientAddress, displayName: displayTitle)
                    } label: {
                        Image(systemName: "phone.fill")
                    }
                }
            }
        }
    }
}

private struct SmsBubbleView: View {
    let sms: RelayedSms
    @Binding var copiedOtpId: String?
    let isPersian: Bool

    var body: some View {
        HStack {
            if sms.isOutgoingMessage {
                Spacer(minLength: 48)
            }
            VStack(alignment: sms.isOutgoingMessage ? .trailing : .leading, spacing: 6) {
                Text(sms.body)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .textSelection(.enabled)

                if !sms.isOutgoingMessage && !sms.otp.isEmpty {
                    HStack(spacing: 8) {
                        Text(sms.otp)
                            .font(.system(.callout, design: .monospaced).bold())
                            .foregroundStyle(Color.obGreen)
                        Button {
                            UIPasteboard.general.string = sms.otp
                            UINotificationFeedbackGenerator().notificationOccurred(.success)
                            copiedOtpId = sms.id
                        } label: {
                            Label(
                                copiedOtpId == sms.id ? (isPersian ? "کپی شد" : "Copied") : (isPersian ? "کپی" : "Copy"),
                                systemImage: copiedOtpId == sms.id ? "checkmark" : "doc.on.doc"
                            )
                            .font(.caption.bold())
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(copiedOtpId == sms.id ? .obGreen : .obCyan)
                        .controlSize(.mini)
                    }
                    .padding(8)
                    .background(Color.obGreen.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }

                HStack(spacing: 6) {
                    Text(Date(timeIntervalSince1970: Double(sms.ts) / 1000.0), style: .time)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    if sms.isOutgoingMessage {
                        let status = sms.deliveryStatus ?? "sent"
                        Text(status == "sending" ? "…" : (status == "failed" ? "!" : "✓"))
                            .font(.caption2.bold())
                            .foregroundStyle(status == "failed" ? .red : (status == "sending" ? .orange : Color.obGreen))
                    }
                }
            }
            .padding(12)
            .background(sms.isOutgoingMessage ? Color.obCardOut : Color.obCard)
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(sms.isOutgoingMessage ? Color.obCyan.opacity(0.4) : Color.obBorder, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16))
            if !sms.isOutgoingMessage {
                Spacer(minLength: 48)
            }
        }
    }
}

private struct SmsComposerSheet: View {
    @EnvironmentObject var bridge: BridgeManager
    @Environment(\.dismiss) private var dismiss

    let initialRecipient: String
    let initialContactName: String
    let isPersian: Bool

    @State private var recipient = ""
    @State private var contactName = ""
    @State private var messageBody = ""
    @State private var showContacts = false

    private var hasUnicode: Bool {
        messageBody.unicodeScalars.contains { $0.value > 127 }
    }

    private var partLimit: Int {
        hasUnicode ? 70 : 160
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(isPersian ? "گیرنده (شماره موبایل)" : "Recipient") {
                    HStack {
                        TextField(isPersian ? "مثلاً 09123456789" : "+98912...", text: $recipient)
                            .keyboardType(.phonePad)
                        Button {
                            showContacts = true
                        } label: {
                            Image(systemName: "person.crop.circle.badge.plus")
                                .font(.title3)
                        }
                    }
                    if !contactName.isEmpty {
                        Text(contactName)
                            .font(.caption)
                            .foregroundStyle(Color.obCyan)
                    }
                }

                if let sims = bridge.deviceStatus?.sims, sims.count > 1 {
                    Section(isPersian ? "انتخاب سیم‌کارت ارسال" : "Send From SIM") {
                        Picker(isPersian ? "سیم‌کارت" : "SIM Card", selection: Binding(
                            get: { bridge.selectedSubId ?? sims.first?.subId ?? 0 },
                            set: { bridge.selectedSubId = $0 }
                        )) {
                            ForEach(sims) { sim in
                                Text("SIM \(sim.slot + 1) • \(sim.name)").tag(sim.subId)
                            }
                        }
                        .pickerStyle(.segmented)
                    }
                }

                Section {
                    ZStack(alignment: .topLeading) {
                        if messageBody.isEmpty {
                            Text(isPersian ? "متن پیامک خود را بنویسید..." : "Write your SMS message...")
                                .foregroundStyle(.secondary)
                                .padding(.top, 8)
                                .padding(.leading, 4)
                        }
                        TextEditor(text: $messageBody)
                            .frame(minHeight: 130)
                    }
                } header: {
                    Text(isPersian ? "متن پیامک" : "Message Body")
                } footer: {
                    let count = messageBody.count
                    let parts = max(1, (count + partLimit - 1) / partLimit)
                    Text(isPersian
                         ? "\(count) کاراکتر • \(parts) پیامک (\(hasUnicode ? "فارسی/یونیکد" : "انگلیسی"))"
                         : "\(count) chars • \(parts) SMS part(s)")
                }

                Section {
                    Button {
                        bridge.sendSms(recipient: recipient, body: messageBody, contactName: contactName)
                        dismiss()
                    } label: {
                        Label(isPersian ? "ارسال پیامک با سیم‌کارت اندروید" : "Send via Android SIM", systemImage: "paperplane.fill")
                            .frame(maxWidth: .infinity)
                            .font(.headline)
                    }
                    .disabled(
                        recipient.trimmingCharacters(in: .whitespaces).isEmpty ||
                        messageBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                        bridge.connectionStatus != .connected
                    )
                } footer: {
                    if bridge.connectionStatus != .connected {
                        Text(isPersian ? "ابتدا باید اتصال بلوتوث به گوشی اندروید برقرار باشد." : "Connect to Android via BLE first.")
                            .foregroundStyle(.orange)
                    }
                }
            }
            .navigationTitle(isPersian ? "پیامک جدید" : "New SMS")
            .onAppear {
                recipient = initialRecipient
                contactName = initialContactName
            }
            .sheet(isPresented: $showContacts) {
                UnifiedContactsSheet(isPersian: isPersian) { num, name in
                    recipient = num
                    contactName = name
                    showContacts = false
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(isPersian ? "بستن" : "Cancel") { dismiss() }
                }
            }
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
                        Text(isPersian ? "خودکار (بلوتوث + وای‌فای) + CallKit" : "Auto (Pure-BLE + Wi-Fi) + CallKit").tag(VoiceCallMode.localWifi)
                        Text(isPersian ? "تلفن اینترنتی (SIP)" : "SIP VoIP + CallKit").tag(VoiceCallMode.sip)
                        Text(isPersian ? "فقط کنترل از راه دور" : "Remote Control Only").tag(VoiceCallMode.remoteOnly)
                    }
                    Button {
                        bridge.requestVoiceCompatibilityCheck()
                    } label: {
                        HStack {
                            Label(isPersian ? "تست سخت‌افزار صدای اندروید" : "Check Android Call Audio", systemImage: "waveform.and.magnifyingglass")
                            Spacer()
                            if bridge.isProbingVoice {
                                ProgressView().controlSize(.small)
                            } else if let cap = bridge.voiceCapability {
                                Text(cap.verdict)
                                    .font(.caption)
                                    .foregroundStyle(cap.verdict == "two_way_direct" ? Color.obGreen : .orange)
                            } else {
                                Text(isPersian ? "اجرا" : "Run")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .disabled(bridge.connectionStatus != .connected)

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

private struct SimSelectorBar: View {
    @EnvironmentObject var bridge: BridgeManager
    let isPersian: Bool

    var body: some View {
        if let sims = bridge.deviceStatus?.sims, !sims.isEmpty {
            HStack(spacing: 8) {
                ForEach(sims) { sim in
                    let isSelected = (bridge.selectedSubId ?? sims.first?.subId) == sim.subId
                    Button {
                        bridge.selectedSubId = sim.subId
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "simcard.fill")
                                .font(.caption2)
                            Text("SIM \(sim.slot + 1) • \(sim.name)")
                                .font(.caption.weight(.semibold))
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(isSelected ? Color.obCyan.opacity(0.22) : Color.obCard)
                        .foregroundStyle(isSelected ? Color.obCyan : .secondary)
                        .overlay(
                            Capsule().stroke(isSelected ? Color.obCyan : Color.obBorder, lineWidth: 1)
                        )
                        .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
                Spacer()
            }
        }
    }
}

private struct UnifiedContactsSheet: View {
    @EnvironmentObject var bridge: BridgeManager
    @Environment(\.dismiss) private var dismiss
    let isPersian: Bool
    let onSelect: (String, String) -> Void

    @State private var searchText = ""
    @State private var showIphonePicker = false

    private var filtered: [AndroidContact] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.isEmpty { return bridge.androidContacts }
        return bridge.androidContacts.filter {
            $0.name.localizedCaseInsensitiveContains(q) || $0.number.contains(q)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        showIphonePicker = true
                    } label: {
                        Label(
                            isPersian ? "انتخاب از دفترچه تلفن آیفون" : "Pick from iPhone Contacts",
                            systemImage: "person.crop.circle.badge.checkmark"
                        )
                        .foregroundStyle(Color.obCyan)
                    }
                }

                Section(isPersian ? "مخاطبین سینک‌شده اندروید (\(bridge.androidContacts.count))" : "Synced Android Contacts (\(bridge.androidContacts.count))") {
                    if filtered.isEmpty {
                        HStack {
                            Text(bridge.androidContacts.isEmpty
                                 ? (isPersian ? "مخاطبی همگام‌سازی نشده؛ دکمه بروزرسانی را بزنید." : "No Android contacts synced yet.")
                                 : (isPersian ? "موردی یافت نشد." : "No matches."))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button(isPersian ? "همگام‌سازی" : "Sync") {
                                bridge.requestSync()
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    } else {
                        ForEach(filtered) { contact in
                            Button {
                                onSelect(contact.number, contact.name)
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(contact.name)
                                        .font(.body.weight(.medium))
                                        .foregroundStyle(.primary)
                                    Text(contact.number)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .searchable(text: $searchText, prompt: isPersian ? "جستجوی نام یا شماره..." : "Search name or number...")
            .navigationTitle(isPersian ? "مخاطبین" : "Contacts")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(isPersian ? "بستن" : "Close") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        bridge.requestSync()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .sheet(isPresented: $showIphonePicker) {
                ContactPickerView { num, name in
                    showIphonePicker = false
                    onSelect(num, name)
                }
            }
        }
    }
}

private struct SimToolsSheet: View {
    @EnvironmentObject var bridge: BridgeManager
    @Environment(\.dismiss) private var dismiss
    let isPersian: Bool

    @State private var ussdCode = "*140#"
    @State private var forwardTarget = ""

    private let quickCodes: [(String, String, String)] = [
        ("*140#", "شارژ / موجودی", "Balance"),
        ("*100#", "بسته اینترنت", "Internet"),
        ("*555*1*2#", "ایرانسل (باقی‌مانده)", "Irancell"),
        ("*10*11#", "همراه اول (وضعیت)", "MCI Info")
    ]

    var body: some View {
        NavigationStack {
            Form {
                if let sims = bridge.deviceStatus?.sims, sims.count > 1 {
                    Section(isPersian ? "سیم‌کارت فعال" : "Active SIM") {
                        Picker(isPersian ? "سیم‌کارت" : "SIM Card", selection: Binding(
                            get: { bridge.selectedSubId ?? sims.first?.subId ?? 0 },
                            set: { bridge.selectedSubId = $0 }
                        )) {
                            ForEach(sims) { sim in
                                Text("SIM \(sim.slot + 1) • \(sim.name)").tag(sim.subId)
                            }
                        }
                        .pickerStyle(.segmented)
                    }
                }

                Section(isPersian ? "اجرای کدهای دستوری (USSD)" : "Run USSD Code") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(quickCodes, id: \.0) { code, faLabel, enLabel in
                                Button {
                                    ussdCode = code
                                    bridge.runUssd(code: code)
                                } label: {
                                    Text("\(code) • \(isPersian ? faLabel : enLabel)")
                                        .font(.caption.weight(.semibold))
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }
                        }
                        .padding(.vertical, 2)
                    }

                    HStack {
                        TextField("*140#", text: $ussdCode)
                            .keyboardType(.phonePad)
                        Button(isPersian ? "اجرا" : "Run") {
                            bridge.runUssd(code: ussdCode)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(ussdCode.trimmingCharacters(in: .whitespaces).isEmpty || bridge.connectionStatus != .connected)
                    }

                    if bridge.isUssdRunning {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text(isPersian ? "در حال دریافت پاسخ از اپراتور..." : "Waiting for carrier USSD reply...")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else if let res = bridge.lastUssdResult {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(res.code).font(.caption.bold())
                                Spacer()
                                Image(systemName: res.success ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                                    .foregroundStyle(res.success ? Color.obGreen : .orange)
                            }
                            Text(res.message)
                                .font(.subheadline)
                                .textSelection(.enabled)
                        }
                        .padding(.vertical, 4)
                    }
                }

                Section {
                    TextField(isPersian ? "شماره مقصد یا خط SIP/تلفن" : "Forward target number", text: $forwardTarget)
                        .keyboardType(.phonePad)
                    HStack {
                        Button(isPersian ? "فعال‌سازی دایورت (*21*)" : "Enable Forward (*21*)") {
                            bridge.setRemoteCallForwarding(enable: true, target: forwardTarget)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(forwardTarget.trimmingCharacters(in: .whitespaces).isEmpty || bridge.connectionStatus != .connected)

                        Button(isPersian ? "لغو دایورت (#21#)" : "Disable (#21#)") {
                            bridge.setRemoteCallForwarding(enable: false, target: "")
                        }
                        .buttonStyle(.bordered)
                        .disabled(bridge.connectionStatus != .connected)
                    }
                } header: {
                    Text(isPersian ? "دایورت تماس از راه دور (*21*)" : "Remote Call Forwarding (*21*)")
                } footer: {
                    Text(isPersian
                         ? "برای مکالمه کاملاً شفاف بدون بیرون آوردن گوشی اندروید از کیف، تماس‌ها را روی شماره SIP خود دایورت کنید."
                         : "Forward cellular calls to your SIP virtual number for crystal-clear in-bag audio.")
                }
            }
            .navigationTitle(isPersian ? "ابزار سیم‌کارت و USSD" : "SIM & USSD Tools")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(isPersian ? "بستن" : "Close") { dismiss() }
                }
            }
        }
    }
}
