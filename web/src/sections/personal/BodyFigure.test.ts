import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import BodyFigure from './BodyFigure.vue'
import type { BodyRenderer, BodyRendererState } from './bodyGpu'
import { neutralBodyVisual } from './bodyVisual'

const { create } = vi.hoisted(() => ({ create: vi.fn() }))
vi.mock('./bodyGpu', () => ({ createBodyRenderer: create }))
let intersection: IntersectionObserverCallback
let resizeDisconnected: ReturnType<typeof vi.fn>
let intersectionDisconnected: ReturnType<typeof vi.fn>
let media: EventTarget & { matches: boolean }
let renderer: BodyRenderer
const visual = {
  ...neutralBodyVisual(),
  fat: 0.7,
  muscle: 0.3,
}
beforeEach(() => {
  renderer = {
    mode: 'webgpu',
    setLayer: vi.fn(),
    setVisual: vi.fn(),
    setYaw: vi.fn(),
    setView: vi.fn(),
    getViewState: vi.fn(() => ({ yaw: 0.13, polar: 1.4, radius: 5 })),
    setActive: vi.fn(),
    setMotion: vi.fn(),
    setPaused: vi.fn(),
    setScenario: vi.fn(),
    setComparison: vi.fn(),
    resize: vi.fn(),
    destroy: vi.fn(),
  }
  create.mockReset().mockResolvedValue(renderer)
  media = Object.assign(new EventTarget(), { matches: false })
  vi.spyOn(window, 'matchMedia').mockReturnValue(media as unknown as MediaQueryList)
  resizeDisconnected = vi.fn()
  intersectionDisconnected = vi.fn()
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      disconnect = resizeDisconnected
    },
  )
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      constructor(callback: IntersectionObserverCallback) {
        intersection = callback
      }
      observe = vi.fn()
      disconnect = intersectionDisconnected
    },
  )
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
function intersect(value: boolean) {
  intersection([{ isIntersecting: value } as IntersectionObserverEntry], {} as IntersectionObserver)
}

