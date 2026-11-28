# OpenBridge Roadmap

## Phase 0 & 1: E2EE BLE Protocol & Core Continuity (Completed)
- [x] GPLv3 open-source repository + GitHub Actions CI for Android APK & iOS IPA
- [x] BLE GATT + E2EE (`P-256 ECDH + HKDF-SHA256 + AES-256-GCM`) QR pairing
- [x] Background BLE Central (iOS) + Foreground GATT Server (Android)
- [x] Two-way SMS + English/Persian OTP detection + CallKit controls

## Phase 2: Bag-Mode & Daily Control (Completed in v0.4.0)
- [x] Dual-SIM selection (Dialer, SMS, USSD)
- [x] Encrypted sync for Android Contacts, SMS Inbox/Sent, and Call Logs
- [x] Remote USSD execution (`*140#`, `*100#`, etc.) & `*21*` Call Forwarding from iPhone
- [x] Built-in SIP / VoIP client (`CallKit` + `G.711`)

## Phase 3: Universal Public Release (`v0.5.0` — Current)
- [x] Consistent community APK signing keystore in CI for seamless upgrades
- [ ] Adaptive `UID 2000` (Shizuku) / Root `TYPE_TELEPHONY` + `VOICE_CALL` audio probing & PCM resampler
- [ ] 1-tap **Hardware Audio Compatibility Check** on Android + synced verdict to iOS
- [ ] Encrypted **Pure-BLE Voice (`IMA-ADPCM 8kHz`)** with automatic Wi-Fi HD (`16kHz`) upgrade
- [ ] Conversation-threaded SMS UI on iOS + Manufacturer onboarding checklist
- [ ] Automated GitHub Releases on version tags

## Phase 4: Future Enhancements
- [ ] Optional Bluetooth HFP hardware bridge firmware (ESP32) for locked OEMs
- [ ] iOS Live Activities / Dynamic Island & Home Screen OTP Widgets
- [ ] Optional self-hosted encrypted relay for beyond-Bluetooth range
