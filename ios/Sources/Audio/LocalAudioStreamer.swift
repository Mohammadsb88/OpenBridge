import Foundation
import AVFoundation
import Network

/// Full-duplex 16kHz 16-bit mono PCM audio streamer over TCP to Android's LocalAudioRelayServer.
/// Includes automatic TCP retry backoff, CallKit AudioSession synchronization,
/// even-byte PCM sample framing, and mute control.
final class LocalAudioStreamer {
    private let sampleRate: Double = 16000
    private var connection: NWConnection?
    private var engine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var playbackFormat: AVAudioFormat?

    private var isRunning = false
    private var isSocketReady = false
    private var isAudioSessionReady = false
    private var isEngineStarted = false
    private var targetHost = ""
    private var targetPort = 54810
    private var retryCount = 0
    private let maxRetries = 12
    private var rxAccumulator = Data()
    private let queue = DispatchQueue(label: "org.openbridge.ios.localaudio")

    var isMuted: Bool = false
    var onLog: ((String) -> Void)?
    var onStateChanged: ((LocalAudioState) -> Void)?

    func start(host: String, port: Int, audioAlreadyActivated: Bool) {
        queue.async { [weak self] in
            guard let self else { return }
            self.teardownLocked()

            let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmedHost.isEmpty, port > 0, port <= 65535 else {
                self.onLog?("LocalAudioStreamer skipped: invalid host '\(host)'")
                self.notifyState(.failed)
                return
            }

            self.isRunning = true
            self.isAudioSessionReady = audioAlreadyActivated
            self.targetHost = trimmedHost
            self.targetPort = port
            self.retryCount = 0
            self.notifyState(.connecting)
            self.connectLocked()
        }
    }

    func onCallKitAudioActivated() {
        queue.async { [weak self] in
            guard let self else { return }
            self.isAudioSessionReady = true
            if self.isRunning && self.isSocketReady && !self.isEngineStarted {
                self.setupAudioEngineLocked()
            }
        }
    }

    func setSpeakerEnabled(_ enabled: Bool) {
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(enabled ? .speaker : .none)
    }

    func stop() {
        queue.async { [weak self] in
            self?.teardownLocked()
            self?.notifyState(.idle)
        }
    }

    private func teardownLocked() {
        isRunning = false
        isSocketReady = false
        isEngineStarted = false
        rxAccumulator.removeAll(keepingCapacity: false)
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        if let engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        playerNode?.stop()
        playerNode = nil
        engine = nil
    }

