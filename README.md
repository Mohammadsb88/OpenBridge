# OpenBridge

**100% Open-Source Android-to-iPhone Phone Bridge (`GPLv3`)**

OpenBridge turns any Android smartphone (with active SIM cards) into a private, end-to-end encrypted continuity bridge for an **unregistered or secondary iPhone** (`iOS 17+`). Keep your Android phone inside your bag or pocket and handle calls, SMS, OTP codes, USSD codes, Dual-SIM selection, contacts, and call history directly from your iPhone.

> ⚠️ **Important Note:** OpenBridge does **not** unlock or register an iPhone IMEI with cellular carriers. Instead, your Android phone handles cellular connectivity and bridges everything securely to your iPhone over **BLE** (plus optional **Wi-Fi Hotspot** or **BYO-SIP**).

## Why OpenBridge? (Privacy & Open-Source Guarantee)
- **Zero Cloud Servers & Zero Tracking:** Keys never leave your devices (**ECDH P-256 + HKDF-SHA256 + AES-256-GCM**).
- **100% Auditable GPLv3 Source Code:** No hidden ADB backdoors, no Play Protect malware flags, and zero paid subscriptions.
- **Play-Protect-Clean Privilege Mode:** Uses standard **Shizuku** (`UID 2000`) or **Root** (optional) to probe direct telephony audio, plus built-in **1-Tap Hardware Audio Compatibility Check**.

## Features (`v0.5.0`)
- **Pure-BLE Voice Calls (No Hotspot Needed!) + Wi-Fi HD Auto-Upgrade:** Encrypted `8 kHz IMA-ADPCM` (`~44 kbps` wire rate) over BLE GATT when away from Wi-Fi, automatically upgrading to `16 kHz` PCM over Hotspot Wi-Fi (`port 54810`) when available.
- **Native iOS CallKit Integration:** Answer, reject, mute, switch speaker, or dial SIM contacts directly from the iPhone lock screen and Dialer.
- **Adaptive Telephony Audio Engine (Android 13/14+):** Dynamically probes `VOICE_CALL` / `VOICE_DOWNLINK` and `AudioDeviceInfo.TYPE_TELEPHONY` (`sampleRates`, `channelMasks`) with real-time PCM resampling (works across MediaTek, Qualcomm, Samsung, and Pixel HALs).
- **Conversation-Threaded SMS + OTP Strip:** Two-way SMS over Android SIM 1 / SIM 2, grouped conversation threads, and automatic English/Persian bank OTP detection with 1-tap copy.
- **Dual-SIM, USSD & Remote Call Forwarding:** Run `*140#` / `*100#` / custom USSD codes and toggle `*21*` forwarding to SIP directly from iPhone.
- **Full Sync:** Automatically syncs Android SMS inbox/sent history, recent call logs, and up to 250 contacts over encrypted BLE.
- **Bilingual UI:** Complete **English** and **Persian (`فارسی` RTL)** interface on both Android and iOS.

## Documentation
- [Protocol Specification (BLE E2EE & Audio)](docs/PROTOCOL.md)
- [Project Roadmap](docs/ROADMAP.md)

## Building

### Android (`android/`)
Requires Android SDK (API 35) and JDK 17/21:
```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```
Install via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### iOS (`ios/`)
Generated with [XcodeGen](https://github.com/yonaskolb/XcodeGen) and automatically built on `macos-latest` via GitHub Actions (`.github/workflows/ios-build.yml`), producing an unsigned `.ipa` ready for **Impactor / SideStore / AltStore / Sideloadly**:
```bash
cd ios
xcodegen generate
xcodebuild -project OpenBridge.xcodeproj -scheme OpenBridge -configuration Debug -sdk iphoneos CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

## License
Licensed under the **GNU General Public License v3.0 (GPLv3)**. See [LICENSE](LICENSE).
