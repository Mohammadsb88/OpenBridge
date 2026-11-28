# OpenBridge Architecture Invariants

## Target Hardware & Regulatory Constraint (Unregistered iPhone in Iran)
OpenBridge exists specifically because the user's iPhone is **unregistered in Iran** and **will not be registered**:

1. **iPhone Capabilities:** No SIM card, no mobile network access, no native cellular voice/SMS/USSD, and no native mobile data.
2. **Connectivity:** The iPhone connects via **BLE** (including pure-BLE compressed voice + encrypted control/SMS) and optionally the **Android phone's Wi-Fi Hotspot** (for HD audio or SIP internet access).
3. **Bag-Mode UX Requirement:** The Android phone stays stored in a bag/pocket; every everyday action (dialing, answering, dual-SIM selection, SMS send/receive/OTP, call log, contacts sync, USSD, `*21*` call forwarding) must be controllable end-to-end from the iPhone UI.
4. **Honest Audio Constraints:** Do not rely on Android speakerphone when inside a bag, dynamically probe device Audio HAL capabilities (`TYPE_TELEPHONY` / `VOICE_CALL`), and never claim unrooted stock Android 14+ can universally capture/inject raw cellular `VOICE_CALL` audio without probing device support, SIP/VoIP + remote `*21*`, or Bluetooth headset routing.
