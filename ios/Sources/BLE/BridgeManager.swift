import Foundation
import CoreBluetooth
import CryptoKit
import UIKit

enum ConnectionStatus: String {
    case notPaired
    case scanning
    case connected
    case disconnected
}

final class BridgeManager: NSObject, ObservableObject {
    static let shared = BridgeManager()

    @Published var connectionStatus: ConnectionStatus = .notPaired
    @Published var pairedAndroidName: String = ""
    @Published var deviceStatus: DeviceStatus?
    @Published var messages: [RelayedSms] = []
    @Published var calls: [RelayedCall] = []
    @Published var activeCall: RelayedCall?
    @Published var logs: [String] = []
    @Published var sipStatusText: String = "Unregistered"
    @Published var audioLinkState: LocalAudioState = .idle
    @Published var isCallMuted: Bool = false
    @Published var isSpeakerOn: Bool = false
    @Published var androidContacts: [AndroidContact] = []
    @Published var selectedSubId: Int?
    @Published var lastUssdResult: UssdResponse?
    @Published var isUssdRunning: Bool = false
    @Published var audioPathDescription: String = ""
    @Published var isAudioDirect: Bool = false
    @Published var isSyncing: Bool = false
    @Published var androidSpeakerOn: Bool = false

    @Published var appLanguage: String {
        didSet {
            UserDefaults.standard.set(appLanguage, forKey: Self.languageKey)
        }
    }
    @Published var voiceMode: VoiceCallMode {
        didSet {
            UserDefaults.standard.set(voiceMode.rawValue, forKey: Self.voiceModeKey)
        }
    }
    @Published var sipConfig: SipAccountConfig {
        didSet {
            if let data = try? JSONEncoder().encode(sipConfig) {
                KeychainHelper.save(data, account: Self.keychainSipAccount)
            }
        }
    }

    private static let peerNameKey = "paired_android_name"
    private static let peerPeripheralIdKey = "paired_peripheral_uuid"
    private static let deviceIdKey = "ios_device_uuid"
    private static let languageKey = "app_language"
    private static let voiceModeKey = "voice_call_mode"
    private static let keychainPrivateKeyAccount = "ios_p256_private_raw"
    private static let keychainSharedKeyAccount = "session_aes_key"
    private static let keychainSipAccount = "sip_config_json"

    private var centralManager: CBCentralManager?
    private var connectedPeripheral: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private var txCharacteristic: CBCharacteristic?

    private var sharedKey: SymmetricKey?
    private var pendingPairHello: PairHelloPayload?
    private let decoder = FrameCodec.StreamDecoder()
    private let localAudio = LocalAudioStreamer()
    private let sipClient = SipClient()
    private let callKit = CallKitManager.shared
    private var isSipCallActive = false
    private var hasPerformedInitialSync = false

    private let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    override init() {
        self.appLanguage = UserDefaults.standard.string(forKey: Self.languageKey) ?? "system"
        let savedVoice = UserDefaults.standard.string(forKey: Self.voiceModeKey) ?? VoiceCallMode.localWifi.rawValue
        self.voiceMode = VoiceCallMode(rawValue: savedVoice) ?? .localWifi
        if let sipData = KeychainHelper.load(account: Self.keychainSipAccount),
           let cfg = try? JSONDecoder().decode(SipAccountConfig.self, from: sipData) {
            self.sipConfig = cfg
        } else {
            self.sipConfig = SipAccountConfig()
        }
        super.init()

        setupAudioAndCallKitBindings()
        loadStoredSession()

        NotificationManager.shared.onCallActionFromNotification = { [weak self] action, number in
            self?.sendCallAction(action: action, number: number)
        }

        let options: [String: Any] = [
            CBCentralManagerOptionRestoreIdentifierKey: BridgeConstants.restoreIdentifier
        ]
        self.centralManager = CBCentralManager(delegate: self, queue: nil, options: options)

        if voiceMode == .sip && sipConfig.isConfigured {
            sipClient.configureAndRegister(sipConfig)
        }
    }

