import Foundation
import CallKit
import AVFoundation

final class CallKitManager: NSObject, CXProviderDelegate {
    static let shared = CallKitManager()

    private let provider: CXProvider
    private let callController = CXCallController()
    private(set) var activeCallUUID: UUID?
    private(set) var isAudioSessionActive = false

    var onAnswerCall: ((UUID) -> Void)?
    var onEndCall: ((UUID) -> Void)?
    var onStartOutgoingCall: ((UUID, String) -> Void)?
    var onMuteChanged: ((Bool) -> Void)?
    var onAudioSessionActivated: (() -> Void)?
    var onAudioSessionDeactivated: (() -> Void)?
    var onLog: ((String) -> Void)?

    override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallGroups = 1
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.phoneNumber, .generic]
        config.includesCallsInRecents = true

        self.provider = CXProvider(configuration: config)
        super.init()
        self.provider.setDelegate(self, queue: nil)
    }

    func reportIncomingCall(handle: String, displayName: String, completion: ((UUID?) -> Void)? = nil) {
        if let existing = activeCallUUID {
            onLog?("CallKit already tracking call \(existing.uuidString)")
            completion?(existing)
            return
        }
        let uuid = UUID()
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .phoneNumber, value: handle.isEmpty ? "Unknown" : handle)
        update.localizedCallerName = displayName
        update.hasVideo = false
        update.supportsDTMF = true
        update.supportsHolding = false

        activeCallUUID = uuid
        provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            if let error {
                self?.onLog?("CallKit incoming error: \(error.localizedDescription)")
                self?.activeCallUUID = nil
                completion?(nil)
            } else {
                self?.onLog?("CallKit ringing for \(displayName)")
                completion?(uuid)
            }
        }
    }

    func answerActiveCall() {
        guard let uuid = activeCallUUID else { return }
        let action = CXAnswerCallAction(call: uuid)
        callController.request(CXTransaction(action: action)) { [weak self] error in
            if let error {
                self?.onLog?("CallKit answer error: \(error.localizedDescription)")
            }
        }
    }

    func startOutgoingCall(handle: String, displayName: String) {
        if activeCallUUID != nil {
            endActiveCall()
        }
        let uuid = UUID()
        activeCallUUID = uuid
        let cxHandle = CXHandle(type: .phoneNumber, value: handle)
        let action = CXStartCallAction(call: uuid, handle: cxHandle)
        action.contactIdentifier = displayName

        let transaction = CXTransaction(action: action)
        callController.request(transaction) { [weak self] error in
            if let error {
                self?.onLog?("CallKit outgoing error: \(error.localizedDescription)")
                self?.activeCallUUID = nil
            } else {
                self?.provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
            }
        }
    }

    func setMuted(_ muted: Bool) {
        guard let uuid = activeCallUUID else { return }
        let action = CXSetMutedCallAction(call: uuid, muted: muted)
        callController.request(CXTransaction(action: action)) { [weak self] error in
            if let error {
                self?.onLog?("CallKit mute error: \(error.localizedDescription)")
            }
        }
    }

    func reportOutgoingConnected() {
        guard let uuid = activeCallUUID else { return }
        provider.reportOutgoingCall(with: uuid, connectedAt: Date())
    }

    func endActiveCall() {
        guard let uuid = activeCallUUID else { return }
        let action = CXEndCallAction(call: uuid)
        callController.request(CXTransaction(action: action)) { [weak self] error in
            if let error {
                self?.onLog?("CallKit end error: \(error.localizedDescription)")
                self?.provider.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
            }
            self?.activeCallUUID = nil
        }
    }

    func reportRemoteEnded() {
        guard let uuid = activeCallUUID else { return }
        provider.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
        activeCallUUID = nil
        isAudioSessionActive = false
    }

    // MARK: - CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        activeCallUUID = nil
        isAudioSessionActive = false
        onAudioSessionDeactivated?()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        configureAudioCategoryOnly()
        onStartOutgoingCall?(action.callUUID, action.handle.value)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        configureAudioCategoryOnly()
        onAnswerCall?(action.callUUID)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        onMuteChanged?(action.isMuted)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        onEndCall?(action.callUUID)
        activeCallUUID = nil
        isAudioSessionActive = false
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        isAudioSessionActive = true
        onLog?("CallKit AVAudioSession activated")
        onAudioSessionActivated?()
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        isAudioSessionActive = false
        onLog?("CallKit AVAudioSession deactivated")
        onAudioSessionDeactivated?()
    }

    private func configureAudioCategoryOnly() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.allowBluetooth, .allowBluetoothA2DP]
        )
        try? session.setPreferredSampleRate(16000)
        try? session.setPreferredIOBufferDuration(0.02)
    }
}
