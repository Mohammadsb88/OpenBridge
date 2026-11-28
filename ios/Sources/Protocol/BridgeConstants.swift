import Foundation
import CoreBluetooth

enum BridgeConstants {
    static let serviceUUID = CBUUID(string: "7F5B0001-9A3C-4E1D-8B2A-1C4D5E6F7A01")
    static let rxCharUUID = CBUUID(string: "7F5B0002-9A3C-4E1D-8B2A-1C4D5E6F7A01")
    static let txCharUUID = CBUUID(string: "7F5B0003-9A3C-4E1D-8B2A-1C4D5E6F7A01")
    static let audioRxCharUUID = CBUUID(string: "7F5B0004-9A3C-4E1D-8B2A-1C4D5E6F7A01")
    static let audioTxCharUUID = CBUUID(string: "7F5B0005-9A3C-4E1D-8B2A-1C4D5E6F7A01")

    static let audioDirAndroidToIos: UInt8 = 0x01
    static let audioDirIosToAndroid: UInt8 = 0x02

    static let restoreIdentifier = "org.openbridge.ios.ble.central"
    static let magic0: UInt8 = 0x4F // 'O'
    static let magic1: UInt8 = 0x42 // 'B'
    static let headerSize = 4
    static let maxFrameSize = 65535
    static let protocolVersion = 1
}
