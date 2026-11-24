package org.openbridge.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.data.RelayedEvent
import org.openbridge.android.protocol.BridgeModels
import org.openbridge.android.protocol.OtpParser
import org.openbridge.android.service.BridgeRuntime
import java.util.UUID

class SmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        BridgeStore.init(context)

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val grouped = linkedMapOf<String, StringBuilder>()
        var timestamp = System.currentTimeMillis()
        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: sms.originatingAddress ?: "Unknown"
            timestamp = sms.timestampMillis.takeIf { it > 0 } ?: timestamp
            grouped.getOrPut(sender) { StringBuilder() }.append(sms.displayMessageBody ?: "")
        }

        for ((sender, bodyBuilder) in grouped) {
            val body = bodyBuilder.toString()
            val contactName = resolveContactName(context, sender)
            val otp = OtpParser.extractOtp(body)
            val id = UUID.randomUUID().toString()

            val payload = BridgeModels.smsReceivedJson(
                id = id,
                sender = sender,
                contactName = contactName,
                body = body,
                otp = otp,
                ts = timestamp
            )
            val sent = BridgeRuntime.sendToIphone(payload)
            BridgeStore.addEvent(
                RelayedEvent(
                    id = id,
                    kind = "SMS",
                    title = contactName ?: sender,
                    subtitle = body,
                    otp = otp,
                    timestamp = timestamp
                )
            )
            BridgeStore.log("SMS from $sender (otp=${otp ?: "none"}) relayed=$sent")
        }
    }

    private fun resolveContactName(context: Context, phone: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phone)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