describe('body figure enhancement lifecycle', () => {
  it('loads neither renderer nor model until visible and retains a local poster without inline styles', async () => {
    create.mockResolvedValue(null)
    const wrapper = mount(BodyFigure, { props: { visual } })
    await flushPromises()
    expect(create).not.toHaveBeenCalled()
    expect(wrapper.emitted('loading')?.at(-1)).toEqual([true])
    expect(wrapper.find('img').attributes('src')).toContain('/male.webp')
    intersect(true)
    await flushPromises()
    expect(create).toHaveBeenCalledOnce()
    expect(wrapper.emitted('loading')?.at(-1)).toEqual([false])
    expect(wrapper.classes()).not.toContain('body-figure--gpu')
    expect(wrapper.findAll('[style]')).toHaveLength(0)
    expect(wrapper.attributes('aria-hidden')).toBe('true')
    wrapper.unmount()
  })
  it('propagates values and pauses offscreen, reduced motion, and explicitly disabled animation', async () => {
    media.matches = true
    const wrapper = mount(BodyFigure, { props: { visual } })
    intersect(true)
    await flushPromises()
    expect(create.mock.calls[0]![1].motion).toBe(false)
    expect(create.mock.calls[0]![1].visual).toEqual(visual)
    expect(wrapper.emitted('reduced-motion')?.at(-1)).toEqual([true])
    media.matches = false
    media.dispatchEvent(new Event('change'))
    expect(renderer.setMotion).toHaveBeenLastCalledWith(true)
    expect(wrapper.emitted('reduced-motion')?.at(-1)).toEqual([false])
    intersect(false)
    expect(renderer.setActive).toHaveBeenLastCalledWith(false)
    intersect(true)
    await wrapper.setProps({ paused: true, layer: 'water', visual: { ...visual, fat: 0.8 } })
    expect(renderer.setLayer).toHaveBeenLastCalledWith('water')
    expect(renderer.setPaused).toHaveBeenLastCalledWith(true)
    expect(renderer.setMotion).toHaveBeenLastCalledWith(true)
    expect(renderer.setVisual).toHaveBeenLastCalledWith({ ...visual, fat: 0.8 })
    await wrapper.setProps({ paused: false, animate: false })
    expect(renderer.setMotion).toHaveBeenLastCalledWith(false)
    wrapper.unmount()
    expect(resizeDisconnected).toHaveBeenCalledOnce()
    expect(intersectionDisconnected).toHaveBeenCalledOnce()
    expect(renderer.destroy).toHaveBeenCalledOnce()
  })
  it('keeps scenario, pause and comparison independent from the reduced-motion preference', async () => {
    const wrapper = mount(BodyFigure, { props: { visual, scenario: 'athletic', paused: true } })
    intersect(true)
    await flushPromises()
    expect(create.mock.calls[0]![1]).toMatchObject({
      scenario: 'athletic',
      paused: true,
      comparison: false,
      motion: true,
    })
    await wrapper.setProps({ comparing: true })
    expect(renderer.setComparison).toHaveBeenLastCalledWith(true)
    expect(renderer.setMotion).toHaveBeenLastCalledWith(true)
    await wrapper.setProps({ comparing: false, scenario: 'stage' })
    expect(renderer.setComparison).toHaveBeenLastCalledWith(false)
    expect(renderer.setScenario).toHaveBeenLastCalledWith('stage')
    expect(renderer.setPaused).toHaveBeenLastCalledWith(true)
    wrapper.unmount()
  })
  it('forwards scene changes without per-frame progress events', async () => {
    const wrapper = mount(BodyFigure, { props: { visual } })
    intersect(true)
    await flushPromises()
    const state: BodyRendererState = {
      scenario: 'stage',
      activeScenario: 'stage',
      queuedScenario: null,
      phase: 'motion',
      progress: 0.1,
      playing: true,
      paused: false,
      motionSource: 'authored',
    }
    const onState = create.mock.calls[0]![1].onState
    onState(state)
    onState({ ...state, progress: 0.2 })
    expect(wrapper.emitted('motion-state')).toHaveLength(1)
    onState({ ...state, queuedScenario: 'calm' })
    expect(wrapper.emitted('motion-state')).toHaveLength(2)
    wrapper.unmount()
  })
  it('reports WebGL and returns to the same model poster after an error', async () => {
    renderer = { ...renderer, mode: 'webgl' }
    create.mockResolvedValue(renderer)
    const wrapper = mount(BodyFigure, { props: { visual: { ...visual, template: 'female' } } })
    intersect(true)
    await flushPromises()
    expect(wrapper.emitted('renderer')?.at(-1)).toEqual(['webgl'])
    create.mock.calls[0]![1].onFailure()
    await wrapper.vm.$nextTick()
    expect(wrapper.classes()).not.toContain('body-figure--gpu')
    expect(wrapper.find('img').attributes('src')).toContain('/female.webp')
    expect(wrapper.emitted('renderer')?.at(-1)).toEqual(['poster'])
    wrapper.unmount()
  })
  it('replaces the canvas and aborts the former template when the selected template changes', async () => {
    const wrapper = mount(BodyFigure, { props: { visual } })
    intersect(true)
    await flushPromises()
    const oldCanvas = wrapper.find('canvas').element
    const signal = create.mock.calls[0]![1].signal as AbortSignal
    await wrapper.setProps({ visual: { ...visual, template: 'female' } })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    expect(renderer.destroy).toHaveBeenCalledOnce()
    expect(wrapper.find('canvas').element).not.toBe(oldCanvas)
    expect(create.mock.calls.at(-1)![1].visual.template).toBe('female')
    wrapper.unmount()
  })
  it('starts dragging from the actual camera during a view transition and restores it across character replacement', async () => {
    const snapshot = { yaw: 1.07, polar: 1.5, radius: 4.2 }
    vi.mocked(renderer.getViewState).mockReturnValue(snapshot)
    const wrapper = mount(BodyFigure, {
      props: { visual, scenario: 'stage', paused: true, comparing: true },
    })
    intersect(true)
    await flushPromises()
    ;(wrapper.vm as unknown as { setView: (view: 'side') => void }).setView('side')
    expect(renderer.setView).toHaveBeenCalledWith('side')
    const canvas = wrapper.get('canvas')
    await canvas.trigger('pointerdown', {
      button: 0,
      isPrimary: true,
      pointerId: 1,
      clientX: 20,
      clientY: 20,
    })
    // A held finger has not cancelled the preset yet: start at its progressed angle.
    vi.mocked(renderer.getViewState).mockReturnValue({ ...snapshot, yaw: 1.5 })
    await canvas.trigger('pointermove', { pointerId: 1, clientX: 40, clientY: 20 })
    expect(renderer.setYaw).toHaveBeenLastCalledWith(1.62)
    const restored = { ...snapshot, yaw: 1.62 }
    vi.mocked(renderer.getViewState).mockReturnValue(restored)
    await wrapper.setProps({ characterId: 'spartan' })
    await flushPromises()
    expect(create.mock.calls.at(-1)![1]).toMatchObject({
      characterId: 'spartan',
      initialView: restored,
      scenario: 'stage',
      paused: true,
      comparison: true,
    })
    expect(wrapper.get('img').attributes('src')).toContain('male_spartan.webp')
    wrapper.unmount()
  })
  it('resolves a saved Spartan to the female sculpture without requesting a male model', async () => {
    const wrapper = mount(BodyFigure, {
      props: { visual: { ...visual, template: 'female' }, characterId: 'spartan' },
    })
    intersect(true)
    await flushPromises()
    expect(wrapper.get('img').attributes('src')).toContain('female.webp')
    expect(wrapper.attributes('data-character-id')).toBe('sculpture')
    expect(create.mock.calls[0]![1].characterId).toBe('spartan')
    wrapper.unmount()
  })
  it('keeps the new template loading while an aborted old template finishes', async () => {
    const pending: ((value: BodyRenderer) => void)[] = []
    create.mockImplementation(() => new Promise<BodyRenderer>((resolve) => pending.push(resolve)))
    const wrapper = mount(BodyFigure, { props: { visual } })
    intersect(true)
    await flushPromises()
    const first = create.mock.calls[0]![1]
    await wrapper.setProps({ visual: { ...visual, template: 'female' } })
    await flushPromises()
    expect(create).toHaveBeenCalledTimes(2)
    expect(first.signal.aborted).toBe(true)
    expect(wrapper.emitted('loading')?.at(-1)).toEqual([true])
    first.onFailure()
    pending[0]!(renderer)
    await flushPromises()
    expect(renderer.destroy).toHaveBeenCalledOnce()
    expect(wrapper.emitted('loading')?.at(-1)).toEqual([true])
    expect(wrapper.classes()).not.toContain('body-figure--gpu')
    pending[1]!({ ...renderer, destroy: vi.fn() })
    await flushPromises()
    expect(wrapper.emitted('loading')?.at(-1)).toEqual([false])
    expect(wrapper.classes()).toContain('body-figure--gpu')
    wrapper.unmount()
  })
  it('aborts initialization and destroys a late renderer after unmounting', async () => {
    let finish!: (value: BodyRenderer) => void
    create.mockImplementation(
      () =>
        new Promise<BodyRenderer>((resolve) => {
          finish = resolve
        }),
    )
    const wrapper = mount(BodyFigure)
    intersect(true)
    await flushPromises()
    const signal = create.mock.calls[0]![1].signal as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
    finish(renderer)
    await flushPromises()
    expect(renderer.destroy).toHaveBeenCalledOnce()
    expect(renderer.setActive).not.toHaveBeenCalled()
  })
  it('does not intercept vertical gestures and permits a full horizontal orbit', async () => {
    const wrapper = mount(BodyFigure)
    intersect(true)
    await flushPromises()
    const canvas = wrapper.find('canvas')
    await canvas.trigger('pointerdown', {
      button: 0,
      isPrimary: true,
      pointerId: 1,
      clientX: 0,
      clientY: 0,
    })
    await canvas.trigger('pointermove', { pointerId: 1, clientX: 2, clientY: 50 })
    expect(renderer.setYaw).not.toHaveBeenCalled()
    await canvas.trigger('pointerdown', {
      button: 0,
      isPrimary: true,
      pointerId: 2,
      clientX: 0,
      clientY: 0,
    })
    await canvas.trigger('pointermove', { pointerId: 2, clientX: 500, clientY: 2 })
    expect(renderer.setYaw).toHaveBeenLastCalledWith(3.13)
    wrapper.unmount()
  })
})
