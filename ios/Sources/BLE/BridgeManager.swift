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
    @Published var appLanguage: String {
        didSet {
            UserDefaults.standard.set(appLanguage, forKey: Self.languageKey)
        }
    }

    private static let peerNameKey = "paired_android_name"
    private static let peerPeripheralIdKey = "paired_peripheral_uuid"
    private static let deviceIdKey = "ios_device_uuid"
    private static let languageKey = "app_language"
    private static let keychainPrivateKeyAccount = "ios_p256_private_raw"
    private static let keychainSharedKeyAccount = "session_aes_key"

    private var centralManager: CBCentralManager?
    private var connectedPeripheral: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private var txCharacteristic: CBCharacteristic?

    private var sharedKey: SymmetricKey?
    private var pendingPairHello: PairHelloPayload?
    private let decoder = FrameCodec.StreamDecoder()
    private let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    override init() {
        self.appLanguage = UserDefaults.standard.string(forKey: Self.languageKey) ?? "system"
        super.init()

        loadStoredSession()
        NotificationManager.shared.onCallActionFromNotification = { [weak self] action, number in
            self?.sendCallAction(action: action, number: number)
        }

        let options: [String: Any] = [
            CBCentralManagerOptionRestoreIdentifierKey: BridgeConstants.restoreIdentifier
        ]
        self.centralManager = CBCentralManager(delegate: self, queue: nil, options: options)
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
        connectedPeripheral = nil
        rxCharacteristic = nil
        txCharacteristic = nil
        connectionStatus = .notPaired
        log("Unpaired and cleared session keys.")
    }

    func sendDialRequest(number: String) {
        let trimmed = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let dict: [String: Any] = [
            "type": "DIAL_REQUEST",
            "number": trimmed
        ]
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
            case "PONG":
                self.log("Received PONG")
            case "DEVICE_STATUS":
                if let status = try? JSONDecoder().decode(DeviceStatus.self, from: plain) {
                    self.deviceStatus = status
                }
            case "SMS_RECEIVED":
                if let sms = try? JSONDecoder().decode(RelayedSms.self, from: plain) {
                    self.messages.insert(sms, at: 0)
                    if self.messages.count > 100 { self.messages.removeLast() }
                    NotificationManager.shared.notifySms(sms)
                    self.log("SMS from \(sms.displaySender) (OTP=\(sms.otp.isEmpty ? "none" : sms.otp))")
                }
            case "CALL_STATE":
                if let call = try? JSONDecoder().decode(RelayedCall.self, from: plain) {
                    if call.state == "RINGING" {
                        self.activeCall = call
                        self.calls.insert(call, at: 0)
                        if self.calls.count > 100 { self.calls.removeLast() }
                        NotificationManager.shared.notifyIncomingCall(call)
                    } else if call.state == "IDLE" {
                        self.activeCall = nil
                    } else if call.state == "OFFHOOK" {
                        self.activeCall = call
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
        decoder.reset()
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
