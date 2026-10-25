import { useDocumentVisibility } from '@vueuse/core'
import { toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue'
import { api } from '@/core/client'

/**
 * Place a keyed marker immediately after the latest fetched message. Pass only
 * that page's last message ID, the actual scroll root, and enabled=false while a
 * discussion tab is hidden. Fetching history alone never acknowledges it.
 */
export function useChannelRead(
  channel: MaybeRefOrGetter<string | null>,
  throughMessage: MaybeRefOrGetter<string | null>,
  marker: Readonly<Ref<HTMLElement | null>>,
  options: {
    root?: Readonly<Ref<HTMLElement | null>>
    enabled?: MaybeRefOrGetter<boolean>
    onRead?: () => void
  } = {},
) {
  const visibility = useDocumentVisibility()
  const acknowledged = new Set<string>()
  const pending = new Set<string>()

  watch(
    () =>
      [
        toValue(channel),
        toValue(throughMessage),
        marker.value,
        options.root?.value,
        options.enabled === undefined || toValue(options.enabled),
        visibility.value,
      ] as const,
    ([channelId, messageId, element, root, enabled, visible], _, cleanup) => {
      if (!channelId || !messageId || !element || !enabled || visible !== 'visible') return
      if (typeof IntersectionObserver === 'undefined') return
      const key = `${channelId}:${messageId}`
      let current = true
      const observer = new IntersectionObserver(
        (entries) => {
          if (
            !current ||
            visibility.value !== 'visible' ||
            toValue(channel) !== channelId ||
            toValue(throughMessage) !== messageId ||
            marker.value !== element ||
            (options.enabled !== undefined && !toValue(options.enabled)) ||
            acknowledged.has(key) ||
            pending.has(key) ||
            !entries.some((entry) => entry.target === element && entry.isIntersecting)
          )
            return
          pending.add(key)
          void api
            .post(`/v1/channels/${encodeURIComponent(channelId)}/read`, {
              through_message_id: messageId,
            })
            .then(() => {
              acknowledged.add(key)
              options.onRead?.()
            })
            .catch(() => undefined)
            .finally(() => pending.delete(key))
        },
        { root: root ?? null },
      )
      observer.observe(element)
      cleanup(() => {
        current = false
        observer.disconnect()
      })
    },
    { immediate: true, flush: 'post' },
  )
}
