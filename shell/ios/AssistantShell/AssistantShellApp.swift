import SwiftUI

@main
struct AssistantShellApp: App {
    @UIApplicationDelegateAdaptor(AssistantAppDelegate.self) private var appDelegate
    @StateObject private var connection = ConnectionModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            Group {
                if let active = connection.active {
                    ShellView(connection: active, openConnection: { connection.showSettings = true })
                        .id(active.id)
                } else {
                    ConnectionScreen(model: connection)
                }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                if connection.active != nil {
                    HStack {
                        Spacer()
                        Button { connection.showSettings = true } label: { Image(systemName: "server.rack").padding(12) }
                            .accessibilityLabel("Настройки сервера")
                    }
                    .padding(.horizontal, 16).background(.regularMaterial)
                }
            }
            .tint(KaiColors.accent)
            .task { await connection.restore() }
            .sheet(isPresented: $connection.showSettings) { ConnectionScreen(model: connection) }
            .onChange(of: phase) { value in
                if value == .active { NativeNotifications.shared.foreground() }
            }
        }
    }
}
