import Foundation
import UserNotifications

/// No networking: APNs already carries the complete encrypted notification body.
final class NotificationService: UNNotificationServiceExtension {
    private let lock = NSLock()
    private var handler: ((UNNotificationContent) -> Void)?
    private var fallback: UNNotificationContent?

    override func didReceive(_ request: UNNotificationRequest,
                             withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        let neutral = Self.neutralContent()
        lock.lock()
        handler = contentHandler
        fallback = neutral
        lock.unlock()

        do {
            guard let envelope = NativePushEnvelope(userInfo: request.content.userInfo),
                  let binding = try SharedPushStore.activeBinding(), binding.hasActiveSession(),
                  envelope.bindingID == binding.bindingID,
                  let keys = try SharedPushStore.load(bindingID: binding.bindingID)
            else { finish(neutral); return }
            let plaintext = try PushCrypto.decrypt(envelope.ciphertext, keys: keys)
            let message = try JSONDecoder().decode(PushNotificationMessage.self, from: plaintext)
            guard message.valid else { finish(neutral); return }

            let content = Self.neutralContent()
            // Only authenticated decryption can supply a notification destination.
            var info: [AnyHashable: Any] = ["binding_id": binding.bindingID, "id": message.id]
            if let link = PushCrypto.safeRelativeLink(message.link) { info["link"] = link }
            content.userInfo = info
            if binding.previews {
                content.title = message.title
                content.body = message.body
            }
            // A logout, server switch or privacy change during decryption invalidates the result.
            guard let current = try SharedPushStore.activeBinding(), current == binding,
                  current.hasActiveSession() else { finish(neutral); return }
            finish(content)
        } catch {
            // Keychain unavailable before the first unlock, invalid ciphertext and stale bindings
            // all use the same privacy-preserving content. Never log plaintext or key material.
            finish(neutral)
        }
    }

    override func serviceExtensionTimeWillExpire() {
        lock.lock()
        let content = fallback
        lock.unlock()
        if let content { finish(content) }
    }

    private func finish(_ content: UNNotificationContent) {
        lock.lock()
        let delivery = handler
        handler = nil
        fallback = nil
        lock.unlock()
        delivery?(content)
    }

    private static func neutralContent() -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = "Bro"
        content.body = "Новое уведомление"
        content.sound = .default
        return content
    }
}
