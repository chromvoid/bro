import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import marks from '@/assets/bro/moods.json'
import BrandMark from './BrandMark.vue'
import { assistantMood, MERGE_MS } from './brandMood'

function reducedMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduce && query.includes('reduce') }))
}

async function become(mood: typeof assistantMood.value) {
  assistantMood.value = mood
  await nextTick()
}

const visibleMouth = (wrapper: ReturnType<typeof mount>) =>
  wrapper.findAll('.mouth.shown').map((element) => element.attributes('d') ?? 'dots')

beforeEach(() => {
  vi.useFakeTimers()
  reducedMotion(false)
})
afterEach(() => {
  assistantMood.value = 'ok'
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('BrandMark', () => {
  it('shows one mouth at a time and follows the assistant state', async () => {
    const wrapper = mount(BrandMark)
    const svg = wrapper.get('[data-slot="brand-mark"]')
    expect(svg.attributes('aria-hidden')).toBe('true')
    expect(svg.attributes('data-mood')).toBe('ok')
    for (const mood of ['done', 'oops', 'thinking'] as const) {
      await become(mood)
      expect(svg.attributes('data-mood')).toBe(mood)
      expect(wrapper.findAll('.mouth.shown')).toHaveLength(1)
    }
    expect(wrapper.findAll('.dots.shown .dot')).toHaveLength(3)
  })

  it('flows the dots into ")" when an answer is ready, then shows the outcome', async () => {
    const wrapper = mount(BrandMark)
    const pieces = () => wrapper.findAll('.merge path').map((piece) => piece.attributes('d'))
    await become('thinking')
    await become('done')
    expect(wrapper.find('.merge.on').exists()).toBe(true)
    expect(wrapper.findAll('.mouth.shown')).toHaveLength(0)
    // A script moves the pieces (brandMerge.ts), not SMIL: from the dots to the thirds of ")".
    expect(wrapper.find('animate').exists()).toBe(false)
    expect(pieces()).toEqual(marks.merge.map((piece) => piece.from))
    vi.advanceTimersByTime(MERGE_MS)
    await nextTick()
    expect(pieces()).toEqual(marks.merge.map((piece) => piece.to))
    expect(wrapper.find('.merge.on').exists()).toBe(false)
    expect(visibleMouth(wrapper)).toEqual([marks.ok])
    vi.runAllTimers()
    await nextTick()
    expect(visibleMouth(wrapper)).toEqual([marks.done])

    await become('thinking')
    await become('ok')
    expect(wrapper.find('.merge.on').exists()).toBe(true)
    expect(pieces()).toEqual(marks.merge.map((piece) => piece.from))
    vi.runAllTimers()
    await nextTick()
    expect(visibleMouth(wrapper)).toEqual([marks.ok])
  })

  it('does not turn a failure into a smile, and skips the flow with reduced motion', async () => {
    const wrapper = mount(BrandMark)
    await become('thinking')
    await become('oops')
    expect(wrapper.find('.merge.on').exists()).toBe(false)
    expect(visibleMouth(wrapper)).toEqual([marks.oops])

    reducedMotion(true)
    await become('thinking')
    await become('ok')
    expect(wrapper.find('.merge.on').exists()).toBe(false)
    expect(visibleMouth(wrapper)).toEqual([marks.ok])
  })

  it('keeps a fixed mood outside the chat whatever the assistant is doing', async () => {
    const wrapper = mount(BrandMark, { props: { mood: 'ok' } })
    expect(visibleMouth(wrapper)).toEqual([marks.ok])
    await become('thinking')
    await become('oops')
    expect(wrapper.get('svg').attributes('data-mood')).toBe('ok')
    expect(visibleMouth(wrapper)).toEqual([marks.ok])
  })

  it('pairs every dot with a third of the smile drawn from as many points', () => {
    expect(marks.merge).toHaveLength(marks.thinking.length)
    for (const piece of marks.merge) {
      const count = (path: string) => path.match(/[ML]/g)?.length
      expect(count(piece.from)).toBe(count(piece.to))
      expect(piece.from).toMatch(/Z$/)
      expect(piece.to).toMatch(/Z$/)
    }
  })
})
