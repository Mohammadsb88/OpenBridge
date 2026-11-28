import Foundation
import AVFoundation

/// Full-duplex voice audio streamer over Pure BLE (8 kHz IMA-ADPCM 20ms frames), requiring zero Wi-Fi/Hotspot.
final class BleAudioStreamer {
    private let sampleRate: Double = 16000
    private var engine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var playbackFormat: AVAudioFormat?

    private var isRunning = false
    private var isAudioSessionReady = false
    private var isEngineStarted = false

    private let txCodec = ImaAdpcmCodec()
    private let rxCodec = ImaAdpcmCodec()
    private var txAccumulator = Data()
    private let queue = DispatchQueue(label: "org.openbridge.ios.bleaudio")

    var isMuted: Bool = false
    var onLog: ((String) -> Void)?
    var onEncodedAdpcmFrame: ((Data) -> Void)?
    var onStateChanged: ((LocalAudioState) -> Void)?

    func start(audioAlreadyActivated: Bool) {
        queue.async { [weak self] in
            guard let self else { return }
            self.teardownLocked()
            self.isRunning = true
            self.isAudioSessionReady = audioAlreadyActivated
            self.notifyState(.connecting)
            if self.isAudioSessionReady {
                self.setupAudioEngineLocked()
            }
        }
    }

    func onCallKitAudioActivated() {
        queue.async { [weak self] in
            guard let self else { return }
            self.isAudioSessionReady = true
            if self.isRunning && !self.isEngineStarted {
                self.setupAudioEngineLocked()
            }
        }
    }

    func setSpeakerEnabled(_ enabled: Bool) {
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(enabled ? .speaker : .none)
    }

    func feedIncomingAdpcmPlaintext(_ packet: Data) {
        queue.async { [weak self] in
            guard let self, self.isRunning,
                  let decoded = self.rxCodec.decodeFrame(packet: packet) else {
                return
            }
            let pcm16k = ImaAdpcmCodec.upsample8kTo16k(decoded.pcm8k)
            self.playInt16Pcm(pcm16k)
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.teardownLocked()
            self?.notifyState(.idle)
        }
    }

    private func teardownLocked() {
        isRunning = false
        isEngineStarted = false
        txAccumulator.removeAll(keepingCapacity: false)
        txCodec.reset()
        rxCodec.reset()
        if let engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        playerNode?.stop()
        playerNode = nil
        engine = nil
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
                onLog?("BLE Audio waiting for AVAudioSession input route...")
                return
            }
            let converter = AVAudioConverter(from: inputFormat, to: int16Format)

            input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
                guard let self, self.isRunning, let converter else { return }
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
                    let rawPcm: Data
                    if self.isMuted {
                        rawPcm = Data(count: byteCount)
                    } else {
                        rawPcm = Data(bytes: ptr, count: byteCount)
                    }
                    self.queue.async { [weak self] in
                        self?.enqueueTxPcm16k(rawPcm)
                    }
                }
            }

            try audioEngine.start()
            player.play()
            self.engine = audioEngine
            self.playerNode = player
            self.isEngineStarted = true
            self.notifyState(.connected)
            onLog?("Pure-BLE Audio engine started (8kHz IMA-ADPCM)")
        } catch {
            onLog?("BLE Audio setup error: \(error.localizedDescription)")
            notifyState(.failed)
        }
    }

    private func enqueueTxPcm16k(_ data: Data) {
        guard isRunning else { return }
        txAccumulator.append(data)
        let chunkBytes16k = 640 // 20ms @ 16kHz mono
        while txAccumulator.count >= chunkBytes16k {
            let slice = Data(txAccumulator.prefix(chunkBytes16k))
            txAccumulator.removeFirst(chunkBytes16k)
            let pcm8k = ImaAdpcmCodec.downsample16kTo8k(slice)
            let encoded = txCodec.encodeFrame(pcm8kBytes: pcm8k)
            onEncodedAdpcmFrame?(encoded)
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
