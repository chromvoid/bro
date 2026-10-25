import { mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { h, nextTick } from 'vue'
import ScrollStrip from './ScrollStrip.vue'

const mounted: VueWrapper[] = []
afterEach(() => mounted.splice(0).forEach((wrapper) => wrapper.unmount()))

function strip(props: Record<string, unknown> = {}) {
  const wrapper = mount(ScrollStrip, {
    props,
    attrs: { 'aria-label': 'Списки' },
    slots: { default: () => ['Все', 'Личные', 'Дача'].map((name) => h('a', name)) },
  })
  mounted.push(wrapper)
  return wrapper
}

/** happy-dom does no layout: the row's sizes are set by hand. */
function size(element: HTMLElement, scrollWidth: number, clientWidth: number, scrollLeft = 0) {
  Object.defineProperty(element, 'scrollWidth', { configurable: true, value: scrollWidth })
  Object.defineProperty(element, 'clientWidth', { configurable: true, value: clientWidth })
  element.scrollLeft = scrollLeft
}

describe('scroll strip', () => {
  it('marks and fades only the side where items hide', async () => {
    const wrapper = strip()
    const row = wrapper.element as HTMLElement
    expect(row.getAttribute('aria-label')).toBe('Списки')
    size(row, 600, 300)
    ;(wrapper.vm as unknown as { measure(): void }).measure()
    await nextTick()
    expect(row.dataset.overflowStart).toBeUndefined()
    expect(row.dataset.overflowEnd).toBe('true')
    expect(row.className).toContain('mask-r-from')
    expect(row.className).not.toContain('mask-l-from')
    size(row, 600, 300, 300)
    row.dispatchEvent(new Event('scroll'))
    await nextTick()
    expect(row.dataset.overflowStart).toBe('true')
    expect(row.dataset.overflowEnd).toBeUndefined()
  })

  it('has no fade when everything fits', async () => {
    const wrapper = strip()
    size(wrapper.element as HTMLElement, 300, 300)
    ;(wrapper.vm as unknown as { measure(): void }).measure()
    await nextTick()
    expect((wrapper.element as HTMLElement).className).not.toContain('mask-')
  })

  it('reaches the screen edges when it bleeds and renders as a nav for links', () => {
    const wrapper = strip({ as: 'nav', bleed: true })
    expect(wrapper.element.tagName).toBe('NAV')
    expect(wrapper.classes()).toEqual(expect.arrayContaining(['-mx-4', 'px-4']))
  })

  it('brings an item into the middle of the row without scrolling the page', () => {
    const wrapper = strip()
    const row = wrapper.element as HTMLElement
    const item = row.children[2] as HTMLElement
    row.getBoundingClientRect = () => ({ left: 0, width: 300 }) as DOMRect
    item.getBoundingClientRect = () => ({ left: 500, width: 100 }) as DOMRect
    const scrollTo = vi.fn()
    row.scrollTo = scrollTo as unknown as typeof row.scrollTo
    ;(wrapper.vm as unknown as { reveal(item: Element): void }).reveal(item)
    expect(scrollTo).toHaveBeenCalledWith({ left: 400, behavior: 'auto' })
  })
})
