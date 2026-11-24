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
}
