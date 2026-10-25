/** News while the app is open: a notification that arrives, or comes back, pops up once. */
import { watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import { appLink, freshNotifications } from './model'
import { useNotifications } from './useNotifications'

const SHOWN_AT_ONCE = 3

export function useNotificationToasts() {
  const { t } = useI18n()
  const router = useRouter()
  const { list, read } = useNotifications()
  let seen: Map<string, string> | null = null

  // A push tapped while the app was open elsewhere: its service worker asks this page to go there.
  if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator)
    navigator.serviceWorker.addEventListener('message', (event: MessageEvent) => {
      const data = event.data as { type?: unknown; link?: unknown } | null
      const link =
        data?.type === 'notification.open' && typeof data.link === 'string'
          ? appLink(data.link)
          : null
      if (link) void router.push(link)
    })

  watch(
    list.data,
    (data) => {
      if (!data) return // not loaded yet: the first list is what was already waiting
      const result = freshNotifications(seen, data.notifications)
      seen = result.seen
      const fresh = result.fresh
      for (const notification of fresh.slice(0, SHOWN_AT_ONCE)) {
        const link = appLink(notification.link)
        const urgent = notification.severity === 'urgent'
        const show = notification.severity === 'info' ? toast.info : toast.warning
        show(notification.title, {
          id: notification.id,
          description: notification.body || undefined,
          // An urgent one waits for the person; the rest leave on their own.
          duration: urgent ? Infinity : 8000,
          action: link
            ? {
                label: t('notifications.open'),
                onClick: () => void read([notification.id]).then(() => router.push(link)),
              }
            : undefined,
        })
        if (urgent) navigator.vibrate?.([200, 100, 200])
      }
      if (fresh.length > SHOWN_AT_ONCE)
        toast.info(t('notifications.more', { count: fresh.length - SHOWN_AT_ONCE }), {
          action: {
            label: t('notifications.openAll'),
            onClick: () => void router.push({ name: 'notifications' }),
          },
        })
    },
    { immediate: true },
  )
}
