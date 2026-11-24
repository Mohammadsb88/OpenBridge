# OpenBridge

**Open-source Android-to-iPhone Continuity & Phone Bridge** (`GPLv3`)

OpenBridge lets an Android smartphone securely act as a companion phone bridge for an iPhone over a direct, end-to-end encrypted **Bluetooth Low Energy (BLE)** link — **no cloud servers, no root, and no ADB/Wireless Debugging required**.

> ⚠️ **Important Note:** OpenBridge does **not** bypass carrier hardware registration. It links your existing Android phone (with an active SIM) to your iPhone so you can leave your Android phone in your pocket or bag while managing SMS, OTP codes, and call alerts/controls directly from your iPhone.

## Features (Phase 1 MVP)
- **End-to-End Encrypted (E2EE):** QR-code pairing using **ECDH P-256 + HKDF-SHA256 + AES-256-GCM** stored locally on-device.
- **Instant SMS & OTP Sync:** Receives SMS messages on iPhone in real time with automatic **English & Persian OTP code detection** and 1-tap copy.
- **Call Alerts & Remote Control:** Instant incoming Caller ID notifications on iPhone with **Reject**, **Quick Reply SMS**, and **Remote Dial** (initiates call on Android — great with Multipoint earbuds).
- **Device Status Dashboard:** Live Android battery percentage, charging state, signal bars, and mobile operator name on iPhone.
- **Bilingual (EN / FA):** Full English and Persian (`فارسی`) UI with RTL support.
- **Built-in Debug Console:** Live BLE & protocol log viewer in both apps for easy troubleshooting.

## Documentation
- [Protocol Specification](docs/PROTOCOL.md)
- [Project Roadmap](docs/ROADMAP.md)

## Building

### Android (`android/`)
Requires Android SDK (API 35) and JDK 17/21:
```bash
cd android
./gradlew assembleDebug
```
Install via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### iOS (`ios/`)
Generated with [XcodeGen](https://github.com/yonaskolb/XcodeGen) and automatically built on `macos-latest` via GitHub Actions (`.github/workflows/ios-build.yml`), producing an unsigned `.ipa` ready for **SideStore / AltStore / Sideloadly**:
```bash
cd ios
xcodegen generate
xcodebuild -project OpenBridge.xcodeproj -scheme OpenBridge -configuration Debug -sdk iphoneos CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

## License
Licensed under the **GNU General Public License v3.0 (GPLv3)**. See [LICENSE](LICENSE).
