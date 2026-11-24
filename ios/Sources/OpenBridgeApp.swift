import SwiftUI

@main
struct OpenBridgeApp: App {
    @StateObject private var bridge = BridgeManager.shared

    init() {
        NotificationManager.shared.configure()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bridge)
        }
    }
}
