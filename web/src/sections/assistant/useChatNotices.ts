/**
 * Important open notifications are the assistant's own lines: they stand in the conversation by
 * time, within the stretch of history already loaded, and not in a search context.
 */
import { computed, type Ref } from 'vue'
import { speaksInChat, type Notification } from '@/sections/notifications/model'
import { useNotifications } from '@/sections/notifications/useNotifications'
import type { Exchange } from './model'

export function useChatNotices(
  /** A message opened from search: its context shows no notices. */
  anchor: Readonly<Ref<string | null>>,
  exchanges: Readonly<Ref<readonly Exchange[]>>,
  /** Earlier pages are left: the oldest loaded message bounds the notices. */
  earlier: Readonly<Ref<boolean>>,
  /** The loaded window reaches the newest message, so newer notices belong in it too. */
  atHead: Readonly<Ref<boolean>>,
) {
  const notifications = useNotifications()
  return computed<Notification[]>(() => {
    if (anchor.value) return []
    const oldest = earlier.value ? exchanges.value[0]?.createdAt : undefined
    const newest = atHead.value ? undefined : exchanges.value.at(-1)?.createdAt
    return notifications.items.value.filter(
      (item) =>
        speaksInChat(item) &&
        (!oldest || Date.parse(item.updated_at) >= Date.parse(oldest)) &&
        (!newest || Date.parse(item.updated_at) <= Date.parse(newest)),
    )
  })
}
