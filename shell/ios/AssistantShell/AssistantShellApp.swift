import SwiftUI

@main
struct AssistantShellApp: App {
    @UIApplicationDelegateAdaptor(AssistantAppDelegate.self) private var appDelegate
    @StateObject private var connection = ConnectionModel()
    @StateObject private var ambient = AmbientAudio.shared
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
                HStack {
                    Button { ambient.showScreen = true } label: {
                        Label(ambient.recording ? "Идёт запись" : "Микрофон",
                              systemImage: ambient.recording ? "record.circle.fill" : "mic")
                            .foregroundStyle(ambient.recording ? KaiColors.destructive : KaiColors.foreground)
                            .padding(.vertical, 12)
                    }
                    .accessibilityLabel(ambient.recording ? "Микрофон записывает. Открыть управление записью" : "Открыть управление фоновой записью")
                    Spacer()
                    if connection.active != nil {
                        Button { connection.showSettings = true } label: { Image(systemName: "server.rack").padding(12) }
                            .accessibilityLabel("Настройки сервера")
                    }
                }
                .padding(.horizontal, 16).background(.regularMaterial)
            }
            .tint(KaiColors.accent)
            .task { await connection.restore() }
            .sheet(isPresented: $connection.showSettings) { ConnectionScreen(model: connection) }
            .sheet(isPresented: $ambient.showScreen) { AmbientAudioScreen(audio: ambient) }
            .onChange(of: phase) { value in
                if value == .active {
                    NativeNotifications.shared.foreground()
                    ambient.foreground()
                }
            }
        }
    }
}
