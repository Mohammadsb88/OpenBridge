import Foundation

struct QrPairingPayload: Codable {
    let v: Int
    let id: String
    let name: String
    let pub: String
    let svc: String
}

struct EncryptedEnvelope: Codable {
    let e: Int
    let n: String
    let c: String
}

struct PairHelloPayload: Codable {
    let type: String
    let v: Int
    let deviceId: String
    let deviceName: String
    let pub: String
}

struct SimCardInfo: Codable, Identifiable, Equatable {
    let slot: Int
    let subId: Int
    let name: String

    var id: Int { subId }
}

struct DeviceStatus: Codable, Equatable {
    let battery: Int
    let charging: Bool
    let signal: Int
    let networkType: String
    let operatorName: String
    let wifiIp: String?
    let audioPort: Int?
    let sims: [SimCardInfo]?
    let ts: Int64

    enum CodingKeys: String, CodingKey {
        case battery
        case charging
        case signal
        case networkType
        case operatorName = "operator"
        case wifiIp
        case audioPort
        case sims
        case ts
    }
}

struct AndroidContact: Identifiable, Codable, Equatable {
    let name: String
    let number: String

    var id: String { "\(name)|\(number)" }
}

struct UssdResponse: Codable, Equatable {
    let code: String
    let success: Bool
    let message: String
    let ts: Int64
}

struct AudioStatusInfo: Codable, Equatable {
    let direct: Bool
    let source: String
    let transport: String?
    let ts: Int64
}

struct VoiceCapabilityInfo: Codable, Equatable {
    let verdict: String
    let mode: String
    let downlinkOk: Bool
    let uplinkOk: Bool
    let downlinkSource: String
    let uplinkLabel: String
    let summary: String
    let ts: Int64
}

struct SmsHistoryBatch: Codable {
    let items: [RelayedSms]
}

struct CallHistoryBatch: Codable {
    let items: [RelayedCall]
}

struct ContactsSyncBatch: Codable {
    let items: [AndroidContact]
}

struct RelayedSms: Identifiable, Codable, Equatable {
    let id: String
    let sender: String
    let contactName: String
    let body: String
    let otp: String
    let ts: Int64
    var isOutgoing: Bool?
    var deliveryStatus: String?
    var errorDetail: String?

    var isOutgoingMessage: Bool {
        isOutgoing == true
    }

    var displaySender: String {
        contactName.isEmpty ? sender : contactName
    }
}

struct SmsSentAck: Codable {
    let id: String
    let recipient: String
    let body: String
    let success: Bool
    let error: String?
    let ts: Int64
}

struct RelayedCall: Identifiable, Codable, Equatable {
    let id: String
    let state: String
    let number: String
    let contactName: String
    let wifiIp: String?
    let audioPort: Int?
    let ts: Int64

    var displayCaller: String {
        if !contactName.isEmpty { return contactName }
        return number.isEmpty ? "Unknown Caller" : number
    }
}

enum VoiceCallMode: String, Codable, CaseIterable {
    case localWifi = "local_wifi"
    case sip = "sip"
    case remoteOnly = "remote_only"
}

enum LocalAudioState: String, Equatable {
    case idle
    case connecting
    case connected
    case reconnecting
    case failed
}

struct SipAccountConfig: Codable, Equatable {
    var server: String = ""
    var port: Int = 5060
    var username: String = ""
    var password: String = ""
    var displayName: String = "OpenBridge"

    var isConfigured: Bool {
        !server.trimmingCharacters(in: .whitespaces).isEmpty &&
        !username.trimmingCharacters(in: .whitespaces).isEmpty
    }
}

struct SmsConversationThread: Identifiable, Equatable {
    let id: String
    let primaryAddress: String
    let displayTitle: String
    let messagesAscending: [RelayedSms]
    let latestMessage: RelayedSms

    static func normalizedThreadKey(for sender: String) -> String {
        let trimmed = sender.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "unknown" }
        let digitsOnly = trimmed.filter { $0.isNumber }
        let isPhoneLike = !trimmed.contains(where: { $0.isLetter })
        if isPhoneLike && digitsOnly.count >= 10 {
            return String(digitsOnly.suffix(10))
        }
        return trimmed.lowercased()
    }

    static func group(_ messages: [RelayedSms]) -> [SmsConversationThread] {
        var buckets: [String: [RelayedSms]] = [:]
        for sms in messages {
            let key = normalizedThreadKey(for: sms.sender)
            buckets[key, default: []].append(sms)
        }
        return buckets.compactMap { key, list in
            let asc = list.sorted { $0.ts < $1.ts }
            guard let latest = asc.last else { return nil }
            let named = asc.reversed().first(where: { !$0.contactName.isEmpty })?.contactName ?? ""
            let addr = asc.reversed().first(where: { !$0.sender.isEmpty })?.sender ?? key
            let title = named.isEmpty ? addr : named
            return SmsConversationThread(
                id: key,
                primaryAddress: addr,
                displayTitle: title,
                messagesAscending: asc,
                latestMessage: latest
            )
        }
        .sorted { $0.latestMessage.ts > $1.latestMessage.ts }
    }
}
