import Foundation
import AVFoundation
import CryptoKit
import Network

/// Lightweight pure-Swift SIP (UDP + MD5 Digest) + RTP G.711 u-law (PCMU 8kHz) client.
final class SipClient {
    enum SipState: String {
        case unregistered
        case registering
        case registered
        case inCall
        case error
    }

    var onStateChanged: ((SipState, String) -> Void)?
    var onIncomingCall: ((String, String) -> Void)?
    var onCallConnected: (() -> Void)?
    var onCallEnded: (() -> Void)?
    var onLog: ((String) -> Void)?

    private let queue = DispatchQueue(label: "org.openbridge.ios.sip")
    private var config = SipAccountConfig()
    private var sipConn: NWConnection?
    private var rtpConn: NWConnection?
    private var rtpListener: NWListener?
    private var localRtpPort: UInt16 = 40002
    private var localSipIp: String = "0.0.0.0"

    private var regCallId = UUID().uuidString
    private var cseq = 1
    private var activeCallId: String?
    private var remoteTag: String?
    private var localTag = String(UUID().uuidString.prefix(8))
    private var pendingInviteFrom: String?
    private var pendingInviteTo: String?
    private var pendingInviteVia: String?
    private var pendingInviteCSeq: String?
    private var pendingRemoteRtpHost: String?
    private var pendingRemoteRtpPort: UInt16 = 0
    private var outboundNumber: String?

    private var audioEngine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var playbackFormat: AVAudioFormat?
    private var rtpSeq: UInt16 = 1
    private var rtpTimestamp: UInt32 = 160
    private let rtpSsrc: UInt32 = UInt32.random(in: 1000...UInt32.max)
    private var isInMedia = false

    func configureAndRegister(_ cfg: SipAccountConfig) {
        stop()
        config = cfg
        guard cfg.isConfigured else {
            onStateChanged?(.unregistered, "Not configured")
            return
        }
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(cfg.port)) else {
            onStateChanged?(.error, "Invalid SIP port")
            return
        }

        onStateChanged?(.registering, "Connecting...")
        startRtpListener()

