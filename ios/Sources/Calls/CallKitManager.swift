import Foundation
import CallKit
import AVFoundation

final class CallKitManager: NSObject, CXProviderDelegate {
    static let shared = CallKitManager()

    private let provider: CXProvider
    private let callController = CXCallController()
    private var activeCallUUID: UUID?

    var onAnswerCall: ((UUID) -> Void)?
    var onEndCall: ((UUID) -> Void)?
    var onStartOutgoingCall: ((UUID, String) -> Void)?
    var onAudioSessionActivated: (() -> Void)?
    var onLog: ((String) -> Void)?

    override init() {
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.phoneNumber, .generic]
        config.includesCallsInRecents = true

        self.provider = CXProvider(configuration: config)
        super.init()
        self.provider.setDelegate(self, queue: nil)
    }

    func reportIncomingCall(handle: String, displayName: String, completion: ((UUID?) -> Void)? = nil) {
        let uuid = UUID()
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .phoneNumber, value: handle.isEmpty ? "Unknown" : handle)
        update.localizedCallerName = displayName
        update.hasVideo = false

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

    func startOutgoingCall(handle: String, displayName: String) {
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
    }

    // MARK: - CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        activeCallUUID = nil
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        configureAudioSession()
        onStartOutgoingCall?(action.callUUID, action.handle.value)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        configureAudioSession()
        onAnswerCall?(action.callUUID)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        onEndCall?(action.callUUID)
        activeCallUUID = nil
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        onAudioSessionActivated?()
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
        try? session.setActive(true)
    }
}
