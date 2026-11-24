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

struct DeviceStatus: Codable, Equatable {
    let battery: Int
    let charging: Bool
    let signal: Int
    let networkType: String
    let operatorName: String
    let ts: Int64

    enum CodingKeys: String, CodingKey {
        case battery
        case charging
        case signal
        case networkType
        case operatorName = "operator"
        case ts
    }
}

struct RelayedSms: Identifiable, Codable, Equatable {
    let id: String
    let sender: String
    let contactName: String
    let body: String
    let otp: String
    let ts: Int64

    var displaySender: String {
        contactName.isEmpty ? sender : contactName
    }
}

struct RelayedCall: Identifiable, Codable, Equatable {
    let id: String
    let state: String
    let number: String
    let contactName: String
    let ts: Int64

    var displayCaller: String {
        if !contactName.isEmpty { return contactName }
        return number.isEmpty ? "Unknown Caller" : number
    }
}
