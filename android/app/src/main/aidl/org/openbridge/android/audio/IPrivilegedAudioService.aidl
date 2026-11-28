package org.openbridge.android.audio;

interface IPrivilegedAudioService {
    int startAudioCapture();
    void stopAudioCapture();
    String getCaptureStatus();
    String probeAudioCapabilities();
    void destroy();
}
