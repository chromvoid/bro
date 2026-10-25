/*
 * Web Push for the assistant (docs/notifications-spec.md §6). The browser decrypts the message
 * (RFC 8291); this worker only shows it and opens the app where it points. It caches nothing.
 */
const LINK = /^\/(?!\/)[A-Za-z0-9/_-]*$/
// Other apps share this origin; the home dashboard and their windows do not handle our messages.
const APP_PATH =
  /^\/(?:assistant|today|food|tasks|timers|notifications|circle|personal|settings|admin|accounts|workers|plugins|welcome|no-access|login|_ui|app|chat|community|modules)(?:\/|$)/

function notificationLink(value) {
  return typeof value === 'string' && LINK.test(value) && APP_PATH.test(value)
    ? value
    : '/notifications'
}

self.addEventListener('push', (event) => {
  let message = {}
  try {
    message = event.data ? event.data.json() : {}
  } catch {
    message = {}
  }
  const urgent = message.severity === 'urgent'
  event.waitUntil(
    self.registration.showNotification(message.title || 'Bro', {
      body: message.body || '',
      tag: message.id,
      renotify: urgent,
      requireInteraction: urgent,
      data: { link: notificationLink(message.link) },
    }),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const link = notificationLink(event.notification.data?.link)
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      const open = windows.find((client) => {
        const url = new URL(client.url)
        return url.origin === self.location.origin && APP_PATH.test(url.pathname)
      })
      if (open) {
        await open.focus()
        open.postMessage({ type: 'notification.open', link })
      } else {
        await self.clients.openWindow(link)
      }
    })(),
  )
})
