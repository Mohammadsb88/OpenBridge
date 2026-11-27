import Foundation
import AVFoundation
import Network

/// Streams full-duplex 16kHz 16-bit mono PCM audio over TCP to Android's LocalAudioRelayServer.
final class LocalAudioStreamer {
    private let sampleRate: Double = 16000
    private var connection: NWConnection?
    private var engine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var playbackFormat: AVAudioFormat?
    private var isRunning = false
    private let queue = DispatchQueue(label: "org.openbridge.ios.localaudio")

    var onLog: ((String) -> Void)?

    func start(host: String, port: Int) {
        stop()
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedHost.isEmpty, let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            onLog?("LocalAudioStreamer skipped: invalid host '\(host)'")
            return
        }

        isRunning = true
        let params = NWParameters.tcp
        let conn = NWConnection(host: NWEndpoint.Host(trimmedHost), port: nwPort, using: params)
        connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.onLog?("Local Wi-Fi audio socket connected to \(trimmedHost):\(port)")
                self.setupAudioEngine()
                self.receiveLoop()
            case .failed(let err):
                self.onLog?("Local audio socket failed: \(err.localizedDescription)")
                self.stop()
            case .cancelled:
                break
            default:
                break
            }
        }
        conn.start(queue: queue)
    }

    func stop() {
        isRunning = false
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

    private func setupAudioEngine() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
            try session.setPreferredSampleRate(sampleRate)
            try session.setActive(true)

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

            self.playbackFormat = floatFormat
            audioEngine.connect(player, to: audioEngine.mainMixerNode, format: floatFormat)

            let input = audioEngine.inputNode
            let inputFormat = input.outputFormat(forBus: 0)
            let converter = AVAudioConverter(from: inputFormat, to: int16Format)

            input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
                guard let self, self.isRunning, let converter, let conn = self.connection else { return }
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
                    let data = Data(bytes: ptr, count: byteCount)
                    conn.send(content: data, completion: .contentProcessed { _ in })
                }
            }

            try audioEngine.start()
            player.play()
            self.engine = audioEngine
            self.playerNode = player
        } catch {
            onLog?("AVAudioEngine setup error: \(error.localizedDescription)")
        }
    }

    private func receiveLoop() {
        guard isRunning, let conn = connection else { return }
        conn.receive(minimumIncompleteLength: 320, maximumLength: 4096) { [weak self] data, _, isComplete, error in
            guard let self, self.isRunning else { return }
            if let data, !data.isEmpty {
                self.playInt16Pcm(data)
            }
            if isComplete || error != nil {
                self.stop()
                return
            }
            self.receiveLoop()
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
}