    private func setupAudioAndCallKitBindings() {
        localAudio.onLog = { [weak self] msg in self?.log(msg) }
        localAudio.onStateChanged = { [weak self] st in
            DispatchQueue.main.async { self?.audioLinkState = st }
        }
        sipClient.onLog = { [weak self] msg in self?.log(msg) }
        callKit.onLog = { [weak self] msg in self?.log(msg) }

        sipClient.onStateChanged = { [weak self] _, text in
            DispatchQueue.main.async { self?.sipStatusText = text }
        }
        sipClient.onIncomingCall = { [weak self] handle, name in
            guard let self else { return }
            self.isSipCallActive = true
            self.activeCall = RelayedCall(
                id: UUID().uuidString,
                state: "RINGING",
                number: handle,
                contactName: name,
                wifiIp: nil,
                audioPort: nil,
                ts: Int64(Date().timeIntervalSince1970 * 1000)
            )
            self.callKit.reportIncomingCall(handle: handle, displayName: name)
        }
        sipClient.onCallConnected = { [weak self] in
            self?.callKit.reportOutgoingConnected()
            if var call = self?.activeCall {
                call = RelayedCall(
                    id: call.id, state: "OFFHOOK", number: call.number,
                    contactName: call.contactName, wifiIp: nil, audioPort: nil, ts: call.ts
                )
                DispatchQueue.main.async { self?.activeCall = call }
            }
        }
        sipClient.onCallEnded = { [weak self] in
            self?.isSipCallActive = false
            self?.callKit.reportRemoteEnded()
            DispatchQueue.main.async {
                self?.activeCall = nil
                self?.isCallMuted = false
            }
        }

        callKit.onAudioSessionActivated = { [weak self] in
            guard let self else { return }
            self.localAudio.onCallKitAudioActivated()
            self.sipClient.onCallKitAudioActivated()
        }
        callKit.onAudioSessionDeactivated = { [weak self] in
            guard let self else { return }
            self.localAudio.stop()
            DispatchQueue.main.async {
                self.isCallMuted = false
                self.isSpeakerOn = false
            }
        }
        callKit.onMuteChanged = { [weak self] muted in
            guard let self else { return }
            self.localAudio.isMuted = muted
            self.sipClient.isMuted = muted
            DispatchQueue.main.async { self.isCallMuted = muted }
            self.sendEncryptedDict([
                "type": "CALL_ACTION",
                "action": muted ? "MUTE" : "UNMUTE",
                "number": self.activeCall?.number ?? ""
            ])
        }

        callKit.onAnswerCall = { [weak self] _ in
            guard let self else { return }
            if self.isSipCallActive || (self.voiceMode == .sip && self.sipConfig.isConfigured && self.activeCall?.wifiIp == nil) {
                self.sipClient.answerIncomingCall(audioAlreadyActivated: self.callKit.isAudioSessionActive)
            } else {
                let num = self.activeCall?.number ?? ""
                self.sendCallAction(action: "ACCEPT", number: num)
                self.startLocalAudioIfAvailable()
            }
        }
        callKit.onEndCall = { [weak self] _ in
            guard let self else { return }
            self.localAudio.stop()
            if self.isSipCallActive || self.voiceMode == .sip {
                self.sipClient.hangup()
                self.isSipCallActive = false
            }
            if let active = self.activeCall {
                self.sendCallAction(action: "REJECT", number: active.number)
            }
            DispatchQueue.main.async {
                self.activeCall = nil
                self.isCallMuted = false
            }
        }
        callKit.onStartOutgoingCall = { [weak self] _, number in
            guard let self else { return }
            if self.voiceMode == .sip && self.sipConfig.isConfigured {
                self.isSipCallActive = true
                self.sipClient.makeCall(to: number, audioAlreadyActivated: self.callKit.isAudioSessionActive)
            } else {
                self.sendDialRequest(number: number)
                if self.voiceMode == .localWifi {
                    // Start connecting immediately; LocalAudioStreamer auto-retries until Android binds 54810
                    self.startLocalAudioIfAvailable()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                        if self.activeCall != nil {
                            self.callKit.reportOutgoingConnected()
                        }
                    }
                }
            }
        }
    }

    func registerSip() {
        sipClient.configureAndRegister(sipConfig)
    }

    func initiateCall(number: String, displayName: String? = nil) {
        let trimmed = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let name = displayName?.isEmpty == false ? displayName! : trimmed
        let call = RelayedCall(
            id: UUID().uuidString,
            state: "DIALING",
            number: trimmed,
            contactName: name,
            wifiIp: deviceStatus?.wifiIp,
            audioPort: deviceStatus?.audioPort,
            ts: Int64(Date().timeIntervalSince1970 * 1000)
        )
        activeCall = call
        calls.insert(call, at: 0)
        if calls.count > 100 { calls.removeLast() }

        if voiceMode == .remoteOnly {
            sendDialRequest(number: trimmed)
        } else {
            callKit.startOutgoingCall(handle: trimmed, displayName: name)
        }
    }

    func answerActiveCall() {
        if callKit.activeCallUUID != nil {
            callKit.answerActiveCall()
        } else if let num = activeCall?.number {
            sendCallAction(action: "ACCEPT", number: num)
            startLocalAudioIfAvailable()
        }
    }

    func endCurrentCall() {
        if callKit.activeCallUUID != nil {
            callKit.endActiveCall()
        } else {
            localAudio.stop()
            if isSipCallActive { sipClient.hangup() }
            if let num = activeCall?.number {
                sendCallAction(action: "REJECT", number: num)
            }
            activeCall = nil
            audioPathDescription = ""
            isAudioDirect = false
            androidSpeakerOn = false
        }
    }

    func toggleMute() {
        let next = !isCallMuted
        if callKit.activeCallUUID != nil {
            callKit.setMuted(next)
        } else {
            localAudio.isMuted = next
            sipClient.isMuted = next
            isCallMuted = next
            sendEncryptedDict([
                "type": "CALL_ACTION",
                "action": next ? "MUTE" : "UNMUTE",
                "number": activeCall?.number ?? ""
            ])
        }
    }

    func toggleSpeaker() {
        let next = !isSpeakerOn
        isSpeakerOn = next
        localAudio.setSpeakerEnabled(next)
    }

    func toggleAndroidSpeaker() {
        let next = !androidSpeakerOn
        androidSpeakerOn = next
        sendEncryptedDict([
            "type": "CALL_ACTION",
            "action": next ? "SPEAKER_ON" : "SPEAKER_OFF",
            "number": activeCall?.number ?? ""
        ])
    }

    func sendDtmfIfInSipCall(_ digit: String) {
        if isSipCallActive {
            sipClient.sendDtmf(digit)
        }
    }

    func sendSms(recipient: String, body: String, contactName: String = "") {
        let cleanRecipient = recipient.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanBody = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanRecipient.isEmpty, !cleanBody.isEmpty else { return }

        let id = UUID().uuidString
        let outgoing = RelayedSms(
            id: id,
            sender: cleanRecipient,
            contactName: contactName,
            body: cleanBody,
            otp: "",
            ts: Int64(Date().timeIntervalSince1970 * 1000),
            isOutgoing: true,
            deliveryStatus: "sending",
            errorDetail: nil
        )
        messages.insert(outgoing, at: 0)
        if messages.count > 200 { messages.removeLast() }

        var dict: [String: Any] = [
            "type": "SEND_SMS",
            "id": id,
            "recipient": cleanRecipient,
            "body": cleanBody
        ]
        if let subId = selectedSubId {
            dict["subId"] = subId
        }
        sendEncryptedDict(dict)
        log("Dispatched SEND_SMS to \(cleanRecipient)")
    }

    func requestSync(scope: String = "all") {
        guard connectionStatus == .connected else { return }
        isSyncing = true
        sendEncryptedDict([
            "type": "SYNC_REQUEST",
            "scope": scope
        ])
        log("Requested sync (\(scope)) from Android")
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { [weak self] in
            self?.isSyncing = false
        }
    }

    func runUssd(code: String) {
        let clean = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        isUssdRunning = true
        lastUssdResult = nil
        var dict: [String: Any] = [
            "type": "RUN_USSD",
            "code": clean
        ]
        if let subId = selectedSubId {
            dict["subId"] = subId
        }
        sendEncryptedDict(dict)
        log("Sent RUN_USSD \(clean)")
        DispatchQueue.main.asyncAfter(deadline: .now() + 18.0) { [weak self] in
            self?.isUssdRunning = false
        }
    }

    func setRemoteCallForwarding(enable: Bool, target: String) {
        let cleanTarget = target.trimmingCharacters(in: .whitespacesAndNewlines)
        if enable && cleanTarget.isEmpty { return }
        var dict: [String: Any] = [
            "type": "CALL_FORWARD",
            "enable": enable,
            "target": cleanTarget
        ]
        if let subId = selectedSubId {
            dict["subId"] = subId
        }
        sendEncryptedDict(dict)
        log("Sent CALL_FORWARD enable=\(enable) target=\(cleanTarget)")
    }

    private func startLocalAudioIfAvailable() {
        guard voiceMode == .localWifi else { return }
        if audioLinkState == .connected || audioLinkState == .connecting {
            return
        }
        let host = activeCall?.wifiIp ?? deviceStatus?.wifiIp ?? ""
        let port = activeCall?.audioPort ?? deviceStatus?.audioPort ?? 54810
        guard !host.isEmpty else {
            log("Local audio waiting: enable Hotspot or same Wi-Fi on both phones")
            return
        }
        localAudio.start(host: host, port: port, audioAlreadyActivated: callKit.isAudioSessionActive)
    }

    private func loadStoredSession() {
        if let keyData = KeychainHelper.load(account: Self.keychainSharedKeyAccount), keyData.count == 32 {
            sharedKey = SymmetricKey(data: keyData)
            pairedAndroidName = UserDefaults.standard.string(forKey: Self.peerNameKey) ?? "Android"
            connectionStatus = .disconnected
            log("Loaded existing E2EE session for \(pairedAndroidName)")
        } else {
            connectionStatus = .notPaired
        }
    }

    private func getOrCreateIosDeviceId() -> String {
        if let existing = UserDefaults.standard.string(forKey: Self.deviceIdKey) {
            return existing
        }
        let fresh = UUID().uuidString
        UserDefaults.standard.set(fresh, forKey: Self.deviceIdKey)
        return fresh
    }

    private func getOrCreatePrivateKey() -> P256.KeyAgreement.PrivateKey {
        if let raw = KeychainHelper.load(account: Self.keychainPrivateKeyAccount),
           let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: raw) {
            return key
        }
        let fresh = BridgeCrypto.generatePrivateKey()
        KeychainHelper.save(fresh.rawRepresentation, account: Self.keychainPrivateKeyAccount)
        return fresh
    }

    func pairFromQrString(_ qrString: String) -> Bool {
        guard let data = qrString.data(using: .utf8),
              let qr = try? JSONDecoder().decode(QrPairingPayload.self, from: data),
              let peerPubData = Data(base64Encoded: qr.pub) else {
            log("Invalid QR pairing payload")
            return false
        }

        do {
            let privKey = getOrCreatePrivateKey()
            let derived = try BridgeCrypto.deriveSessionKey(privateKey: privKey, peerPublicX963: peerPubData)
            let derivedData = derived.withUnsafeBytes { Data($0) }
            KeychainHelper.save(derivedData, account: Self.keychainSharedKeyAccount)
            self.sharedKey = derived

            UserDefaults.standard.set(qr.name, forKey: Self.peerNameKey)
            self.pairedAndroidName = qr.name

            let pubB64 = privKey.publicKey.x963Representation.base64EncodedString()
            self.pendingPairHello = PairHelloPayload(
                type: "PAIR_HELLO",
                v: BridgeConstants.protocolVersion,
                deviceId: getOrCreateIosDeviceId(),
                deviceName: UIDevice.current.name,
                pub: pubB64
            )
            log("QR scanned for \(qr.name). Starting BLE scan...")
            startScan()
            return true
        } catch {
            log("Pairing crypto error: \(error.localizedDescription)")
            return false
        }
    }

    func startScan() {
        guard let central = centralManager else { return }
        guard central.state == .poweredOn else {
            log("Bluetooth not poweredOn yet (state=\(central.state.rawValue))")
            return
        }
        if let savedUuidStr = UserDefaults.standard.string(forKey: Self.peerPeripheralIdKey),
           let savedUuid = UUID(uuidString: savedUuidStr),
           let known = central.retrievePeripherals(withIdentifiers: [savedUuid]).first {
            log("Attempting direct reconnect to known peripheral \(savedUuidStr)")
            connectedPeripheral = known
            known.delegate = self
            central.connect(known, options: nil)
        }
        connectionStatus = .scanning
        central.scanForPeripherals(
            withServices: [BridgeConstants.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        log("Scanning for OpenBridge BLE service...")
    }

    func unpair() {
        localAudio.stop()
        if let p = connectedPeripheral {
            centralManager?.cancelPeripheralConnection(p)
        }
        centralManager?.stopScan()
        KeychainHelper.delete(account: Self.keychainSharedKeyAccount)
        UserDefaults.standard.removeObject(forKey: Self.peerNameKey)
        UserDefaults.standard.removeObject(forKey: Self.peerPeripheralIdKey)
        sharedKey = nil
        pendingPairHello = nil
        pairedAndroidName = ""
        deviceStatus = nil
        androidContacts = []
        selectedSubId = nil
        lastUssdResult = nil
        isUssdRunning = false
        audioPathDescription = ""
        isAudioDirect = false
        isSyncing = false
        androidSpeakerOn = false
        hasPerformedInitialSync = false
        connectedPeripheral = nil
        rxCharacteristic = nil
        txCharacteristic = nil
        connectionStatus = .notPaired
        log("Unpaired and cleared session keys.")
    }

    func sendDialRequest(number: String) {
        let trimmed = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var dict: [String: Any] = [
            "type": "DIAL_REQUEST",
            "number": trimmed,
            "mode": voiceMode.rawValue
        ]
        if let subId = selectedSubId {
            dict["subId"] = subId
        }
        sendEncryptedDict(dict)
        log("Sent DIAL_REQUEST to \(trimmed)")
    }

    func sendCallAction(action: String, number: String) {
        let dict: [String: Any] = [
            "type": "CALL_ACTION",
            "action": action,
            "number": number,
            "smsBody": "I can't talk right now, I'll call you back soon."
        ]
        sendEncryptedDict(dict)
        if action == "REJECT" || action == "QUICK_SMS" {
            localAudio.stop()
            callKit.reportRemoteEnded()
            DispatchQueue.main.async {
                self.activeCall = nil
            }
        }
        log("Sent CALL_ACTION=\(action)")
    }

    func sendPing() {
        let dict: [String: Any] = [
            "type": "PING",
            "ts": Int64(Date().timeIntervalSince1970 * 1000)
        ]
        sendEncryptedDict(dict)
    }

    func clearLogs() {
        logs.removeAll()
    }

    private func sendEncryptedDict(_ dict: [String: Any]) {
        guard let key = sharedKey,
              let plainData = try? JSONSerialization.data(withJSONObject: dict) else {
            log("Cannot send: missing session key")
            return
        }
        do {
            let (nonce, cipher) = try BridgeCrypto.encrypt(key: key, plaintext: plainData)
            let envelope = EncryptedEnvelope(
                e: 1,
                n: nonce.base64EncodedString(),
                c: cipher.base64EncodedString()
            )
            let envData = try JSONEncoder().encode(envelope)
            writeFrame(envData)
        } catch {
            log("Encrypt send failed: \(error.localizedDescription)")
        }
    }

    private func sendPairHelloIfNeeded() {
        guard let hello = pendingPairHello,
              let data = try? JSONEncoder().encode(hello) else {
            return
        }
        writeFrame(data)
        pendingPairHello = nil
        log("Sent PAIR_HELLO to Android")
    }

    private func writeFrame(_ payload: Data) {
        guard let peripheral = connectedPeripheral,
              let rx = rxCharacteristic else {
            log("BLE write skipped: not connected")
            return
        }
        let frame = FrameCodec.encode(payload)
        let mtu = max(20, peripheral.maximumWriteValueLength(for: .withResponse))
        let writeType: CBCharacteristicWriteType = rx.properties.contains(.write) ? .withResponse : .withoutResponse
        for chunk in FrameCodec.chunk(frame, maxChunkSize: mtu) {
            peripheral.writeValue(chunk, for: rx, type: writeType)
        }
    }

    private func handleIncomingFrame(_ data: Data) {
        guard let jsonObj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            log("Invalid JSON frame")
            return
        }
        if let encrypted = jsonObj["e"] as? Int, encrypted == 1 {
            guard let key = sharedKey,
                  let nB64 = jsonObj["n"] as? String,
                  let cB64 = jsonObj["c"] as? String,
                  let nonce = Data(base64Encoded: nB64),
                  let cipher = Data(base64Encoded: cB64) else {
                log("Cannot decrypt envelope: missing key or fields")
                return
            }
            do {
                let plain = try BridgeCrypto.decrypt(key: key, nonce: nonce, cipherWithTag: cipher)
                handleDecryptedData(plain)
            } catch {
                log("Decrypt error: \(error.localizedDescription)")
            }
        }
    }

    private func handleDecryptedData(_ plain: Data) {
        guard let obj = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let type = obj["type"] as? String else {
            return
        }

        DispatchQueue.main.async {
            switch type {
            case "PAIR_ACK":
                if let name = obj["deviceName"] as? String {
                    self.pairedAndroidName = name
                    UserDefaults.standard.set(name, forKey: Self.peerNameKey)
                }
                self.log("Received PAIR_ACK — E2EE verified!")
                if !self.hasPerformedInitialSync {
                    self.hasPerformedInitialSync = true
                    self.requestSync()
                }
            case "PONG":
                self.log("Received PONG")
                if !self.hasPerformedInitialSync {
                    self.hasPerformedInitialSync = true
                    self.requestSync()
                }
            case "DEVICE_STATUS":
                if let status = try? JSONDecoder().decode(DeviceStatus.self, from: plain) {
                    self.deviceStatus = status
                    if let sims = status.sims, !sims.isEmpty {
                        if self.selectedSubId == nil || !sims.contains(where: { $0.subId == self.selectedSubId }) {
                            self.selectedSubId = sims.first?.subId
                        }
                    }
                }
            case "SMS_RECEIVED":
                if let sms = try? JSONDecoder().decode(RelayedSms.self, from: plain) {
                    if !self.messages.contains(where: { $0.id == sms.id }) {
                        self.messages.insert(sms, at: 0)
                        if self.messages.count > 200 { self.messages.removeLast() }
                    }
                    NotificationManager.shared.notifySms(sms)
                    self.log("SMS from \(sms.displaySender) (OTP=\(sms.otp.isEmpty ? "none" : sms.otp))")
                }
            case "SMS_HISTORY":
                if let batch = try? JSONDecoder().decode(SmsHistoryBatch.self, from: plain) {
                    var existing = Dictionary(uniqueKeysWithValues: self.messages.map { ($0.id, $0) })
                    for item in batch.items where existing[item.id] == nil {
                        existing[item.id] = item
                    }
                    self.messages = Array(existing.values.sorted { $0.ts > $1.ts }.prefix(200))
                }
            case "CALL_HISTORY":
                if let batch = try? JSONDecoder().decode(CallHistoryBatch.self, from: plain) {
                    var existing = Dictionary(uniqueKeysWithValues: self.calls.map { ($0.id, $0) })
                    for item in batch.items where existing[item.id] == nil {
                        existing[item.id] = item
                    }
                    self.calls = Array(existing.values.sorted { $0.ts > $1.ts }.prefix(150))
                }
            case "CONTACTS_SYNC":
                if let batch = try? JSONDecoder().decode(ContactsSyncBatch.self, from: plain) {
                    var existing = Dictionary(uniqueKeysWithValues: self.androidContacts.map { ($0.id, $0) })
                    for c in batch.items {
                        existing[c.id] = c
                    }
                    self.androidContacts = existing.values.sorted {
                        $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
                    }
                }
            case "USSD_RESPONSE":
                if let resp = try? JSONDecoder().decode(UssdResponse.self, from: plain) {
                    self.lastUssdResult = resp
                    self.isUssdRunning = false
                    self.log("USSD \(resp.code): \(resp.message)")
                }
            case "AUDIO_STATUS":
                if let info = try? JSONDecoder().decode(AudioStatusInfo.self, from: plain) {
                    self.isAudioDirect = info.direct
                    self.audioPathDescription = info.source
                    self.log("Audio path: direct=\(info.direct) (\(info.source))")
                }
            case "SMS_SENT":
                if let ack = try? JSONDecoder().decode(SmsSentAck.self, from: plain) {
                    if let idx = self.messages.firstIndex(where: { $0.id == ack.id }) {
                        self.messages[idx].deliveryStatus = ack.success ? "sent" : "failed"
                        self.messages[idx].errorDetail = ack.error
                    }
                    self.log("SMS delivery to \(ack.recipient): \(ack.success ? "sent" : (ack.error ?? "failed"))")
                }
            case "CALL_STATE":
                if let call = try? JSONDecoder().decode(RelayedCall.self, from: plain) {
                    if call.state == "RINGING" {
                        self.activeCall = call
                        self.calls.insert(call, at: 0)
                        if self.calls.count > 100 { self.calls.removeLast() }
                        if self.voiceMode == .localWifi {
                            self.callKit.reportIncomingCall(handle: call.number, displayName: call.displayCaller)
                        } else {
                            NotificationManager.shared.notifyIncomingCall(call)
                        }
                    } else if call.state == "IDLE" {
                        if !self.isSipCallActive {
                            self.activeCall = nil
                            self.audioPathDescription = ""
                            self.isAudioDirect = false
                            self.androidSpeakerOn = false
                            self.localAudio.stop()
                            self.callKit.reportRemoteEnded()
                        }
                    } else if call.state == "OFFHOOK" {
                        // Preserve known number/contactName if Android omitted it on second broadcast
                        let existing = self.activeCall
                        let merged = RelayedCall(
                            id: call.id,
                            state: "OFFHOOK",
                            number: call.number.isEmpty ? (existing?.number ?? "") : call.number,
                            contactName: call.contactName.isEmpty ? (existing?.contactName ?? "") : call.contactName,
                            wifiIp: call.wifiIp ?? existing?.wifiIp,
                            audioPort: call.audioPort ?? existing?.audioPort,
                            ts: call.ts
                        )
                        self.activeCall = merged
                        if self.voiceMode == .localWifi {
                            self.callKit.reportOutgoingConnected()
                            self.startLocalAudioIfAvailable()
                        }
                    }
                    self.log("CALL_STATE: \(call.state) (\(call.displayCaller))")
                }
            default:
                self.log("Unhandled message type: \(type)")
            }
        }
    }

    func log(_ message: String) {
        let entry = "[\(timeFormatter.string(from: Date()))] \(message)"
        DispatchQueue.main.async {
            self.logs.insert(entry, at: 0)
            if self.logs.count > 250 {
                self.logs.removeLast()
            }
        }
    }
}

