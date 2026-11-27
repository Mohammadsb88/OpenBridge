import Foundation
import AVFoundation
import CryptoKit
import Darwin

/// NAT-friendly pure-Swift SIP (UDP + RFC2617 Digest qop=auth) + Symmetric RTP (G.711 PCMU & PCMA 8kHz).
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

    // Single UDP socket for SIP signaling (learns public IP/port from Via received/rport)
    private var sipFd: Int32 = -1
    private var sipReadSource: DispatchSourceRead?
    private var sipServerAddr: sockaddr_in?
    private var localSipIp: String = "127.0.0.1"
    private var localSipPort: UInt16 = 5060
    private var publicSipIp: String?
    private var publicSipPort: UInt16?
    private var keepaliveTimer: DispatchSourceTimer?
    private var reRegisterTimer: DispatchSourceTimer?

    // Single symmetric UDP socket for RTP (send & receive on same port for NAT traversal)
    private var rtpFd: Int32 = -1
    private var rtpReadSource: DispatchSourceRead?
    private var rtpRemoteAddr: sockaddr_in?
    private var localRtpPort: UInt16 = 40002

    private var regCallId = UUID().uuidString
    private var cseq = 1
    private var nonceCount = 1
    private var activeCallId: String?
    private var remoteTag: String?
    private var remoteContactUri: String?
    private var localTag = String(UUID().uuidString.prefix(8))
    private var pendingInviteFrom: String?
    private var pendingInviteTo: String?
    private var pendingInviteVia: String?
    private var pendingInviteCSeq: String?
    private var pendingRemoteRtpHost: String?
    private var pendingRemoteRtpPort: UInt16 = 0
    private var negotiatedPayloadType: UInt8 = 0 // 0 = PCMU, 8 = PCMA
    private var outboundNumber: String?

    private var audioEngine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var playbackFormat: AVAudioFormat?
    private var rtpSeq: UInt16 = 1
    private var rtpTimestamp: UInt32 = 160
    private let rtpSsrc: UInt32 = UInt32.random(in: 1000...UInt32.max)
    private var isInMedia = false
    private var isAudioSessionReady = false
    private var pendingMediaTarget: (host: String, port: UInt16)?
    var isMuted: Bool = false

    private var advertisedIp: String { publicSipIp ?? localSipIp }
    private var advertisedPort: UInt16 { publicSipPort ?? localSipPort }

    func configureAndRegister(_ cfg: SipAccountConfig) {
        queue.async { [weak self] in
            guard let self else { return }
            self.stopLocked()
            self.config = cfg
            guard cfg.isConfigured else {
                self.notifyState(.unregistered, "Not configured")
                return
            }

            guard let resolved = self.resolveIPv4(host: cfg.server, port: UInt16(cfg.port)) else {
                self.notifyState(.error, "Cannot resolve SIP host")
                self.onLog?("SIP resolve failed for \(cfg.server):\(cfg.port)")
                return
            }
            self.sipServerAddr = resolved
            self.localSipIp = self.resolveOutboundLocalIP(to: resolved) ?? "127.0.0.1"
            self.publicSipIp = nil
            self.publicSipPort = nil

            guard self.openSipSocketLocked() else {
                self.notifyState(.error, "SIP UDP bind failed")
                return
            }
            self.openRtpSocketLocked()
            self.notifyState(.registering, "Registering...")
            self.sendRegisterLocked(authHeader: nil, headerName: "Authorization")
            self.startTimersLocked()
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.stopLocked()
        }
    }

    func makeCall(to number: String, audioAlreadyActivated: Bool = false) {
        queue.async { [weak self] in
            guard let self else { return }
            guard self.config.isConfigured, self.sipFd >= 0 else {
                self.onLog?("SIP call skipped: not registered")
                return
            }
            self.isAudioSessionReady = audioAlreadyActivated
            self.outboundNumber = number
            self.remoteTag = nil
            self.remoteContactUri = nil
            self.activeCallId = UUID().uuidString
            self.sendInviteLocked(to: number, authHeader: nil, headerName: "Authorization")
        }
    }

    func answerIncomingCall(audioAlreadyActivated: Bool = false) {
        queue.async { [weak self] in
            guard let self,
                  let callId = self.activeCallId,
                  let fromHdr = self.pendingInviteFrom,
                  let toHdr = self.pendingInviteTo,
                  let viaHdr = self.pendingInviteVia,
                  let cseqHdr = self.pendingInviteCSeq else { return }

            self.isAudioSessionReady = audioAlreadyActivated
            let sdp = self.buildSdp()
            let resp = [
                "SIP/2.0 200 OK",
                "Via: \(viaHdr)",
                "From: \(fromHdr)",
                "To: \(toHdr);tag=\(self.localTag)",
                "Call-ID: \(callId)",
                "CSeq: \(cseqHdr)",
                "Contact: <sip:\(self.config.username)@\(self.advertisedIp):\(self.advertisedPort)>",
                "Content-Type: application/sdp",
                "Content-Length: \(sdp.utf8.count)",
                "",
                sdp
            ].joined(separator: "\r\n")
            self.sendRawSipLocked(resp)
            if let host = self.pendingRemoteRtpHost, self.pendingRemoteRtpPort > 0 {
                self.prepareOrStartMediaLocked(remoteHost: host, remotePort: self.pendingRemoteRtpPort)
            }
            self.notifyState(.inCall, "In Call")
        }
    }

    func onCallKitAudioActivated() {
        queue.async { [weak self] in
            guard let self else { return }
            self.isAudioSessionReady = true
            if let pending = self.pendingMediaTarget, self.audioEngine == nil {
                self.startMediaEngineLocked(remoteHost: pending.host, remotePort: pending.port)
            }
        }
    }

    func sendDtmf(_ digit: String) {
        queue.async { [weak self] in
            guard let self, let callId = self.activeCallId else { return }
            let targetUri = self.remoteContactUri ?? "sip:\(self.outboundNumber ?? self.config.username)@\(self.config.server)"
            self.cseq += 1
            let body = "Signal=\(digit)\r\nDuration=160\r\n"
            let info = [
                "INFO \(targetUri) SIP/2.0",
                "Via: SIP/2.0/UDP \(self.advertisedIp):\(self.advertisedPort);branch=z9hG4bK-\(UUID().uuidString.prefix(8));rport",
                "Max-Forwards: 70",
                "From: <sip:\(self.config.username)@\(self.config.server)>;tag=\(self.localTag)",
                "To: <sip:\(self.outboundNumber ?? self.config.username)@\(self.config.server)>\(self.remoteTag.map { ";tag=\($0)" } ?? "")",
                "Call-ID: \(callId)",
                "CSeq: \(self.cseq) INFO",
                "Content-Type: application/dtmf-relay",
                "Content-Length: \(body.utf8.count)",
                "",
                body
            ].joined(separator: "\r\n")
            self.sendRawSipLocked(info)
        }
    }

    func hangup() {
        queue.async { [weak self] in
            guard let self else { return }
            if let callId = self.activeCallId {
                let targetUri = self.remoteContactUri ?? "sip:\(self.outboundNumber ?? self.config.username)@\(self.config.server)"
                self.cseq += 1
                let bye = [
                    "BYE \(targetUri) SIP/2.0",
                    "Via: SIP/2.0/UDP \(self.advertisedIp):\(self.advertisedPort);branch=z9hG4bK-\(UUID().uuidString.prefix(8));rport",
                    "Max-Forwards: 70",
                    "From: <sip:\(self.config.username)@\(self.config.server)>;tag=\(self.localTag)",
                    "To: <sip:\(self.outboundNumber ?? self.config.username)@\(self.config.server)>\(self.remoteTag.map { ";tag=\($0)" } ?? "")",
                    "Call-ID: \(callId)",
                    "CSeq: \(self.cseq) BYE",
                    "Content-Length: 0",
                    "",
                    ""
                ].joined(separator: "\r\n")
                self.sendRawSipLocked(bye)
            }
            self.stopMediaLocked()
            self.activeCallId = nil
            self.outboundNumber = nil
            self.remoteTag = nil
            self.remoteContactUri = nil
            self.notifyState(self.sipFd >= 0 ? .registered : .unregistered, self.sipFd >= 0 ? "Registered" : "Disconnected")
        }
    }

    private func stopLocked() {
        stopMediaLocked()
        keepaliveTimer?.cancel()
        keepaliveTimer = nil
        reRegisterTimer?.cancel()
        reRegisterTimer = nil
        sipReadSource?.cancel()
        sipReadSource = nil
        sipFd = -1
        rtpReadSource?.cancel()
        rtpReadSource = nil
        rtpFd = -1
        activeCallId = nil
        outboundNumber = nil
        notifyState(.unregistered, "Disconnected")
    }

    // MARK: - UDP Sockets

    private func openSipSocketLocked() -> Bool {
        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return false }
        _ = fcntl(fd, F_SETFL, O_NONBLOCK)

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = INADDR_ANY.bigEndian

        let bindRes = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindRes == 0 else {
            Darwin.close(fd)
            return false
        }

        var bound = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let gs = withUnsafeMutablePointer(to: &bound) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.getsockname(fd, $0, &len)
            }
        }
        localSipPort = (gs == 0) ? UInt16(bigEndian: bound.sin_port) : 5060
        sipFd = fd

        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in
            self?.drainSipSocketLocked()
        }
        source.setCancelHandler {
            Darwin.close(fd)
        }
        source.resume()
        sipReadSource = source
        return true
    }

    private func openRtpSocketLocked() {
        rtpReadSource?.cancel()
        rtpReadSource = nil
        let fd = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return }
        _ = fcntl(fd, F_SETFL, O_NONBLOCK)

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = INADDR_ANY.bigEndian

        let bindRes = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindRes == 0 else {
            Darwin.close(fd)
            return
        }

        var bound = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let gs = withUnsafeMutablePointer(to: &bound) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.getsockname(fd, $0, &len)
            }
        }
        if gs == 0 {
            localRtpPort = UInt16(bigEndian: bound.sin_port)
        }
        rtpFd = fd

        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in
            self?.drainRtpSocketLocked()
        }
        source.setCancelHandler {
            Darwin.close(fd)
        }
        source.resume()
        rtpReadSource = source
    }

    private func startTimersLocked() {
        keepaliveTimer?.cancel()
        let ka = DispatchSource.makeTimerSource(queue: queue)
        ka.schedule(deadline: .now() + 25, repeating: 25)
        ka.setEventHandler { [weak self] in
            self?.sendRawSipLocked("\r\n\r\n")
        }
        ka.resume()
        keepaliveTimer = ka

        reRegisterTimer?.cancel()
        let rr = DispatchSource.makeTimerSource(queue: queue)
        rr.schedule(deadline: .now() + 240, repeating: 240)
        rr.setEventHandler { [weak self] in
            self?.sendRegisterLocked(authHeader: nil, headerName: "Authorization")
        }
        rr.resume()
        reRegisterTimer = rr
    }

    private func drainSipSocketLocked() {
        guard sipFd >= 0 else { return }
        var buf = [UInt8](repeating: 0, count: 8192)
        while true {
            var fromAddr = sockaddr_in()
            var fromLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &fromAddr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.recvfrom(sipFd, &buf, buf.count, 0, $0, &fromLen)
                }
            }
            if n <= 0 { break }
            let data = Data(bytes: buf, count: n)
            if let text = String(data: data, encoding: .utf8) {
                handleSipMessageLocked(text)
            }
        }
    }

    private func drainRtpSocketLocked() {
        guard rtpFd >= 0 else { return }
        var buf = [UInt8](repeating: 0, count: 2048)
        while true {
            var fromAddr = sockaddr_in()
            var fromLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &fromAddr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.recvfrom(rtpFd, &buf, buf.count, 0, $0, &fromLen)
                }
            }
            if n <= 0 { break }
            if n > 12 && isInMedia {
                let pt = buf[1] & 0x7F
                let payload = Data(buf[12..<n])
                playRtpPayload(payload, payloadType: pt)
            }
        }
    }

    // MARK: - SIP Signaling

    private func sendRegisterLocked(authHeader: String?, headerName: String) {
        cseq += 1
        var lines = [
            "REGISTER sip:\(config.server) SIP/2.0",
            "Via: SIP/2.0/UDP \(advertisedIp):\(advertisedPort);branch=z9hG4bK-\(UUID().uuidString.prefix(8));rport",
            "Max-Forwards: 70",
            "From: \"\(config.displayName)\" <sip:\(config.username)@\(config.server)>;tag=\(localTag)",
            "To: <sip:\(config.username)@\(config.server)>",
            "Call-ID: \(regCallId)",
            "CSeq: \(cseq) REGISTER",
            "Contact: <sip:\(config.username)@\(advertisedIp):\(advertisedPort)>",
            "Expires: 300"
        ]
        if let authHeader {
            lines.append("\(headerName): \(authHeader)")
        }
        lines.append("Content-Length: 0")
        lines.append("")
        lines.append("")
        sendRawSipLocked(lines.joined(separator: "\r\n"))
    }

    private func sendInviteLocked(to number: String, authHeader: String?, headerName: String) {
        cseq += 1
        let sdp = buildSdp()
        let callId = activeCallId ?? UUID().uuidString
        activeCallId = callId
        var lines = [
            "INVITE sip:\(number)@\(config.server) SIP/2.0",
            "Via: SIP/2.0/UDP \(advertisedIp):\(advertisedPort);branch=z9hG4bK-\(UUID().uuidString.prefix(8));rport",
            "Max-Forwards: 70",
            "From: \"\(config.displayName)\" <sip:\(config.username)@\(config.server)>;tag=\(localTag)",
            "To: <sip:\(number)@\(config.server)>",
            "Call-ID: \(callId)",
            "CSeq: \(cseq) INVITE",
            "Contact: <sip:\(config.username)@\(advertisedIp):\(advertisedPort)>",
            "Allow: INVITE, ACK, CANCEL, BYE, OPTIONS, INFO",
            "Content-Type: application/sdp"
        ]
        if let authHeader {
            lines.append("\(headerName): \(authHeader)")
        }
        lines.append("Content-Length: \(sdp.utf8.count)")
        lines.append("")
        lines.append(sdp)
        sendRawSipLocked(lines.joined(separator: "\r\n"))
    }

    private func buildSdp() -> String {
        [
            "v=0",
            "o=- 0 0 IN IP4 \(advertisedIp)",
            "s=OpenBridge",
            "c=IN IP4 \(advertisedIp)",
            "t=0 0",
            "m=audio \(localRtpPort) RTP/AVP 0 8",
            "a=rtpmap:0 PCMU/8000",
            "a=rtpmap:8 PCMA/8000",
            "a=ptime:20",
            "a=sendrecv",
            ""
        ].joined(separator: "\r\n")
    }

    private func sendRawSipLocked(_ msg: String) {
        guard sipFd >= 0, var dest = sipServerAddr else { return }
        let data = Data(msg.utf8)
        data.withUnsafeBytes { raw in
            guard let ptr = raw.baseAddress else { return }
            _ = withUnsafePointer(to: &dest) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.sendto(sipFd, ptr, data.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    private func handleSipMessageLocked(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
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

        // Learn NAT reflexive IP & port from Via: ...;received=X;rport=Y
        if let via = header("Via") {
            if let recIp = extractParam(via, key: "received") {
                publicSipIp = recIp
            }
            if let rpStr = extractParam(via, key: "rport"), let rp = UInt16(rpStr), rp > 0 {
                publicSipPort = rp
            }
        }

        if firstLine.hasPrefix("SIP/2.0 401") || firstLine.hasPrefix("SIP/2.0 407") {
            let isProxy = firstLine.hasPrefix("SIP/2.0 407")
            let headerName = isProxy ? "Proxy-Authorization" : "Authorization"
            let challenge = header(isProxy ? "Proxy-Authenticate" : "WWW-Authenticate") ?? ""
            let cseqStr = header("CSeq") ?? ""
            if cseqStr.contains("REGISTER"),
               let auth = buildDigestAuth(challenge: challenge, method: "REGISTER", uri: "sip:\(config.server)") {
                sendRegisterLocked(authHeader: auth, headerName: headerName)
            } else if cseqStr.contains("INVITE"), let target = outboundNumber,
                      let auth = buildDigestAuth(challenge: challenge, method: "INVITE", uri: "sip:\(target)@\(config.server)") {
                sendInviteLocked(to: target, authHeader: auth, headerName: headerName)
            }
        } else if firstLine.hasPrefix("SIP/2.0 180") || firstLine.hasPrefix("SIP/2.0 183") {
            if let (host, port, pt) = parseSdpMedia(body) {
                negotiatedPayloadType = pt
                prepareOrStartMediaLocked(remoteHost: host, remotePort: port)
            }
        } else if firstLine.hasPrefix("SIP/2.0 200") {
            let cseqStr = header("CSeq") ?? ""
            if cseqStr.contains("REGISTER") {
                notifyState(.registered, "Registered")
                onLog?("SIP registered on \(config.server) (NAT \(advertisedIp):\(advertisedPort))")
            } else if cseqStr.contains("INVITE") {
                if let toHdr = header("To"), let tag = extractParam(toHdr, key: "tag") {
                    remoteTag = tag
                }
                if let contact = header("Contact"), let uri = extractContactUri(contact) {
                    remoteContactUri = uri
                }
                if let target = outboundNumber, let callId = activeCallId {
                    let ackUri = remoteContactUri ?? "sip:\(target)@\(config.server)"
                    let ackCseq = cseqStr.split(separator: " ").first.map(String.init) ?? String(cseq)
                    let ack = [
                        "ACK \(ackUri) SIP/2.0",
                        "Via: SIP/2.0/UDP \(advertisedIp):\(advertisedPort);branch=z9hG4bK-\(UUID().uuidString.prefix(8));rport",
                        "Max-Forwards: 70",
                        "From: <sip:\(config.username)@\(config.server)>;tag=\(localTag)",
                        "To: <sip:\(target)@\(config.server)>\(remoteTag.map { ";tag=\($0)" } ?? "")",
                        "Call-ID: \(callId)",
                        "CSeq: \(ackCseq) ACK",
                        "Content-Length: 0",
                        "",
                        ""
                    ].joined(separator: "\r\n")
                    sendRawSipLocked(ack)
                }
                if let (host, port, pt) = parseSdpMedia(body) {
                    negotiatedPayloadType = pt
                    prepareOrStartMediaLocked(remoteHost: host, remotePort: port)
                }
                notifyState(.inCall, "In Call")
                DispatchQueue.main.async { self.onCallConnected?() }
            }
        } else if firstLine.hasPrefix("INVITE ") {
            activeCallId = header("Call-ID")
            pendingInviteFrom = header("From")
            pendingInviteTo = header("To")
            pendingInviteVia = header("Via")
            pendingInviteCSeq = header("CSeq")
            if let contact = header("Contact"), let uri = extractContactUri(contact) {
                remoteContactUri = uri
            }
            if let fromHdr = pendingInviteFrom, let tag = extractParam(fromHdr, key: "tag") {
                remoteTag = tag
            }
            if let (host, port, pt) = parseSdpMedia(body) {
                pendingRemoteRtpHost = host
                pendingRemoteRtpPort = port
                negotiatedPayloadType = pt
            }
            let caller = extractSipUser(pendingInviteFrom ?? "") ?? "SIP Caller"
            outboundNumber = caller
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
                sendRawSipLocked(ringing)
            }
            DispatchQueue.main.async { self.onIncomingCall?(caller, caller) }
        } else if firstLine.hasPrefix("BYE ") || firstLine.hasPrefix("CANCEL ") {
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
                sendRawSipLocked(ok)
            }
            stopMediaLocked()
            activeCallId = nil
            outboundNumber = nil
            notifyState(.registered, "Registered")
            DispatchQueue.main.async { self.onCallEnded?() }
        }
    }

    private func buildDigestAuth(challenge: String, method: String, uri: String) -> String? {
        guard let realm = extractQuotedParam(challenge, key: "realm"),
              let nonce = extractQuotedParam(challenge, key: "nonce") else { return nil }
        let ha1 = md5Hex("\(config.username):\(realm):\(config.password)")
        let ha2 = md5Hex("\(method):\(uri)")

        let qop = extractQuotedParam(challenge, key: "qop")
        if let qop, qop.split(separator: ",").map({ $0.trimmingCharacters(in: .whitespaces) }).contains("auth") {
            let nc = String(format: "%08x", nonceCount)
            nonceCount += 1
            let cnonce = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))
            let response = md5Hex("\(ha1):\(nonce):\(nc):\(cnonce):auth:\(ha2)")
            return "Digest username=\"\(config.username)\", realm=\"\(realm)\", nonce=\"\(nonce)\", uri=\"\(uri)\", qop=auth, nc=\(nc), cnonce=\"\(cnonce)\", response=\"\(response)\", algorithm=MD5"
        } else {
            let response = md5Hex("\(ha1):\(nonce):\(ha2)")
            return "Digest username=\"\(config.username)\", realm=\"\(realm)\", nonce=\"\(nonce)\", uri=\"\(uri)\", response=\"\(response)\", algorithm=MD5"
        }
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

    private func extractContactUri(_ contact: String) -> String? {
        if let start = contact.firstIndex(of: "<"), let end = contact.firstIndex(of: ">"), start < end {
            return String(contact[contact.index(after: start)..<end])
        }
        return nil
    }

    private func extractSipUser(_ fromHeader: String) -> String? {
        guard let sipRange = fromHeader.range(of: "sip:") else { return nil }
        let after = fromHeader[sipRange.upperBound...]
        guard let atIdx = after.firstIndex(of: "@") else { return nil }
        return String(after[..<atIdx])
    }

    private func parseSdpMedia(_ sdp: String) -> (String, UInt16, UInt8)? {
        var host: String?
        var port: UInt16 = 0
        var pt: UInt8 = 0
        for line in sdp.components(separatedBy: .newlines) {
            let clean = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if clean.hasPrefix("c=IN IP4 ") {
                host = clean.replacingOccurrences(of: "c=IN IP4 ", with: "")
            } else if clean.hasPrefix("m=audio ") {
                let comps = clean.split(separator: " ")
                if comps.count >= 2, let p = UInt16(comps[1]) {
                    port = p
                }
                let payloads = comps.dropFirst(3).compactMap { UInt8($0) }
                if payloads.contains(0) {
                    pt = 0
                } else if payloads.contains(8) {
                    pt = 8
                } else if let first = payloads.first {
                    pt = first
                }
            }
        }
        if let host, port > 0 { return (host, port, pt) }
        return nil
    }

    // MARK: - RTP Media

    private func prepareOrStartMediaLocked(remoteHost: String, remotePort: UInt16) {
        pendingMediaTarget = (remoteHost, remotePort)
        rtpRemoteAddr = resolveIPv4(host: remoteHost, port: remotePort)
        // Send an initial RTP NAT-punching packet so symmetric NAT learns our RTP socket immediately
        if rtpFd >= 0 {
            sendSilentRtpPunchLocked()
        }
        if isAudioSessionReady {
            startMediaEngineLocked(remoteHost: remoteHost, remotePort: remotePort)
        }
    }

    private func sendSilentRtpPunchLocked() {
        var zero = [Int16](repeating: 0, count: 160)
        zero.withUnsafeMutableBufferPointer { buf in
            if let base = buf.baseAddress {
                sendRtpAudioLocked(samples: base, count: 160)
            }
        }
    }

    private func startMediaEngineLocked(remoteHost: String, remotePort: UInt16) {
        guard audioEngine == nil else { return }
        if rtpRemoteAddr == nil {
            rtpRemoteAddr = resolveIPv4(host: remoteHost, port: remotePort)
        }
        isInMedia = true

        do {
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP])
            if !isAudioSessionReady {
                try? session.setActive(true)
            }

            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            engine.attach(player)

            guard let float8k = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 8000, channels: 1, interleaved: false),
                  let int16_8k = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 8000, channels: 1, interleaved: true) else { return }

            playbackFormat = float8k
            engine.connect(player, to: engine.mainMixerNode, format: float8k)

            let input = engine.inputNode
            let inputFmt = input.outputFormat(forBus: 0)
            guard inputFmt.sampleRate > 0, inputFmt.channelCount > 0 else { return }
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
                    let count = Int(out.frameLength)
                    self.queue.async {
                        self.sendRtpAudioLocked(samples: ptr, count: count)
                    }
                }
            }

            try engine.start()
            player.play()
            audioEngine = engine
            playerNode = player
            onLog?("SIP RTP media active -> \(remoteHost):\(remotePort) (PT=\(negotiatedPayloadType))")
        } catch {
            onLog?("SIP audio engine error: \(error.localizedDescription)")
        }
    }

    private func stopMediaLocked() {
        isInMedia = false
        pendingMediaTarget = nil
        rtpRemoteAddr = nil
        if let audioEngine {
            audioEngine.inputNode.removeTap(onBus: 0)
            audioEngine.stop()
        }
        playerNode?.stop()
        playerNode = nil
        audioEngine = nil
    }

    private func sendRtpAudioLocked(samples: UnsafePointer<Int16>, count: Int) {
        guard rtpFd >= 0, var dest = rtpRemoteAddr else { return }
        var rtp = Data(capacity: 12 + count)
        rtp.append(0x80)
        rtp.append(negotiatedPayloadType)
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
            let sample: Int16 = isMuted ? 0 : samples[i]
            if negotiatedPayloadType == 8 {
                rtp.append(Self.linearToAlaw(sample))
            } else {
                rtp.append(Self.linearToMulaw(sample))
            }
        }
        rtpSeq &+= 1
        rtpTimestamp &+= UInt32(count)
        rtp.withUnsafeBytes { raw in
            guard let ptr = raw.baseAddress else { return }
            _ = withUnsafePointer(to: &dest) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.sendto(rtpFd, ptr, rtp.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    private func playRtpPayload(_ payload: Data, payloadType: UInt8) {
        guard let player = playerNode, let format = playbackFormat else { return }
        let n = payload.count
        guard n > 0,
              let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(n)),
              let dst = pcm.floatChannelData?[0] else { return }
        pcm.frameLength = AVAudioFrameCount(n)
        for (i, byte) in payload.enumerated() {
            let lin = (payloadType == 8) ? Self.alawToLinear(byte) : Self.mulawToLinear(byte)
            dst[i] = Float(lin) / 32768.0
        }
        player.scheduleBuffer(pcm, completionHandler: nil)
    }

    // MARK: - Helpers & Codecs

    private func resolveIPv4(host: String, port: UInt16) -> sockaddr_in? {
        var hints = addrinfo()
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_DGRAM
        hints.ai_protocol = IPPROTO_UDP
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &res) == 0, let first = res else { return nil }
        defer { freeaddrinfo(res) }
        guard let addrPtr = first.pointee.ai_addr else { return nil }
        return addrPtr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
    }

    private func resolveOutboundLocalIP(to remote: sockaddr_in) -> String? {
        let probe = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard probe >= 0 else { return nil }
        defer { Darwin.close(probe) }
        var dest = remote
        let rc = withUnsafePointer(to: &dest) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(probe, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard rc == 0 else { return nil }
        var local = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let gs = withUnsafeMutablePointer(to: &local) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.getsockname(probe, $0, &len)
            }
        }
        guard gs == 0 else { return nil }
        var ipBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        var addrCopy = local.sin_addr
        return inet_ntop(AF_INET, &addrCopy, &ipBuf, socklen_t(INET_ADDRSTRLEN)).map { String(cString: $0) }
    }

    private func notifyState(_ state: SipState, _ text: String) {
        DispatchQueue.main.async {
            self.onStateChanged?(state, text)
        }
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

    static func linearToAlaw(_ sample: Int16) -> UInt8 {
        var pcm = Int(sample)
        let mask: Int
        if pcm >= 0 {
            mask = 0xD5
        } else {
            mask = 0x55
            pcm = -pcm - 1
        }
        if pcm > 32635 { pcm = 32635 }
        let segEnds = [0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF]
        var seg = 0
        while seg < 8 && pcm > segEnds[seg] {
            seg += 1
        }
        if seg >= 8 {
            return UInt8((0x7F ^ mask) & 0xFF)
        }
        let shift = (seg < 2) ? 4 : (seg + 3)
        let aval = (seg << 4) | ((pcm >> shift) & 0x0F)
        return UInt8((aval ^ mask) & 0xFF)
    }

    static func alawToLinear(_ byte: UInt8) -> Int16 {
        let a = Int(byte) ^ 0x55
        var t = (a & 0x0F) << 4
        let seg = (a & 0x70) >> 4
        switch seg {
        case 0: t += 8
        case 1: t += 0x108
        default:
            t += 0x108
            t <<= (seg - 1)
        }
        return Int16((a & 0x80) != 0 ? t : -t)
    }
}