    private func connectLocked() {
        guard isRunning, let nwPort = NWEndpoint.Port(rawValue: UInt16(targetPort)) else { return }
        connection?.stateUpdateHandler = nil
        connection?.cancel()

        let params = NWParameters.tcp
        if let tcpOpts = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcpOpts.noDelay = true
            tcpOpts.connectionTimeout = 3
        }
        let conn = NWConnection(host: NWEndpoint.Host(targetHost), port: nwPort, using: params)
        connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.isSocketReady = true
                self.retryCount = 0
                self.onLog?("Local Wi-Fi audio connected to \(self.targetHost):\(self.targetPort)")
                self.notifyState(.connected)
                if self.isAudioSessionReady && !self.isEngineStarted {
                    self.setupAudioEngineLocked()
                }
                self.receiveLoopLocked(on: conn)
            case .failed(let err):
                self.isSocketReady = false
                self.onLog?("Local audio socket error: \(err.localizedDescription)")
                self.scheduleRetryLocked()
            case .waiting(let err):
                self.onLog?("Local audio waiting: \(err.localizedDescription)")
            case .cancelled:
                break
            default:
                break
            }
        }
        conn.start(queue: queue)
    }

    private func scheduleRetryLocked() {
        guard isRunning else { return }
        if retryCount >= maxRetries {
            onLog?("Local audio max retries reached")
            teardownLocked()
            notifyState(.failed)
            return
        }
        retryCount += 1
        notifyState(.reconnecting)
        queue.asyncAfter(deadline: .now() + 0.35) { [weak self] in
            guard let self, self.isRunning else { return }
            self.connectLocked()
        }
    }

    private func setupAudioEngineLocked() {
        guard !isEngineStarted, isRunning else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try? session.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.allowBluetooth, .allowBluetoothA2DP]
            )
            try? session.setPreferredSampleRate(sampleRate)
            try? session.setPreferredIOBufferDuration(0.02)
            if !isAudioSessionReady {
                try? session.setActive(true)
            }

            let audioEngine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            audioEngine.attach(player)

            guard let floatFormat = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: sampleRate,
                channels: 1,
                interleaved: false
            ),
            let int16Format = AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: sampleRate,
                channels: 1,
                interleaved: true
            ) else { return }

            playbackFormat = floatFormat
            audioEngine.connect(player, to: audioEngine.mainMixerNode, format: floatFormat)

            let input = audioEngine.inputNode
            let inputFormat = input.outputFormat(forBus: 0)
            guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
                onLog?("Input audio format invalid yet; waiting for route activation")
                return
            }
            let converter = AVAudioConverter(from: inputFormat, to: int16Format)

            input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
                guard let self, self.isRunning, self.isSocketReady, let converter, let conn = self.connection else { return }
                let ratio = self.sampleRate / inputFormat.sampleRate
                let cap = AVAudioFrameCount(Double(buffer.frameLength) * ratio + 16)
                guard let outBuffer = AVAudioPCMBuffer(pcmFormat: int16Format, frameCapacity: cap) else { return }

                var err: NSError?
                var didSupply = false
                converter.convert(to: outBuffer, error: &err) { _, outStatus in
                    if didSupply {
                        outStatus.pointee = .noDataNow
                        return nil
                    }
                    didSupply = true
                    outStatus.pointee = .haveData
                    return buffer
                }
                if err == nil, outBuffer.frameLength > 0, let ptr = outBuffer.int16ChannelData?[0] {
                    let byteCount = Int(outBuffer.frameLength) * MemoryLayout<Int16>.size
                    let data: Data
                    if self.isMuted {
                        data = Data(count: byteCount)
                    } else {
                        data = Data(bytes: ptr, count: byteCount)
                    }
                    conn.send(content: data, completion: .contentProcessed { _ in })
                }
            }

            try audioEngine.start()
            player.play()
            self.engine = audioEngine
            self.playerNode = player
            self.isEngineStarted = true
            onLog?("LocalAudio AVAudioEngine started (16kHz PCM)")
        } catch {
            onLog?("AVAudioEngine setup error: \(error.localizedDescription)")
        }
    }

    private func receiveLoopLocked(on conn: NWConnection) {
        guard isRunning else { return }
        conn.receive(minimumIncompleteLength: 320, maximumLength: 4096) { [weak self] data, _, isComplete, error in
            guard let self, self.isRunning, self.connection === conn else { return }
            if let data, !data.isEmpty {
                self.rxAccumulator.append(data)
                let evenCount = self.rxAccumulator.count & ~1
                if evenCount > 0 {
                    let chunk = self.rxAccumulator.prefix(evenCount)
                    self.playInt16Pcm(Data(chunk))
                    self.rxAccumulator.removeFirst(evenCount)
                }
            }
            if isComplete || error != nil {
                self.isSocketReady = false
                if let error {
                    self.onLog?("Audio receive closed: \(error.localizedDescription)")
                }
                self.scheduleRetryLocked()
                return
            }
            self.receiveLoopLocked(on: conn)
        }
    }

    private func playInt16Pcm(_ data: Data) {
        guard let player = playerNode, let format = playbackFormat else { return }
        let sampleCount = data.count / MemoryLayout<Int16>.size
        guard sampleCount > 0,
              let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(sampleCount)),
              let dst = pcm.floatChannelData?[0] else { return }

        pcm.frameLength = AVAudioFrameCount(sampleCount)
        data.withUnsafeBytes { raw in
            let src = raw.bindMemory(to: Int16.self)
            for i in 0..<sampleCount {
                dst[i] = Float(src[i]) / 32768.0
            }
        }
        player.scheduleBuffer(pcm, completionHandler: nil)
    }

    private func notifyState(_ state: LocalAudioState) {
        DispatchQueue.main.async {
            self.onStateChanged?(state)
        }
    }
}