extension BridgeManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            log("CoreBluetooth poweredOn")
            if sharedKey != nil {
                startScan()
            }
        case .poweredOff:
            log("Bluetooth is powered off")
            if sharedKey != nil { connectionStatus = .disconnected }
        default:
            log("Bluetooth state changed: \(central.state.rawValue)")
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
           let first = peripherals.first {
            connectedPeripheral = first
            first.delegate = self
            log("Restored BLE peripheral from background state")
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        central.stopScan()
        connectedPeripheral = peripheral
        peripheral.delegate = self
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: Self.peerPeripheralIdKey)
        log("Discovered Android peripheral: \(peripheral.name ?? peripheral.identifier.uuidString)")
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        decoder.reset()
        connectionStatus = .connected
        log("Connected to Android peripheral")
        peripheral.discoverServices([BridgeConstants.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        rxCharacteristic = nil
        txCharacteristic = nil
        hasPerformedInitialSync = false
        decoder.reset()
        localAudio.stop()
        if sharedKey != nil {
            connectionStatus = .disconnected
            log("Disconnected (\(error?.localizedDescription ?? "clean")). Re-scanning...")
            startScan()
        }
    }
}

extension BridgeManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == BridgeConstants.serviceUUID {
            peripheral.discoverCharacteristics(
                [BridgeConstants.rxCharUUID, BridgeConstants.txCharUUID],
                for: service
            )
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }
        for char in chars {
            if char.uuid == BridgeConstants.rxCharUUID {
                rxCharacteristic = char
            } else if char.uuid == BridgeConstants.txCharUUID {
                txCharacteristic = char
                peripheral.setNotifyValue(true, for: char)
            }
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if characteristic.uuid == BridgeConstants.txCharUUID && characteristic.isNotifying {
            log("Subscribed to TX notifications")
            sendPairHelloIfNeeded()
            sendPing()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == BridgeConstants.txCharUUID,
              let value = characteristic.value else {
            return
        }
        let frames = decoder.append(value)
        for frame in frames {
            handleIncomingFrame(frame)
        }
    }
}
