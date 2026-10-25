import { nextTick, onBeforeUnmount, shallowRef, watch, type Ref } from 'vue'
import type {
  LocationQuery,
  LocationQueryRaw,
  RouteLocationNormalized,
  RouteLocationRaw,
} from 'vue-router'
import { dayIn, isIsoDay } from '@/core/dates'
import { DAILY_KINDS, type HistoryKind } from './api'
import { SENSOR_METRICS, type SensorMetric } from './sensors'

export type PersonalSection = 'weight' | 'daily' | 'body' | 'nutrition'
type PersonalChild = 'personal-history' | 'personal-profile' | 'personal-sources' | 'personal-goals'
type PersonalLink = { name: PersonalChild | 'personal'; query?: LocationQueryRaw }
type Query = LocationQuery | LocationQueryRaw

interface ReturnContext {
  day: string
  section: PersonalSection
  key?: string
}
interface ReturnPosition extends ReturnContext {
  offset: number
}
interface PendingReturn {
  path: string
  context: ReturnContext
}

const SECTIONS: readonly PersonalSection[] = ['weight', 'daily', 'body', 'nutrition']
// Geometry belongs only to this tab. URLs retain a useful day/section after a reload.
const positions = new Map<string, ReturnPosition>()
const pendingReturn = shallowRef<PendingReturn | null>(null)

function returnContext(query: Query): ReturnContext | null {
  const day = query.returnDay
  const section = query.returnSection
  if (!isIsoDay(day) || !SECTIONS.includes(section as PersonalSection)) return null
  return {
    day,
    section: section as PersonalSection,
    ...(typeof query.returnKey === 'string' ? { key: query.returnKey } : {}),
  }
}

/** Context alone can be carried between child screens without carrying their filters. */
export function personalReturnQuery(query: Query): LocationQueryRaw {
  const context = returnContext(query)
  return context
    ? {
        returnDay: context.day,
        returnSection: context.section,
        ...(context.key ? { returnKey: context.key } : {}),
      }
    : {}
}

export function personalScreenLink(
  name: PersonalChild,
  day: string,
  section: PersonalSection,
  extraQuery: LocationQueryRaw = {},
): PersonalLink {
  return {
    name,
    query: {
      ...extraQuery,
      returnDay: day,
      returnSection: section,
      returnKey: crypto.randomUUID(),
    },
  }
}

export function personalHistoryLink(
  day: string,
  section: PersonalSection,
  kind:
    | HistoryKind
    | SensorMetric
    | 'derived_scores'
    | 'running_steps'
    | 'running_workouts'
    | 'all' = 'all',
  period?: number,
): PersonalLink {
  return personalScreenLink('personal-history', day, section, {
    kind,
    end: day,
    ...(period ? { period } : {}),
  })
}

/** Call at activation, rather than render time: the owner may have scrolled since rendering. */
export function capturePersonalReturn(link: RouteLocationRaw): void {
  if (typeof link !== 'object' || !('query' in link) || !link.query) return
  const context = returnContext(link.query)
  if (!context?.key) return
  const anchor = document.getElementById(`personal-${context.section}`)
  if (!anchor) return
  positions.set(context.key, { ...context, offset: anchor.getBoundingClientRect().top })
  // Keep long-lived tabs bounded without storing any metric values.
  if (positions.size > 128) positions.delete(positions.keys().next().value!)
}

/** The original overview day is independent of the period currently browsed in history. */
export function personalReturnLink(query: Query, today: string): PersonalLink {
  const context = returnContext(query)
  if (context && context.day <= today) {
    return {
      name: 'personal',
      query: { day: context.day, ...personalReturnQuery(query) },
    }
  }
  if (isIsoDay(query.end) && query.end <= today) {
    const section: PersonalSection =
      query.kind === 'body'
        ? 'body'
        : [
              ...DAILY_KINDS,
              ...SENSOR_METRICS,
              'derived_scores',
              'running_steps',
              'running_workouts',
            ].includes(String(query.kind))
          ? 'daily'
          : 'weight'
    return {
      name: 'personal',
      query: { day: query.end, returnDay: query.end, returnSection: section },
    }
  }
  return { name: 'personal' }
}

/** Let Personal restore after its data has rendered, instead of racing router's top: 0. */
export function schedulePersonalScroll(
  to: RouteLocationNormalized,
  from: RouteLocationNormalized,
  today = dayIn('UTC'),
  restoreSaved = false,
): boolean {
  if (to.name !== 'personal' || to.hash) {
    pendingReturn.value = null
    return false
  }
  const selectedDay = isIsoDay(to.query.day) && to.query.day <= today ? to.query.day : today
  const matchesDay = (context: ReturnContext | null) => context && selectedDay === context.day
  const fromContext = restoreSaved && from.name !== 'personal' ? returnContext(from.query) : null
  const context = matchesDay(fromContext) ? fromContext : returnContext(to.query)
  if (!context || !matchesDay(context)) {
    pendingReturn.value = null
    return false
  }
  // Intent consumption replaces the same overview route before the initial restore completes.
  // Subsequent query changes on Personal should not send the owner back to an old anchor.
  if (from.name === 'personal' && !pendingReturn.value) return false
  pendingReturn.value = { path: to.fullPath, context }
  return true
}

export function usePersonalScrollRestore(
  route: RouteLocationNormalized,
  ready: Ref<boolean>,
): void {
  let mounted = true
  onBeforeUnmount(() => (mounted = false))
  watch(
    [ready, () => route.fullPath, pendingReturn],
    async ([settled, path, pending]) => {
      if (!settled || !pending || pending.path !== path) return
      await nextTick()
      // ResizeObserver (including the sticky date) and the browser layout settle before measuring.
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
      if (!mounted || !ready.value || route.fullPath !== path || pendingReturn.value !== pending)
        return
      const anchor = document.getElementById(`personal-${pending.context.section}`)
      const remembered = pending.context.key ? positions.get(pending.context.key) : undefined
      const rootStyle = getComputedStyle(document.documentElement)
      const stickyTop =
        Number.parseFloat(rootStyle.scrollPaddingTop) ||
        (Number.parseFloat(rootStyle.getPropertyValue('--app-top')) || 0) +
          (Number.parseFloat(rootStyle.getPropertyValue('--page-sticky-top')) || 0)
      const offset =
        remembered?.day === pending.context.day && remembered.section === pending.context.section
          ? remembered.offset
          : stickyTop
      window.scrollTo({
        top: anchor ? Math.max(0, window.scrollY + anchor.getBoundingClientRect().top - offset) : 0,
        behavior: 'instant',
      })
      pendingReturn.value = null
    },
    { immediate: true, flush: 'post' },
  )
}
