import Foundation

/// Standard Intel/DVI 4-bit IMA-ADPCM codec matching Android's ImaAdpcmCodec.kt byte-for-byte.
/// 20 ms @ 8 kHz mono -> 160 samples (320 bytes PCM16 LE) -> 86-byte plaintext frame.
final class ImaAdpcmCodec {
    static let frameSamples8k = 160
    static let headerBytes = 6
    static let adpcmDataBytes = 80
    static let packetPlaintextBytes = headerBytes + adpcmDataBytes

    private static let indexTable: [Int] = [
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    ]

    private static let stepSizeTable: [Int] = [
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    ]

    private var encPredictor: Int = 0
    private var encStepIndex: Int = 0
    private var encSeq: UInt16 = 0

    func reset() {
        encPredictor = 0
        encStepIndex = 0
        encSeq = 0
    }

    func encodeFrame(pcm8kBytes: Data) -> Data {
        var out = Data(count: Self.packetPlaintextBytes)
        let seq = encSeq
        encSeq = encSeq &+ 1

        out[0] = UInt8(seq & 0xFF)
        out[1] = UInt8((seq >> 8) & 0xFF)
        let predI16 = Int16(clamping: encPredictor)
        out[2] = UInt8(truncatingIfNeeded: predI16 & 0xFF)
        out[3] = UInt8(truncatingIfNeeded: (predI16 >> 8) & 0xFF)
        out[4] = UInt8(min(max(encStepIndex, 0), 88))
        out[5] = 0

        let availSamples = (pcm8kBytes.count & ~1) / 2
        pcm8kBytes.withUnsafeBytes { raw in
            let samplesPtr = raw.bindMemory(to: UInt8.self)
            for i in 0..<Self.frameSamples8k {
                let sample: Int
                if i < availSamples {
                    let lo = Int(samplesPtr[i * 2])
                    let hi = Int(Int8(bitPattern: samplesPtr[i * 2 + 1]))
                    sample = (hi << 8) | lo
                } else {
                    sample = 0
                }

                let step = Self.stepSizeTable[encStepIndex]
                var diff = sample - encPredictor
                var code = 0
                if diff < 0 {
                    code = 8
                    diff = -diff
                }
                var vpdiff = step >> 3
                if diff >= step {
                    code |= 4
                    diff -= step
                    vpdiff += step
                }
                let step2 = step >> 1
                if diff >= step2 {
                    code |= 2
                    diff -= step2
                    vpdiff += step2
                }
                let step4 = step >> 2
                if diff >= step4 {
                    code |= 1
                    vpdiff += step4
                }

                if (code & 8) != 0 {
                    encPredictor -= vpdiff
                } else {
                    encPredictor += vpdiff
                }
                encPredictor = min(max(encPredictor, -32768), 32767)
                encStepIndex = min(max(encStepIndex + Self.indexTable[code], 0), 88)

                let byteIdx = Self.headerBytes + (i / 2)
                if (i & 1) == 0 {
                    out[byteIdx] = UInt8(code & 0x0F)
                } else {
                    out[byteIdx] = UInt8((Int(out[byteIdx]) & 0x0F) | ((code & 0x0F) << 4))
                }
            }
        }
        return out
    }

    func decodeFrame(packet: Data) -> (seq: UInt16, pcm8k: Data)? {
        guard packet.count >= Self.packetPlaintextBytes else { return nil }

        let seq = UInt16(packet[0]) | (UInt16(packet[1]) << 8)
        let predLo = Int(packet[2])
        let predHi = Int(Int8(bitPattern: packet[3]))
        var predictor = (predHi << 8) | predLo
        var stepIndex = min(max(Int(packet[4]), 0), 88)

        var pcmOut = Data(count: Self.frameSamples8k * 2)
        for i in 0..<Self.frameSamples8k {
            let packed = Int(packet[Self.headerBytes + (i / 2)])
            let nibble = (i & 1) == 0 ? (packed & 0x0F) : ((packed >> 4) & 0x0F)

            let step = Self.stepSizeTable[stepIndex]
            var vpdiff = step >> 3
            if (nibble & 4) != 0 { vpdiff += step }
            if (nibble & 2) != 0 { vpdiff += (step >> 1) }
            if (nibble & 1) != 0 { vpdiff += (step >> 2) }

            if (nibble & 8) != 0 {
                predictor -= vpdiff
            } else {
                predictor += vpdiff
            }
            predictor = min(max(predictor, -32768), 32767)
            stepIndex = min(max(stepIndex + Self.indexTable[nibble], 0), 88)

            pcmOut[i * 2] = UInt8(predictor & 0xFF)
            pcmOut[i * 2 + 1] = UInt8((predictor >> 8) & 0xFF)
        }
        return (seq, pcmOut)
    }

    static func downsample16kTo8k(_ pcm16k: Data) -> Data {
        let sampleCount = (pcm16k.count & ~1) / 2
        let outCount = sampleCount / 2
        guard outCount > 0 else { return Data() }
        var out = Data(count: outCount * 2)
        pcm16k.withUnsafeBytes { raw in
            let p = raw.bindMemory(to: UInt8.self)
            for i in 0..<outCount {
                let s0 = (Int(Int8(bitPattern: p[i * 4 + 1])) << 8) | Int(p[i * 4])
                let s1 = (Int(Int8(bitPattern: p[i * 4 + 3])) << 8) | Int(p[i * 4 + 2])
                let avg = min(max((s0 + s1) / 2, -32768), 32767)
                out[i * 2] = UInt8(avg & 0xFF)
                out[i * 2 + 1] = UInt8((avg >> 8) & 0xFF)
            }
        }
        return out
    }

    static func upsample8kTo16k(_ pcm8k: Data) -> Data {
        let sampleCount = (pcm8k.count & ~1) / 2
        guard sampleCount > 0 else { return Data() }
        var out = Data(count: sampleCount * 4)
        pcm8k.withUnsafeBytes { raw in
            let p = raw.bindMemory(to: UInt8.self)
            for i in 0..<sampleCount {
                let cur = (Int(Int8(bitPattern: p[i * 2 + 1])) << 8) | Int(p[i * 2])
                let next: Int
                if i + 1 < sampleCount {
                    next = (Int(Int8(bitPattern: p[(i + 1) * 2 + 1])) << 8) | Int(p[(i + 1) * 2])
                } else {
                    next = cur
                }
                let mid = min(max((cur + next) / 2, -32768), 32767)
                out[i * 4] = UInt8(cur & 0xFF)
                out[i * 4 + 1] = UInt8((cur >> 8) & 0xFF)
                out[i * 4 + 2] = UInt8(mid & 0xFF)
                out[i * 4 + 3] = UInt8((mid >> 8) & 0xFF)
            }
        }
        return out
    }
}