        let conn = NWConnection(host: NWEndpoint.Host(cfg.server), port: nwPort, using: .udp)
        sipConn = conn
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.localSipIp = self.resolveLocalAddress(from: conn) ?? "127.0.0.1"
                self.sendRegister(authHeader: nil)
                self.receiveSipLoop()
            case .failed(let err):
                self.onStateChanged?(.error, err.localizedDescription)
                self.onLog?("SIP UDP error: \(err.localizedDescription)")
            default:
                break
            }
        }
        conn.start(queue: queue)
    }

    func stop() {
        stopMedia()
        sipConn?.cancel()
        sipConn = nil
        rtpListener?.cancel()
        rtpListener = nil
        activeCallId = nil
        onStateChanged?(.unregistered, "Disconnected")
    }

    func makeCall(to number: String) {
        guard config.isConfigured, sipConn != nil else {
            onLog?("SIP call skipped: not registered")
            return
        }
        outboundNumber = number
        activeCallId = UUID().uuidString
        sendInvite(to: number, authHeader: nil)
    }

    func answerIncomingCall() {
        guard let callId = activeCallId,
              let fromHdr = pendingInviteFrom,
              let toHdr = pendingInviteTo,
              let viaHdr = pendingInviteVia,
              let cseqHdr = pendingInviteCSeq else { return }

        let sdp = buildSdp()
        let resp = [
            "SIP/2.0 200 OK",
            "Via: \(viaHdr)",
            "From: \(fromHdr)",
            "To: \(toHdr);tag=\(localTag)",
            "Call-ID: \(callId)",
            "CSeq: \(cseqHdr)",
            "Contact: <sip:\(config.username)@\(localSipIp):5060>",
            "Content-Type: application/sdp",
            "Content-Length: \(sdp.utf8.count)",
            "",
            sdp
        ].joined(separator: "\r\n")
        sendRawSip(resp)
        if let host = pendingRemoteRtpHost, pendingRemoteRtpPort > 0 {
            startMedia(remoteHost: host, remotePort: pendingRemoteRtpPort)
        }
        onStateChanged?(.inCall, "In Call")
    }

    func hangup() {
        if let callId = activeCallId, let target = outboundNumber {
            cseq += 1
            let bye = [
                "BYE sip:\(target)@\(config.server) SIP/2.0",
                "Via: SIP/2.0/UDP \(localSipIp):5060;branch=z9hG4bK-\(UUID().uuidString.prefix(8));rport",
                "Max-Forwards: 70",
                "From: <sip:\(config.username)@\(config.server)>;tag=\(localTag)",
                "To: <sip:\(target)@\(config.server)>\(remoteTag.map { ";tag=\($0)" } ?? "")",
                "Call-ID: \(callId)",
                "CSeq: \(cseq) BYE",
                "Content-Length: 0",
                "",
                ""
            ].joined(separator: "\r\n")
            sendRawSip(bye)
        }
        stopMedia()
        activeCallId = nil
        outboundNumber = nil
        onStateChanged?(.registered, "Registered")
    }

    // MARK: - SIP Signaling

    private func sendRegister(authHeader: String?) {
        cseq += 1
        var lines = [
            "REGISTER sip:\(config.server) SIP/2.0",
            "Via: SIP/2.0/UDP \(localSipIp):5060;branch=z9hG4bK-\(UUID().uuidString.prefix(8));rport",
            "Max-Forwards: 70",
            "From: \"\(config.displayName)\" <sip:\(config.username)@\(config.server)>;tag=\(localTag)",
            "To: <sip:\(config.username)@\(config.server)>",
            "Call-ID: \(regCallId)",
            "CSeq: \(cseq) REGISTER",
            "Contact: <sip:\(config.username)@\(localSipIp):5060>",
            "Expires: 300"
        ]
        if let authHeader {
            lines.append("Authorization: \(authHeader)")
        }
        lines.append("Content-Length: 0")
        lines.append("")
        lines.append("")
        sendRawSip(lines.joined(separator: "\r\n"))
    }

    private func sendInvite(to number: String, authHeader: String?) {
        cseq += 1
        let sdp = buildSdp()
        let callId = activeCallId ?? UUID().uuidString
        activeCallId = callId
        var lines = [
            "INVITE sip:\(number)@\(config.server) SIP/2.0",
            "Via: SIP/2.0/UDP \(localSipIp):5060;branch=z9hG4bK-\(UUID().uuidString.prefix(8));rport",
            "Max-Forwards: 70",
            "From: \"\(config.displayName)\" <sip:\(config.username)@\(config.server)>;tag=\(localTag)",
            "To: <sip:\(number)@\(config.server)>",
            "Call-ID: \(callId)",
            "CSeq: \(cseq) INVITE",
            "Contact: <sip:\(config.username)@\(localSipIp):5060>",
            "Content-Type: application/sdp"
        ]
        if let authHeader {
            lines.append("Authorization: \(authHeader)")
        }
        lines.append("Content-Length: \(sdp.utf8.count)")
        lines.append("")
        lines.append(sdp)
        sendRawSip(lines.joined(separator: "\r\n"))
    }

    private func buildSdp() -> String {
        [
            "v=0",
            "o=- 0 0 IN IP4 \(localSipIp)",
            "s=OpenBridge",
            "c=IN IP4 \(localSipIp)",
            "t=0 0",
            "m=audio \(localRtpPort) RTP/AVP 0",
            "a=rtpmap:0 PCMU/8000",
            "a=sendrecv",
            ""
        ].joined(separator: "\r\n")
    }

    private func sendRawSip(_ msg: String) {
        sipConn?.send(content: Data(msg.utf8), completion: .contentProcessed { _ in })
    }

    private func receiveSipLoop() {
        sipConn?.receiveMessage { [weak self] data, _, _, error in
            guard let self else { return }
            if let data, let text = String(data: data, encoding: .utf8) {
                self.handleSipMessage(text)
            }
            if error == nil {
                self.receiveSipLoop()
            }
        }
    }

    private func handleSipMessage(_ text: String) {
        let parts = text.components(separatedBy: "\r\n\r\n")
        let headerBlock = parts.first ?? text
        let body = parts.count > 1 ? parts.dropFirst().joined(separator: "\r\n\r\n") : ""
        let lines = headerBlock.components(separatedBy: "\r\n")
        guard let firstLine = lines.first else { return }

        func header(_ name: String) -> String? {
            let prefix = name.lowercased() + ":"
            for line in lines.dropFirst() {
                if line.lowercased().hasPrefix(prefix) {
                    return line.dropFirst(prefix.count).trimmingCharacters(in: .whitespaces)
                }
            }
            return nil
        }

        if firstLine.hasPrefix("SIP/2.0 401") || firstLine.hasPrefix("SIP/2.0 407") {
            let challenge = header("WWW-Authenticate") ?? header("Proxy-Authenticate") ?? ""
            let cseqStr = header("CSeq") ?? ""
            if cseqStr.contains("REGISTER"), let auth = buildDigestAuth(challenge: challenge, method: "REGISTER", uri: "sip:\(config.server)") {
                sendRegister(authHeader: auth)
            } else if cseqStr.contains("INVITE"), let target = outboundNumber,
                      let auth = buildDigestAuth(challenge: challenge, method: "INVITE", uri: "sip:\(target)@\(config.server)") {
                sendInvite(to: target, authHeader: auth)
            }
        } else if firstLine.hasPrefix("SIP/2.0 200") {
            let cseqStr = header("CSeq") ?? ""
            if cseqStr.contains("REGISTER") {
                onStateChanged?(.registered, "Registered")
                onLog?("SIP registered on \(config.server)")
            } else if cseqStr.contains("INVITE") {
                if let toHdr = header("To"), let tag = extractParam(toHdr, key: "tag") {
                    remoteTag = tag
                }
                if let target = outboundNumber, let callId = activeCallId {
                    cseq += 1
                    let ack = [
                        "ACK sip:\(target)@\(config.server) SIP/2.0",
                        "Via: SIP/2.0/UDP \(localSipIp):5060;branch=z9hG4bK-\(UUID().uuidString.prefix(8));rport",
                        "From: <sip:\(config.username)@\(config.server)>;tag=\(localTag)",
                        "To: <sip:\(target)@\(config.server)>\(remoteTag.map { ";tag=\($0)" } ?? "")",
                        "Call-ID: \(callId)",
                        "CSeq: \(cseq) ACK",
                        "Content-Length: 0",
                        "",
                        ""
                    ].joined(separator: "\r\n")
                    sendRawSip(ack)
                }
                if let (host, port) = parseSdpMedia(body) {
                    startMedia(remoteHost: host, remotePort: port)
                }
                onStateChanged?(.inCall, "In Call")
                onCallConnected?()
            }
        } else if firstLine.hasPrefix("INVITE ") {
            activeCallId = header("Call-ID")
            pendingInviteFrom = header("From")
            pendingInviteTo = header("To")
            pendingInviteVia = header("Via")
            pendingInviteCSeq = header("CSeq")
            if let (host, port) = parseSdpMedia(body) {
                pendingRemoteRtpHost = host
                pendingRemoteRtpPort = port
            }
            let caller = extractSipUser(pendingInviteFrom ?? "") ?? "SIP Caller"
            if let via = pendingInviteVia, let fromHdr = pendingInviteFrom, let toHdr = pendingInviteTo,
               let callId = activeCallId, let cseqStr = pendingInviteCSeq {
                let ringing = [
                    "SIP/2.0 180 Ringing",
                    "Via: \(via)",
                    "From: \(fromHdr)",
                    "To: \(toHdr);tag=\(localTag)",
                    "Call-ID: \(callId)",
                    "CSeq: \(cseqStr)",
                    "Content-Length: 0",
                    "",
                    ""
                ].joined(separator: "\r\n")
                sendRawSip(ringing)
            }
            onIncomingCall?(caller, caller)
        } else if firstLine.hasPrefix("BYE ") {
            if let via = header("Via"), let fromHdr = header("From"), let toHdr = header("To"),
               let callId = header("Call-ID"), let cseqStr = header("CSeq") {
                let ok = [
                    "SIP/2.0 200 OK",
                    "Via: \(via)",
                    "From: \(fromHdr)",
                    "To: \(toHdr)",
                    "Call-ID: \(callId)",
                    "CSeq: \(cseqStr)",
                    "Content-Length: 0",
                    "",
                    ""
                ].joined(separator: "\r\n")
                sendRawSip(ok)
            }
            stopMedia()
            activeCallId = nil
            onStateChanged?(.registered, "Registered")
            onCallEnded?()
        }
    }

    private func buildDigestAuth(challenge: String, method: String, uri: String) -> String? {
        guard let realm = extractQuotedParam(challenge, key: "realm"),
              let nonce = extractQuotedParam(challenge, key: "nonce") else { return nil }
        let ha1 = md5Hex("\(config.username):\(realm):\(config.password)")
        let ha2 = md5Hex("\(method):\(uri)")
        let response = md5Hex("\(ha1):\(nonce):\(ha2)")
        return "Digest username=\"\(config.username)\", realm=\"\(realm)\", nonce=\"\(nonce)\", uri=\"\(uri)\", response=\"\(response)\", algorithm=MD5"
    }

    private func md5Hex(_ input: String) -> String {
        Insecure.MD5.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private func extractQuotedParam(_ str: String, key: String) -> String? {
        guard let range = str.range(of: "\(key)=\"") else { return nil }
        let after = str[range.upperBound...]
        guard let end = after.firstIndex(of: "\"") else { return nil }
        return String(after[..<end])
    }

    private func extractParam(_ str: String, key: String) -> String? {
        for part in str.split(separator: ";") {
            let trimmed = part.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("\(key)=") {
                return String(trimmed.dropFirst(key.count + 1))
            }
        }
        return nil
    }

    private func extractSipUser(_ fromHeader: String) -> String? {
        guard let sipRange = fromHeader.range(of: "sip:") else { return nil }
        let after = fromHeader[sipRange.upperBound...]
        guard let atIdx = after.firstIndex(of: "@") else { return nil }
        return String(after[..<atIdx])
    }

    private func parseSdpMedia(_ sdp: String) -> (String, UInt16)? {
        var host: String?
        var port: UInt16 = 0
        for line in sdp.components(separatedBy: .newlines) {
            if line.hasPrefix("c=IN IP4 ") {
                host = line.replacingOccurrences(of: "c=IN IP4 ", with: "").trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("m=audio ") {
                let comps = line.split(separator: " ")
                if comps.count >= 2, let p = UInt16(comps[1]) {
                    port = p
                }
            }
        }
        if let host, port > 0 { return (host, port) }
        return nil
    }

    private func resolveLocalAddress(from conn: NWConnection) -> String? {
        if case let .hostPort(host, _) = conn.currentPath?.localEndpoint {
            return "\(host)".split(separator: "%").first.map(String.init)
        }
        return nil
    }

    // MARK: - RTP Audio (G.711 PCMU 8kHz)

    private func startRtpListener() {
        rtpListener?.cancel()
        if let listener = try? NWListener(using: .udp, on: .any) {
            listener.stateUpdateHandler = { [weak self] state in
                if case .ready = state, let port = listener.port?.rawValue {
                    self?.localRtpPort = port
                }
            }
            listener.newConnectionHandler = { [weak self] conn in
                conn.start(queue: self?.queue ?? .global())
                self?.receiveRtpPackets(on: conn)
            }
            listener.start(queue: queue)
            rtpListener = listener
        }
    }

    private func receiveRtpPackets(on conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, err in
            guard let self else { return }
            if let data, data.count > 12, self.isInMedia {
                self.playRtpPcmu(data.dropFirst(12))
            }
            if err == nil {
                self.receiveRtpPackets(on: conn)
            }
        }
    }

    private func startMedia(remoteHost: String, remotePort: UInt16) {
        stopMedia()
        guard let port = NWEndpoint.Port(rawValue: remotePort) else { return }
        let conn = NWConnection(host: NWEndpoint.Host(remoteHost), port: port, using: .udp)
        rtpConn = conn
        conn.start(queue: queue)
        isInMedia = true

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
            try session.setActive(true)

            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            engine.attach(player)

            guard let float8k = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 8000, channels: 1, interleaved: false),
                  let int16_8k = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 8000, channels: 1, interleaved: true) else { return }

            playbackFormat = float8k
            engine.connect(player, to: engine.mainMixerNode, format: float8k)

            let input = engine.inputNode
            let inputFmt = input.outputFormat(forBus: 0)
            let converter = AVAudioConverter(from: inputFmt, to: int16_8k)

            input.installTap(onBus: 0, bufferSize: 960, format: inputFmt) { [weak self] buf, _ in
                guard let self, self.isInMedia, let converter else { return }
                let ratio = 8000.0 / inputFmt.sampleRate
                let cap = AVAudioFrameCount(Double(buf.frameLength) * ratio + 16)
                guard let out = AVAudioPCMBuffer(pcmFormat: int16_8k, frameCapacity: cap) else { return }
                var err: NSError?
                var supplied = false
                converter.convert(to: out, error: &err) { _, st in
                    if supplied { st.pointee = .noDataNow; return nil }
                    supplied = true; st.pointee = .haveData; return buf
                }
                if err == nil, out.frameLength > 0, let ptr = out.int16ChannelData?[0] {
                    self.sendRtpAudio(samples: ptr, count: Int(out.frameLength))
                }
            }

            try engine.start()
            player.play()
            audioEngine = engine
            playerNode = player
        } catch {
            onLog?("SIP audio engine error: \(error.localizedDescription)")
        }
    }

    private func stopMedia() {
        isInMedia = false
        rtpConn?.cancel()
        rtpConn = nil
        if let audioEngine {
            audioEngine.inputNode.removeTap(onBus: 0)
            audioEngine.stop()
        }
        playerNode?.stop()
        playerNode = nil
        audioEngine = nil
    }

    private func sendRtpAudio(samples: UnsafePointer<Int16>, count: Int) {
        var rtp = Data(capacity: 12 + count)
        rtp.append(0x80)
        rtp.append(0x00) // PCMU payload type 0
        rtp.append(UInt8((rtpSeq >> 8) & 0xFF))
        rtp.append(UInt8(rtpSeq & 0xFF))
        rtp.append(UInt8((rtpTimestamp >> 24) & 0xFF))
        rtp.append(UInt8((rtpTimestamp >> 16) & 0xFF))
        rtp.append(UInt8((rtpTimestamp >> 8) & 0xFF))
        rtp.append(UInt8(rtpTimestamp & 0xFF))
        rtp.append(UInt8((rtpSsrc >> 24) & 0xFF))
        rtp.append(UInt8((rtpSsrc >> 16) & 0xFF))
        rtp.append(UInt8((rtpSsrc >> 8) & 0xFF))
        rtp.append(UInt8(rtpSsrc & 0xFF))
        for i in 0..<count {
            rtp.append(Self.linearToMulaw(samples[i]))
        }
        rtpSeq &+= 1
        rtpTimestamp &+= UInt32(count)
        rtpConn?.send(content: rtp, completion: .contentProcessed { _ in })
    }

    private func playRtpPcmu(_ payload: Data) {
        guard let player = playerNode, let format = playbackFormat else { return }
        let n = payload.count
        guard n > 0,
              let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(n)),
              let dst = pcm.floatChannelData?[0] else { return }
        pcm.frameLength = AVAudioFrameCount(n)
        for (i, byte) in payload.enumerated() {
            dst[i] = Float(Self.mulawToLinear(byte)) / 32768.0
        }
        player.scheduleBuffer(pcm, completionHandler: nil)
    }

    static func linearToMulaw(_ sample: Int16) -> UInt8 {
        var s = Int(sample)
        var sign = 0
        if s < 0 {
            sign = 0x80
            s = -s
        }
        if s > 32635 { s = 32635 }
        s += 0x84
        var exp = 7
        var mask = 0x4000
        while (s & mask) == 0 && exp > 0 {
            exp -= 1
            mask >>= 1
        }
        let mantissa = (s >> (exp + 3)) & 0x0F
        return UInt8(~(sign | (exp << 4) | mantissa) & 0xFF)
    }

    static func mulawToLinear(_ byte: UInt8) -> Int16 {
        let u = Int(~byte)
        let sign = u & 0x80
        let exp = (u >> 4) & 0x07
        let mantissa = u & 0x0F
        var sample = ((mantissa << 3) + 0x84) << exp
        sample -= 0x84
        return Int16(sign != 0 ? -sample : sample)
    }
}
