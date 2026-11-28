import Foundation
import CryptoKit

enum BridgeCrypto {
    private static let salt = Data("OpenBridge-v1".utf8)
    private static let info = Data("session-aes-256-gcm".utf8)

    static func generatePrivateKey() -> P256.KeyAgreement.PrivateKey {
        P256.KeyAgreement.PrivateKey()
    }

    static func deriveSessionKey(
        privateKey: P256.KeyAgreement.PrivateKey,
        peerPublicX963: Data
    ) throws -> SymmetricKey {
        let peerPub = try P256.KeyAgreement.PublicKey(x963Representation: peerPublicX963)
        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: peerPub)
        return sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: info,
            outputByteCount: 32
        )
    }

    static func encrypt(key: SymmetricKey, plaintext: Data) throws -> (nonce: Data, cipherWithTag: Data) {
        let sealed = try AES.GCM.seal(plaintext, using: key)
        let nonceData = sealed.nonce.withUnsafeBytes { Data($0) }
        var combined = Data(capacity: sealed.ciphertext.count + sealed.tag.count)
        combined.append(sealed.ciphertext)
        combined.append(sealed.tag)
        return (nonceData, combined)
    }

    static func decrypt(key: SymmetricKey, nonce: Data, cipherWithTag: Data) throws -> Data {
        guard nonce.count == 12, cipherWithTag.count >= 16 else {
            throw CryptoKitError.incorrectParameterSize
        }
        let tagSize = 16
        let ciphertext = cipherWithTag.prefix(cipherWithTag.count - tagSize)
        let tag = cipherWithTag.suffix(tagSize)
        let gcmNonce = try AES.GCM.Nonce(data: nonce)
        let box = try AES.GCM.SealedBox(nonce: gcmNonce, ciphertext: ciphertext, tag: tag)
        return try AES.GCM.open(box, using: key)
    }

    static func sealAudioPacket(key: SymmetricKey, direction: UInt8, counter: UInt64, plaintext: Data) throws -> Data {
        let nonceData = buildAudioNonce(direction: direction, counter: counter)
        let gcmNonce = try AES.GCM.Nonce(data: nonceData)
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: gcmNonce)

        var out = Data(capacity: 1 + 8 + sealed.ciphertext.count + sealed.tag.count)
        out.append(direction)
        var be = counter.bigEndian
        withUnsafeBytes(of: &be) { out.append(contentsOf: $0) }
        out.append(sealed.ciphertext)
        out.append(sealed.tag)
        return out
    }

    static func openAudioPacket(key: SymmetricKey, expectedDirection: UInt8, packet: Data) -> Data? {
        guard packet.count >= 1 + 8 + 16, packet[packet.startIndex] == expectedDirection else {
            return nil
        }
        var counter: UInt64 = 0
        for i in 0..<8 {
            counter = (counter << 8) | UInt64(packet[packet.startIndex + 1 + i])
        }
        let nonceData = buildAudioNonce(direction: expectedDirection, counter: counter)
        let payload = packet.dropFirst(9)
        return try? decrypt(key: key, nonce: nonceData, cipherWithTag: Data(payload))
    }

    private static func buildAudioNonce(direction: UInt8, counter: UInt64) -> Data {
        var nonce = Data(capacity: 12)
        nonce.append(0x4F) // 'O'
        nonce.append(0x42) // 'B'
        nonce.append(direction == BridgeConstants.audioDirAndroidToIos ? 0x41 : 0x49) // 'A' or 'I'
        nonce.append(0x32) // '2'
        var be = counter.bigEndian
        withUnsafeBytes(of: &be) { nonce.append(contentsOf: $0) }
        return nonce
    }
}
