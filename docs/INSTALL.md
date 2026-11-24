# Installation Guide / راهنمای نصب

## 1. Android Installation (نصب روی اندروید)

### Option A: Download APK from GitHub Actions / Releases
1. Open the repository's **Actions** tab -> latest **Android Build & Test** workflow -> download `OpenBridge-Android-Debug-APK`.
2. Unzip and install on your Android phone, or via Fedora terminal with ADB:
   ```bash
   adb install -r app-debug.apk
   ```

### Option B: Build Locally on Fedora Linux
```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Note:** Grant Bluetooth, SMS, Phone, and Contacts permissions inside the app, and tap **Allow Unrestricted Battery** so Android does not kill the BLE service in the background.

---

## 2. iOS Installation (نصب روی آیفون بدون نیاز به مک)

1. Open the repository's **Actions** tab -> latest **iOS Build & Test** workflow -> download `OpenBridge-iOS-Unsigned-IPA`.
2. Unzip to get `OpenBridge-unsigned.ipa`.
3. Install onto your iPhone using any free sideloading tool:
   - **SideStore** (recommended: refreshes over Wi-Fi directly on iPhone every 7 days)
   - **AltStore / AltServer-Linux** or **Sideloadly**
4. On your iPhone, go to **Settings -> General -> VPN & Device Management** and trust your Apple ID certificate.

---

## 3. Pairing (جفت‌سازی دو گوشی)

1. On **Android**: Open OpenBridge -> tap **Start Bridge Service**. A QR code will appear.
2. On **iPhone**: Open OpenBridge -> allow Bluetooth & Notifications -> tap **Scan Pairing QR Code** and scan the Android screen.
3. Both devices derive an **E2EE AES-256-GCM** session key via **P-256 ECDH** and verify the connection automatically.
