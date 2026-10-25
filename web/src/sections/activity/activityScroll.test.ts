import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, ref, type PropType, type Ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LocationQuery, LocationQueryRaw, RouteLocationNormalized } from 'vue-router'
import {
  captureActivityAnchor,
  captureActivityPosition,
  scheduleActivityScroll,
  useActivityScrollRestore,
} from './activityScroll'

const route = (name: string, query: LocationQuery = {}, hash = '') =>
  ({ name, query, hash }) as RouteLocationNormalized
const Harness = defineComponent({
  props: {
    query: { type: Object as PropType<Ref<LocationQueryRaw>>, required: true },
    ready: { type: Object as PropType<Ref<boolean>>, required: true },
    anchor: { type: String, required: true },
  },
  setup(props) {
    useActivityScrollRestore(props.query, props.ready)
  },
  template: '<section :id="anchor" />',
})
const wrappers: VueWrapper[] = []
let frames: FrameRequestCallback[]
beforeEach(() => {
  frames = []
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.push(callback)
    return frames.length
  })
  vi.stubGlobal('scrollY', 200)
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  scheduleActivityScroll(route('today'), route('activity'))
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
const renderFrames = async () => {
  await flushPromises()
  while (frames.length) {
    frames.splice(0).forEach((frame) => frame(0))
    await flushPromises()
  }
}
function screen(
  query: Ref<LocationQueryRaw>,
  ready = ref(true),
  anchor = 'activity-entry-manual:test',
) {
  const wrapper = mount(Harness, { props: { query, ready, anchor }, attachTo: document.body })
  wrappers.push(wrapper)
  const geometry = vi.spyOn(wrapper.element, 'getBoundingClientRect')
  geometry.mockReturnValue({ top: 135 } as DOMRect)
  return { wrapper, geometry }
}

describe('Activity detail return position', () => {
  it('restores the exact entry offset when cached queries were ready before router scheduling', async () => {
    const query = ref({ tab: 'journal', day: '2026-10-06', period: '7', kind: 'running' })
    const { geometry } = screen(query)
    await flushPromises()
    captureActivityPosition(query.value, 'manual:test')
    geometry.mockReturnValue({ top: 500 } as DOMRect)
    const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    expect(
      scheduleActivityScroll(
        route('activity', { kind: 'running', period: '7', day: '2026-10-06', tab: 'journal' }),
        route('activity-session'),
      ),
    ).toBe(true)
    await renderFrames()
    expect(scroll).toHaveBeenCalledOnce()
    expect(scroll).toHaveBeenCalledWith({ top: 565, behavior: 'instant' })
  })

  it('waits until list data settled and supports a strength-table anchor', async () => {
    const query = ref({ tab: 'progress', metric: 'strength', exercise: 'bench', day: '2026-10-05' })
    const ready = ref(false)
    const { geometry } = screen(query, ready, 'strength-set-last')
    captureActivityAnchor(query.value, 'strength-set-last')
    geometry.mockReturnValue({ top: 600 } as DOMRect)
    const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    expect(scheduleActivityScroll(route('activity', query.value), route('activity-session'))).toBe(
      true,
    )
    await renderFrames()
    expect(scroll).not.toHaveBeenCalled()
    ready.value = true
    await renderFrames()
    expect(scroll).toHaveBeenLastCalledWith({ top: 665, behavior: 'instant' })
  })

  it('leaves unrelated navigation, different periods, missing anchors and hashes to the router', () => {
    const query = ref({ tab: 'journal', day: '2026-10-04', period: '30' })
    screen(query)
    captureActivityPosition(query.value, 'manual:test')
    expect(scheduleActivityScroll(route('today'), route('activity-session'))).toBe(false)
    expect(scheduleActivityScroll(route('activity', query.value), route('settings'))).toBe(false)
    expect(
      scheduleActivityScroll(
        route('activity', { ...query.value, period: '7' }),
        route('activity-session'),
      ),
    ).toBe(false)
    expect(
      scheduleActivityScroll(route('activity', query.value, '#custom'), route('activity-session')),
    ).toBe(false)
    const absent = { tab: 'journal', day: '2026-09-01' }
    captureActivityPosition(absent, 'manual:not-present')
    expect(scheduleActivityScroll(route('activity', absent), route('activity-session'))).toBe(false)
  })

  it('cancels an in-flight restore after the reading context changed', async () => {
    const query = ref<LocationQueryRaw>({ tab: 'journal', day: '2026-10-03' })
    const { geometry } = screen(query)
    captureActivityPosition(query.value, 'manual:test')
    geometry.mockReturnValue({ top: 500 } as DOMRect)
    const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    scheduleActivityScroll(
      route('activity', query.value as LocationQuery),
      route('activity-session'),
    )
    await flushPromises()
    expect(frames.length).toBeGreaterThan(0)
    query.value = { tab: 'overview', day: '2026-10-02' }
    await renderFrames()
    expect(scroll).not.toHaveBeenCalled()
  })

  it('cancels an in-flight restore on unmount and consumes a missing restored row safely', async () => {
    const query = ref({ tab: 'journal', day: '2026-10-01' })
    const { wrapper } = screen(query)
    captureActivityPosition(query.value, 'manual:test')
    const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    scheduleActivityScroll(route('activity', query.value), route('activity-session'))
    await flushPromises()
    wrapper.unmount()
    wrappers.pop()
    await renderFrames()
    expect(scroll).not.toHaveBeenCalled()

    const missing = ref({ tab: 'journal', day: '2026-09-30' })
    const another = screen(missing)
    captureActivityPosition(missing.value, 'manual:test')
    another.wrapper.element.remove()
    scheduleActivityScroll(route('activity', missing.value), route('activity-session'))
    await renderFrames()
    expect(scroll).not.toHaveBeenCalled()
  })
})
