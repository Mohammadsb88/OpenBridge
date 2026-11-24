# OpenBridge Roadmap

## Phase 0: Protocol & Core Transport (Completed / In Progress)
- [x] Repository setup with GPLv3 license and GitHub Actions CI
- [x] BLE GATT + E2EE (`P-256 ECDH + HKDF-SHA256 + AES-256-GCM`) protocol specification
- [x] iOS `XcodeGen` project for macOS-free Linux development + automated `.ipa` CI builds

## Phase 1: MVP — Continuity Core (No Root / No ADB)
- [x] Secure QR Code pairing between Android and iPhone
- [x] Background BLE Central (iOS) + Foreground GATT Server (Android)
- [x] Instant SMS relay + bilingual (English/Persian) OTP extraction & 1-tap copy
- [x] Incoming Caller ID notifications + Lock-screen notification actions (Reject / Quick SMS)
- [x] Remote dialing from iPhone (executes on Android)
- [x] Android battery, signal, and operator status dashboard on iPhone
- [x] Bilingual UI (English & Persian with RTL support) + Built-in Debug Log viewer

## Phase 2: Enhanced Continuity & Public Store Release
- [ ] Local Wi-Fi Direct / WebSocket high-speed channel for Clipboard & File Sync
- [ ] iOS Live Activities / Dynamic Island & Home Screen Widgets
- [ ] F-Droid, CafeBazaar, Myket, and Apple App Store release preparation

## Phase 3: Modular Voice Plugins
- [ ] **BYO-SIP + CallKit:** Native iOS CallKit calling via user-configured SIP / Call Forwarding
- [ ] **Shizuku / Root Plugin (Optional):** Experimental local audio streaming via Opus/WebRTC
- [ ] **Open Hardware Bridge (Optional):** ESP32 Bluetooth HFP-to-BLE/Wi-Fi firmware

## Phase 4: Optional Encrypted Cloud Relay
- [ ] Zero-Knowledge Relay + APNs/PushKit wake-up for beyond-Bluetooth range
