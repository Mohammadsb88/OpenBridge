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

    func testMulawAndAlawCodecsRoundTrip() {
        let samples: [Int16] = [0, 512, -512, 8192, -16384]
        for s in samples {
            let ulaw = SipClient.mulawToLinear(SipClient.linearToMulaw(s))
            XCTAssertLessThan(abs(Int(s) - Int(ulaw)), 600)

            let alaw = SipClient.alawToLinear(SipClient.linearToAlaw(s))
            XCTAssertLessThan(abs(Int(s) - Int(alaw)), 600)
        }
    }

    func testSmsModelsBackwardCompatibility() throws {
        let incomingJson = """
        {"id":"1","sender":"+989120000000","contactName":"Ali","body":"Hi","otp":"","ts":1732480000000}
        """.data(using: .utf8)!
        let sms = try JSONDecoder().decode(RelayedSms.self, from: incomingJson)
        XCTAssertFalse(sms.isOutgoingMessage)
        XCTAssertEqual(sms.displaySender, "Ali")

        let sentAckJson = """
        {"id":"1","recipient":"+989120000000","body":"Salam","success":true,"error":"","ts":1732480000000}
        """.data(using: .utf8)!
        let ack = try JSONDecoder().decode(SmsSentAck.self, from: sentAckJson)
        XCTAssertTrue(ack.success)
    }

    func testV04SyncAndDualSimModels() throws {
        let statusJson = """
        {"battery":88,"charging":true,"signal":4,"networkType":"Cellular","operator":"MCI","wifiIp":"192.168.43.1","audioPort":54810,"sims":[{"slot":0,"subId":1,"name":"MCI"},{"slot":1,"subId":2,"name":"Irancell"}],"ts":1732480000000}
        """.data(using: .utf8)!
        let status = try JSONDecoder().decode(DeviceStatus.self, from: statusJson)
        XCTAssertEqual(status.sims?.count, 2)
        XCTAssertEqual(status.sims?[1].name, "Irancell")

        let ussdJson = """
        {"code":"*140#","success":true,"message":"Etebar shoma 120000 Rial ast","ts":1732480000000}
        """.data(using: .utf8)!
        let ussd = try JSONDecoder().decode(UssdResponse.self, from: ussdJson)
        XCTAssertTrue(ussd.success)
        XCTAssertEqual(ussd.code, "*140#")

        let contactsJson = """
        {"items":[{"name":"Reza","number":"09121112233"}]}
        """.data(using: .utf8)!
        let batch = try JSONDecoder().decode(ContactsSyncBatch.self, from: contactsJson)
        XCTAssertEqual(batch.items.first?.number, "09121112233")
    }

    func testImaAdpcmCodecAndEncryptedAudioPacketRoundTrip() throws {
        let encoder = ImaAdpcmCodec()
        let decoder = ImaAdpcmCodec()

        var pcm16k = Data(count: 640) // 20ms @ 16kHz mono (320 samples)
        for i in 0..<320 {
            let sample = Int16(clamping: Int(sin(2.0 * Double.pi * 500.0 * Double(i) / 16000.0) * 12000.0))
            pcm16k[i * 2] = UInt8(truncatingIfNeeded: sample & 0xFF)
            pcm16k[i * 2 + 1] = UInt8(truncatingIfNeeded: (sample >> 8) & 0xFF)
        }

        let pcm8k = ImaAdpcmCodec.downsample16kTo8k(pcm16k)
        XCTAssertEqual(pcm8k.count, 320)

        let adpcmPlain = encoder.encodeFrame(pcm8kBytes: pcm8k)
        XCTAssertEqual(adpcmPlain.count, ImaAdpcmCodec.packetPlaintextBytes) // 86B

        let privA = BridgeCrypto.generatePrivateKey()
        let privB = BridgeCrypto.generatePrivateKey()
        let keyA = try BridgeCrypto.deriveSessionKey(privateKey: privA, peerPublicX963: privB.publicKey.x963Representation)
        let keyB = try BridgeCrypto.deriveSessionKey(privateKey: privB, peerPublicX963: privA.publicKey.x963Representation)

        let wire = try BridgeCrypto.sealAudioPacket(
            key: keyA,
            direction: BridgeConstants.audioDirAndroidToIos,
            counter: 42,
            plaintext: adpcmPlain
        )
        XCTAssertEqual(wire.count, 111)

        let opened = BridgeCrypto.openAudioPacket(
            key: keyB,
            expectedDirection: BridgeConstants.audioDirAndroidToIos,
            packet: wire
        )
        XCTAssertEqual(opened, adpcmPlain)

        let decoded = decoder.decodeFrame(packet: opened!)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.seq, 0)
        XCTAssertEqual(decoded?.pcm8k.count, 320)

        let restored16k = ImaAdpcmCodec.upsample8kTo16k(decoded!.pcm8k)
        XCTAssertEqual(restored16k.count, 640)
    }

    func testSmsThreadGroupingAndVoiceCapabilityDecoding() throws {
        let capJson = """
        {"verdict":"two_way_direct","mode":"shizuku","downlinkOk":true,"uplinkOk":true,"downlinkSource":"VOICE_CALL@16000Hz","uplinkLabel":"TYPE_TELEPHONY(VOICE_COMM@48000Hz/2ch)","summary":"Ready","ts":1732480000000}
        """.data(using: .utf8)!
        let cap = try JSONDecoder().decode(VoiceCapabilityInfo.self, from: capJson)
        XCTAssertEqual(cap.verdict, "two_way_direct")
        XCTAssertTrue(cap.uplinkOk)

        let msgs = [
            RelayedSms(id: "1", sender: "+989123456789", contactName: "Ali", body: "Salam", otp: "", ts: 1000, isOutgoing: false, deliveryStatus: nil, errorDetail: nil),
            RelayedSms(id: "2", sender: "09123456789", contactName: "", body: "Khoobam", otp: "", ts: 2000, isOutgoing: true, deliveryStatus: "sent", errorDetail: nil),
            RelayedSms(id: "3", sender: "BankMellat", contactName: "", body: "Code: 12345", otp: "12345", ts: 1500, isOutgoing: false, deliveryStatus: nil, errorDetail: nil)
        ]
        let threads = SmsConversationThread.group(msgs)
        XCTAssertEqual(threads.count, 2)
        XCTAssertEqual(threads[0].displayTitle, "Ali")
        XCTAssertEqual(threads[0].messagesAscending.count, 2)
        XCTAssertEqual(threads[1].primaryAddress, "BankMellat")
    }
}
