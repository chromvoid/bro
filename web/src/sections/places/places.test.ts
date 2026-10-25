import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { RouterLinkStub, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '@/i18n'
import { Day, timeline } from './api'
import PlaceDayView from './PlaceDayView.vue'
import PlacesView from './PlacesView.vue'

const state = vi.hoisted(() => ({
  days: null as unknown,
  day: null as unknown,
  drawn: [] as string[],
}))
vi.mock('leaflet', () => {
  const layer = () => ({ addTo: () => layer() })
  return {
    map: () => ({
      attributionControl: { setPrefix: () => undefined },
      fitBounds: () => void state.drawn.push('fit'),
      invalidateSize: () => void state.drawn.push('resize'),
      remove: () => undefined,
    }),
    tileLayer: (url: string) => (state.drawn.push(url), layer()),
    polyline: (_line: unknown, options: { className: string }) => (
      state.drawn.push(`track:${options.className}`),
      layer()
    ),
    circleMarker: (_at: unknown, options: { className: string }) => (
      state.drawn.push(`stay:${options.className}`),
      layer()
    ),
    latLng: (latitude: number, longitude: number) => [latitude, longitude],
    latLngBounds: (points: unknown[]) => points,
  }
})
vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  readDays: async () => state.days,
  readDay: async () => state.day,
}))
vi.mock('vue-router', async (original) => ({
  ...(await original<typeof import('vue-router')>()),
  useRoute: () => ({ params: { date: '2026-10-09' } }),
}))

const DAY = Day.parse({
  date: '2026-10-09',
  timezone: 'Europe/Moscow',
  points: 14,
  labels_pending: 1,
  stays: [
    {
      from: '2026-10-09T07:30:00+00:00',
      to: '2026-10-09T09:00:00+00:00',
      minutes: 90,
      latitude: 55.758,
      longitude: 37.62,
      place: null,
    },
    {
      from: '2026-10-09T06:00:00+00:00',
      to: '2026-10-09T07:00:00+00:00',
      minutes: 60,
      latitude: 55.73812,
      longitude: 37.58004,
      place: 'улица Льва Толстого, 16, Москва',
    },
  ],
  moves: [{ from: '2026-10-09T07:00:00+00:00', to: '2026-10-09T07:30:00+00:00', distance_m: 3360 }],
  track: [[55.73812, 37.58004, '2026-10-09T06:00:00+00:00']],
})

/** The map container's size as the browser would lay it out; jsdom lays out nothing. */
function size(width: number, height: number) {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(width)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(height)
}

let resized: () => void = () => undefined

function show(component: typeof PlacesView | typeof PlaceDayView) {
  return mount(component, {
    global: {
      plugins: [i18n, [VueQueryPlugin, { queryClient: new QueryClient() }]],
      stubs: { PageHeader: true, RouterLink: RouterLinkStub },
    },
  })
}

describe('movement history', () => {
  beforeEach(() => {
    state.days = { timezone: 'Europe/Moscow', days: [{ date: '2026-10-08', points: 21 }] }
    state.day = DAY
    state.drawn = []
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: ResizeObserverCallback) {
          resized = () => callback([], this as unknown as ResizeObserver)
        }
        observe = vi.fn()
        disconnect = vi.fn()
      },
    )
    size(640, 288)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('orders a day into stays and moves by time', () => {
    expect(timeline(DAY).map((entry) => entry.kind)).toEqual(['stay', 'move', 'stay'])
  })

  it('lists the recorded days, without GPS point counts', async () => {
    const wrapper = show(PlacesView)
    await flushPromises()
    const list = wrapper.get('[data-testid="places-days"]')
    expect(list.text()).toContain('8 октября')
    expect(list.text()).not.toContain('точ')
    expect(wrapper.findComponent(RouterLinkStub).props('to')).toEqual({
      name: 'places-day',
      params: { date: '2026-10-08' },
    })
  })

  it('says when nothing is recorded yet', async () => {
    state.days = { timezone: 'Europe/Moscow', days: [] }
    const wrapper = show(PlacesView)
    await flushPromises()
    expect(wrapper.text()).toContain('Перемещений пока нет')
  })

  it('shows a day in local times with addresses, durations and distances', async () => {
    const wrapper = show(PlaceDayView)
    await flushPromises()
    const rows = wrapper.findAll('[data-testid="places-timeline"] li')
    expect(rows.map((row) => row.attributes('data-kind'))).toEqual(['stay', 'move', 'stay'])
    expect(rows[0]!.text()).toContain('улица Льва Толстого, 16, Москва')
    expect(rows[0]!.text()).toContain('09:00–10:00')
    expect(rows[0]!.text()).toContain('1 ч')
    expect(rows[1]!.text()).toContain('3,4 км')
    expect(rows[2]!.text()).toContain('Адрес уточняется')
    expect(wrapper.text()).toContain('© OpenStreetMap contributors')
  })

  it('draws the day on a map with tiles from this server and token colours', async () => {
    const day = Day.parse({
      ...DAY,
      track: [
        [55.73812, 37.58004, '2026-10-09T06:00:00+00:00'],
        [55.758, 37.62, '2026-10-09T07:30:00+00:00'],
      ],
    })
    state.day = day
    const wrapper = show(PlaceDayView)
    await flushPromises()
    // Its own stacking context keeps Leaflet's z-indexed panes under the launch screen and sheets.
    expect(wrapper.get('[data-testid="places-map"]').classes()).toContain('isolate')
    expect(state.drawn).toEqual([
      '/v1/maps/osm/{z}/{x}/{y}.png',
      'track:stroke-chart-1',
      'stay:stroke-chart-1 fill-chart-1',
      'stay:stroke-chart-1 fill-chart-1',
      'fit',
    ])
  })

  it('makes the map once its container has a size and follows the size after that', async () => {
    size(0, 0)
    show(PlaceDayView)
    await flushPromises()
    expect(state.drawn).toEqual([])
    size(640, 288)
    resized()
    expect(state.drawn).toEqual([
      '/v1/maps/osm/{z}/{x}/{y}.png',
      'stay:stroke-chart-1 fill-chart-1',
      'stay:stroke-chart-1 fill-chart-1',
      'fit',
    ])
    resized()
    expect(state.drawn.at(-1)).toBe('resize')
    expect(state.drawn.filter((step) => step === 'fit')).toHaveLength(1)
  })
})
