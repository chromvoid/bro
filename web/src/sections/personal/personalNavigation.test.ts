import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, reactive, ref, type PropType, type Ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LocationQuery, RouteLocationNormalized } from 'vue-router'
import {
  capturePersonalReturn,
  personalHistoryLink,
  personalReturnLink,
  personalReturnQuery,
  schedulePersonalScroll,
  usePersonalScrollRestore,
} from './personalNavigation'

function route(name: string, query: LocationQuery = {}, fullPath = `/${name}`) {
  return { name, query, fullPath, hash: '' } as RouteLocationNormalized
}
const ScrollHarness = defineComponent({
  props: {
    current: { type: Object as PropType<RouteLocationNormalized>, required: true },
    ready: { type: Object as PropType<Ref<boolean>>, required: true },
    anchor: { type: String, required: true },
  },
  setup(props) {
    usePersonalScrollRestore(props.current, props.ready)
  },
  template: '<section :id="anchor" />',
})
const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  schedulePersonalScroll(route('today'), route('personal'))
  document.documentElement.style.removeProperty('scroll-padding-top')
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Personal return navigation', () => {
  it('keeps overview day and section separate from the browsed history range', () => {
    const link = personalHistoryLink('2026-10-03', 'body', 'body', 365)
    expect(link).toMatchObject({
      name: 'personal-history',
      query: {
        kind: 'body',
        end: '2026-10-03',
        period: 365,
        returnDay: '2026-10-03',
        returnSection: 'body',
        returnKey: expect.any(String),
      },
    })
    const browsed = { ...link.query, kind: 'sleep', period: 7, end: '2026-09-20' }
    expect(personalReturnLink(browsed, '2026-10-05')).toEqual({
      name: 'personal',
      query: { day: '2026-10-03', ...personalReturnQuery(link.query!) },
    })
    expect(personalReturnQuery(browsed)).not.toHaveProperty('end')
    expect(personalReturnQuery(browsed)).not.toHaveProperty('kind')
  })

  it('uses a valid history end when opened directly, and rejects malformed/future context', () => {
    expect(personalReturnLink({ end: '2026-10-01', kind: 'steps' }, '2026-10-05')).toEqual({
      name: 'personal',
      query: { day: '2026-10-01', returnDay: '2026-10-01', returnSection: 'daily' },
    })
    expect(
      personalReturnLink(
        { returnDay: '2027-01-01', returnSection: 'daily', end: '2026-10-02' },
        '2026-10-05',
      ),
    ).toMatchObject({ query: { day: '2026-10-02' } })
    expect(
      personalReturnLink({ returnDay: 'invalid', returnSection: '#other' }, '2026-10-05'),
    ).toEqual({ name: 'personal' })
    expect(personalReturnQuery({ returnDay: ['2026-10-01'], returnSection: 'body' })).toEqual({})
  })

  it('leaves unrelated routes, hashes and changes of overview day to normal router scrolling', () => {
    const context = { returnDay: '2026-10-03', returnSection: 'daily' }
    const history = route('personal-history', context)
    expect(schedulePersonalScroll(route('today'), history)).toBe(false)
    expect(
      schedulePersonalScroll({ ...route('personal', context), hash: '#custom' }, history),
    ).toBe(false)
    expect(schedulePersonalScroll(route('personal', { day: '2026-10-04' }), history)).toBe(false)
    expect(schedulePersonalScroll(route('personal'), history, '2026-10-05', true)).toBe(false)
    expect(
      schedulePersonalScroll(
        route('personal', { day: '2027-01-01', returnDay: '2027-01-01', returnSection: 'daily' }),
        history,
        '2026-10-05',
      ),
    ).toBe(false)
  })

  it('waits for data and restores the activated anchor offset on browser back', async () => {
    const link = personalHistoryLink('2026-10-03', 'daily', 'sleep')
    const current = reactive(route('personal', { day: '2026-10-03' }, '/personal?day=2026-10-03'))
    const ready = ref(false)
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0)
      return 1
    })
    const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    const wrapper = mount(ScrollHarness, {
      props: { current, ready, anchor: 'personal-daily' },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    const geometry = vi.spyOn(wrapper.element, 'getBoundingClientRect')
    geometry.mockReturnValue({ top: 137 } as DOMRect)
    capturePersonalReturn(link)
    geometry.mockReturnValue({ top: 500 } as DOMRect)
    expect(
      schedulePersonalScroll(
        current,
        route('personal-history', link.query as LocationQuery),
        '2026-10-05',
        true,
      ),
    ).toBe(true)
    await flushPromises()
    expect(scroll).not.toHaveBeenCalled()
    ready.value = true
    await flushPromises()
    expect(scroll).toHaveBeenLastCalledWith({ top: 363, behavior: 'instant' })
    expect(schedulePersonalScroll(current, current)).toBe(false)
  })

  it('falls back to the section below sticky content when this tab has no captured position', async () => {
    const current = reactive(
      route('personal', { day: '2026-10-03', returnDay: '2026-10-03', returnSection: 'body' }),
    )
    const ready = ref(true)
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0)
      return 1
    })
    document.documentElement.style.scrollPaddingTop = '72px'
    const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    const wrapper = mount(ScrollHarness, {
      props: { current, ready, anchor: 'personal-body' },
      attachTo: document.body,
    })
    wrappers.push(wrapper)
    vi.spyOn(wrapper.element, 'getBoundingClientRect').mockReturnValue({ top: 400 } as DOMRect)
    expect(schedulePersonalScroll(current, route('personal-history'))).toBe(true)
    await flushPromises()
    expect(scroll).toHaveBeenLastCalledWith({ top: 328, behavior: 'instant' })
  })
})
