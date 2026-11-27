import XCTest
import CryptoKit
@testable import OpenBridge

final class OpenBridgeTests: XCTestCase {
    func testFrameCodecRoundTrip() throws {
        let p1 = Data("{\"type\":\"PING\"}".utf8)
        let p2 = Data(String(repeating: "B", count: 350).utf8)
        var stream = FrameCodec.encode(p1)
        stream.append(FrameCodec.encode(p2))

        let decoder = FrameCodec.StreamDecoder()
        var decoded: [Data] = []
        for chunk in FrameCodec.chunk(stream, maxChunkSize: 20) {
            decoded.append(contentsOf: decoder.append(chunk))
        }
        XCTAssertEqual(decoded.count, 2)
        XCTAssertEqual(decoded[0], p1)
        XCTAssertEqual(decoded[1], p2)
    }

    func testEcdhP256AndAesGcmRoundTrip() throws {
        let privA = BridgeCrypto.generatePrivateKey()
        let privB = BridgeCrypto.generatePrivateKey()

        let pubA = privA.publicKey.x963Representation
        let pubB = privB.publicKey.x963Representation
        XCTAssertEqual(pubA.count, 65)
        XCTAssertEqual(pubB.count, 65)

        let keyA = try BridgeCrypto.deriveSessionKey(privateKey: privA, peerPublicX963: pubB)
        let keyB = try BridgeCrypto.deriveSessionKey(privateKey: privB, peerPublicX963: pubA)

        let msg = Data("رمز پویا: 482910".utf8)
        let (nonce, cipher) = try BridgeCrypto.encrypt(key: keyA, plaintext: msg)
        let decrypted = try BridgeCrypto.decrypt(key: keyB, nonce: nonce, cipherWithTag: cipher)
        XCTAssertEqual(decrypted, msg)
    }

    func testMulawCodecRoundTrip() {
        let samples: [Int16] = [0, 512, -512, 8192, -16384]
        for s in samples {
            let encoded = SipClient.linearToMulaw(s)
            let decoded = SipClient.mulawToLinear(encoded)
            XCTAssertLessThan(abs(Int(s) - Int(decoded)), 600)
        }
    }
}
