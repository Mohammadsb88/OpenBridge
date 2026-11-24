import Foundation

enum FrameCodec {
    static func encode(_ payload: Data) -> Data {
        precondition(payload.count <= BridgeConstants.maxFrameSize, "Payload too large")
        var frame = Data(capacity: BridgeConstants.headerSize + payload.count)
        frame.append(BridgeConstants.magic0)
        frame.append(BridgeConstants.magic1)
        frame.append(UInt8((payload.count >> 8) & 0xFF))
        frame.append(UInt8(payload.count & 0xFF))
        frame.append(payload)
        return frame
    }

    static func chunk(_ frame: Data, maxChunkSize: Int) -> [Data] {
        let safeChunk = max(20, maxChunkSize)
        if frame.count <= safeChunk { return [frame] }
        var chunks: [Data] = []
        var offset = 0
        while offset < frame.count {
            let end = min(offset + safeChunk, frame.count)
            chunks.append(frame.subdata(in: offset..<end))
            offset = end
        }
        return chunks
    }

    final class StreamDecoder {
        private var buffer = Data()
        private let lock = NSLock()

        func append(_ data: Data) -> [Data] {
            lock.lock()
            defer { lock.unlock() }

            if !data.isEmpty {
                buffer.append(data)
            }
            var frames: [Data] = []
            var offset = 0

            while buffer.count - offset >= BridgeConstants.headerSize {
                let b0 = buffer[buffer.startIndex + offset]
                let b1 = buffer[buffer.startIndex + offset + 1]
                if b0 != BridgeConstants.magic0 || b1 != BridgeConstants.magic1 {
                    offset += 1
                    continue
                }
                let hi = Int(buffer[buffer.startIndex + offset + 2])
                let lo = Int(buffer[buffer.startIndex + offset + 3])
                let len = (hi << 8) | lo

                if buffer.count - offset < BridgeConstants.headerSize + len {
                    break
                }
                let start = buffer.startIndex + offset + BridgeConstants.headerSize
                frames.append(Data(buffer[start..<(start + len)]))
                offset += BridgeConstants.headerSize + len
            }

            if offset > 0 {
                if offset < buffer.count {
                    buffer = Data(buffer.dropFirst(offset))
                } else {
                    buffer.removeAll(keepingCapacity: true)
                }
            }
            return frames
        }

        func reset() {
            lock.lock()
            defer { lock.unlock() }
            buffer.removeAll(keepingCapacity: false)
        }
    }
}
