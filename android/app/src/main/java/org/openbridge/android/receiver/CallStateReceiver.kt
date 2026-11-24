package org.openbridge.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.data.RelayedEvent
import org.openbridge.android.protocol.BridgeConstants
import org.openbridge.android.protocol.BridgeModels
import org.openbridge.android.service.BridgeRuntime
import java.util.UUID

class CallStateReceiver : BroadcastReceiver() {
    companion object {
        @Volatile
        private var lastState: String = ""
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        BridgeStore.init(context)

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        val mappedState = when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> BridgeConstants.CALL_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> BridgeConstants.CALL_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> BridgeConstants.CALL_IDLE
            else -> return
        }

        // Avoid duplicate emissions without a number when Android fires twice
        if (mappedState == lastState && number.isBlank()) return
        lastState = mappedState

        val contactName = if (number.isNotBlank()) resolveContactName(context, number) else null
        val ts = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        val payload = BridgeModels.callStateJson(
            id = id,
            state = mappedState,
            number = number,
            contactName = contactName,
            ts = ts
        )
        val sent = BridgeRuntime.sendToIphone(payload)

        if (mappedState == BridgeConstants.CALL_RINGING) {
            BridgeStore.addEvent(
                RelayedEvent(
                    id = id,
                    kind = "CALL",
                    title = contactName ?: number.ifBlank { "Incoming Call" },
                    subtitle = "$mappedState ${number.ifBlank { "" }}".trim(),
                    timestamp = ts
                )
            )
        }
        BridgeStore.log("Call state=$mappedState number=${number.ifBlank { "hidden" }} relayed=$sent")
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
