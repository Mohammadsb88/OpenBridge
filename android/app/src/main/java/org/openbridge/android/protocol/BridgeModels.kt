package org.openbridge.android.protocol

import org.json.JSONObject

object BridgeModels {
    fun buildQrPayload(deviceId: String, deviceName: String, pubKeyBase64: String): String {
        return JSONObject()
            .put("v", BridgeConstants.PROTOCOL_VERSION)
            .put("id", deviceId)
            .put("name", deviceName)
            .put("pub", pubKeyBase64)
            .put("svc", BridgeConstants.SERVICE_UUID.toString())
            .toString()
    }

    fun encryptedEnvelope(nonceB64: String, cipherB64: String): String {
        return JSONObject()
            .put("e", 1)
            .put("n", nonceB64)
            .put("c", cipherB64)
            .toString()
    }

    fun pairAckJson(deviceName: String): String {
        return JSONObject()
            .put("type", "PAIR_ACK")
            .put("deviceName", deviceName)
            .put("ts", System.currentTimeMillis())
            .toString()
    }

    fun pongJson(): String {
        return JSONObject()
            .put("type", "PONG")
            .put("ts", System.currentTimeMillis())
            .toString()
    }

    fun deviceStatusJson(
        battery: Int,
        charging: Boolean,
        signal: Int,
        networkType: String,
        operatorName: String,
        wifiIp: String = ""
    ): String {
        return JSONObject()
            .put("type", "DEVICE_STATUS")
            .put("battery", battery)
            .put("charging", charging)
            .put("signal", signal)
            .put("networkType", networkType)
            .put("operator", operatorName)
            .put("wifiIp", wifiIp)
            .put("audioPort", BridgeConstants.LOCAL_AUDIO_PORT)
            .put("ts", System.currentTimeMillis())
            .toString()
    }

    fun smsReceivedJson(
        id: String,
        sender: String,
        contactName: String?,
        body: String,
        otp: String?,
        ts: Long
    ): String {
        return JSONObject()
            .put("type", "SMS_RECEIVED")
            .put("id", id)
            .put("sender", sender)
            .put("contactName", contactName ?: "")
            .put("body", body)
            .put("otp", otp ?: "")
            .put("ts", ts)
            .toString()
    }

    fun smsSentJson(
        id: String,
        recipient: String,
        body: String,
        success: Boolean,
        error: String = "",
        ts: Long = System.currentTimeMillis()
    ): String {
        return JSONObject()
            .put("type", "SMS_SENT")
            .put("id", id)
            .put("recipient", recipient)
            .put("body", body)
            .put("success", success)
            .put("error", error)
            .put("ts", ts)
            .toString()
    }

    fun callStateJson(
        id: String,
        state: String,
        number: String,
        contactName: String?,
        ts: Long,
        wifiIp: String = ""
    ): String {
        return JSONObject()
            .put("type", "CALL_STATE")
            .put("id", id)
            .put("state", state)
            .put("number", number)
            .put("contactName", contactName ?: "")
            .put("wifiIp", wifiIp)
            .put("audioPort", BridgeConstants.LOCAL_AUDIO_PORT)
            .put("ts", ts)
            .toString()
    }
}
