package org.openbridge.android.protocol

import java.util.UUID

object BridgeConstants {
    val SERVICE_UUID: UUID = UUID.fromString("7F5B0001-9A3C-4E1D-8B2A-1C4D5E6F7A01")
    val RX_CHAR_UUID: UUID = UUID.fromString("7F5B0002-9A3C-4E1D-8B2A-1C4D5E6F7A01")
    val TX_CHAR_UUID: UUID = UUID.fromString("7F5B0003-9A3C-4E1D-8B2A-1C4D5E6F7A01")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val MAGIC_0: Byte = 0x4F // 'O'
    const val MAGIC_1: Byte = 0x42 // 'B'
    const val HEADER_SIZE = 4
    const val MAX_FRAME_SIZE = 65535
    const val PROTOCOL_VERSION = 1

    const val ACTION_ACCEPT = "ACCEPT"
    const val ACTION_REJECT = "REJECT"
    const val ACTION_SILENCE = "SILENCE"
    const val ACTION_QUICK_SMS = "QUICK_SMS"
    const val ACTION_MUTE = "MUTE"
    const val ACTION_UNMUTE = "UNMUTE"
    const val ACTION_SPEAKER_ON = "SPEAKER_ON"
    const val ACTION_SPEAKER_OFF = "SPEAKER_OFF"
    const val LOCAL_AUDIO_PORT = 54810

    const val CALL_RINGING = "RINGING"
    const val CALL_OFFHOOK = "OFFHOOK"
    const val CALL_IDLE = "IDLE"
}
