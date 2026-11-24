import Foundation
import UserNotifications

final class NotificationManager: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationManager()

    static let callCategoryId = "OPENBRIDGE_INCOMING_CALL"
    static let actionRejectId = "CALL_ACTION_REJECT"
    static let actionQuickSmsId = "CALL_ACTION_QUICK_SMS"

    var onCallActionFromNotification: ((String, String) -> Void)?

    func configure() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self

        let rejectAction = UNNotificationAction(
            identifier: Self.actionRejectId,
            title: "Reject",
            options: [.destructive]
        )
        let quickSmsAction = UNNotificationAction(
            identifier: Self.actionQuickSmsId,
            title: "Reply Busy SMS",
            options: []
        )
        let callCategory = UNNotificationCategory(
            identifier: Self.callCategoryId,
            actions: [rejectAction, quickSmsAction],
            intentIdentifiers: [],
            options: []
        )
        center.setNotificationCategories([callCategory])

        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error {
                print("Notification permission error: \(error.localizedDescription)")
            } else {
                print("Notification permission granted: \(granted)")
            }
        }
    }

    func notifySms(_ sms: RelayedSms) {
        let content = UNMutableNotificationContent()
        if !sms.otp.isEmpty {
            content.title = "OTP \(sms.otp) • \(sms.displaySender)"
        } else {
            content.title = sms.displaySender
        }
        content.body = sms.body
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "sms-\(sms.id)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    func notifyIncomingCall(_ call: RelayedCall) {
        let content = UNMutableNotificationContent()
        content.title = "Incoming Call: \(call.displayCaller)"
        content.body = call.number.isEmpty ? "Ringing on companion Android" : call.number
        content.sound = .default
        content.categoryIdentifier = Self.callCategoryId
        content.userInfo = ["number": call.number]

        let request = UNNotificationRequest(
            identifier: "call-\(call.id)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let number = response.notification.request.content.userInfo["number"] as? String ?? ""
        switch response.actionIdentifier {
        case Self.actionRejectId:
            onCallActionFromNotification?("REJECT", number)
        case Self.actionQuickSmsId:
            onCallActionFromNotification?("QUICK_SMS", number)
        default:
            break
        }
        completionHandler()
    }
}
