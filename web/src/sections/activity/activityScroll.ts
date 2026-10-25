import { nextTick, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import type { LocationQueryRaw, RouteLocationNormalized } from 'vue-router'
import { dayIn, isIsoDay } from '@/core/dates'
import { activityReturnQuery } from './activityNavigation'

// Geometry is scoped to this browser tab and holds no Activity or Personal values.
const positions = new Map<string, { entry: string; offset: number }>()
const key = (query: LocationQueryRaw) =>
  JSON.stringify(Object.entries(query).sort(([a], [b]) => a.localeCompare(b)))
const pending = ref<string | null>(null)

export function activityReadingQuery(
  query: LocationQueryRaw,
  today: string,
): Record<string, string> {
  const tab = ['journal', 'progress', 'exercises'].includes(String(query.tab))
    ? String(query.tab)
    : 'overview'
  const day = isIsoDay(query.day) && query.day <= today ? query.day : today
  const result = activityReturnQuery({ ...query, tab }, today, day)
  if (tab === 'progress' && !result.metric) result.metric = result.exercise ? 'strength' : 'steps'
  return result
}

export function captureActivityPosition(query: LocationQueryRaw, entry: string): void {
  captureActivityAnchor(query, `activity-entry-${entry}`)
}

export function captureActivityAnchor(query: LocationQueryRaw, entry: string): void {
  const anchor = document.getElementById(entry)
  if (!anchor) return
  positions.set(key(query), { entry, offset: anchor.getBoundingClientRect().top })
  if (positions.size > 128) positions.delete(positions.keys().next().value!)
}

/** A detail return waits for the rows to render before restoring their original offset. */
export function scheduleActivityScroll(
  to: RouteLocationNormalized,
  from: RouteLocationNormalized,
  today = dayIn('UTC'),
): boolean {
  pending.value = null
  if (to.name !== 'activity' || from.name !== 'activity-session' || to.hash) return false
  const context = key(activityReadingQuery(to.query, today))
  if (!positions.has(context)) return false
  pending.value = context
  return true
}

export function useActivityScrollRestore(query: Ref<LocationQueryRaw>, ready: Ref<boolean>): void {
  let mounted = true
  onBeforeUnmount(() => (mounted = false))
  watch(
    [query, ready, pending],
    async ([context, settled]) => {
      const selected = key(context)
      if (!settled || pending.value !== selected) return
      await nextTick()
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
      if (!mounted || !ready.value || pending.value !== selected || key(query.value) !== selected)
        return
      const position = positions.get(selected)
      const anchor = position && document.getElementById(position.entry)
      if (anchor && position)
        window.scrollTo({
          top: Math.max(0, window.scrollY + anchor.getBoundingClientRect().top - position.offset),
          behavior: 'instant',
        })
      pending.value = null
    },
    { immediate: true, flush: 'post' },
  )
}
