import { mount } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { describe, expect, it } from 'vitest'
import { useScrollDirection } from './useScrollDirection'

function scrollTo(y: number) {
  Object.defineProperty(window, 'scrollY', { value: y, configurable: true })
  window.dispatchEvent(new Event('scroll'))
}

describe('useScrollDirection', () => {
  it('follows the page down and up, ignores small moves and knows the end', async () => {
    Object.defineProperty(document.documentElement, 'scrollHeight', {
      value: 2000,
      configurable: true,
    })
    Object.defineProperty(window, 'innerHeight', { value: 800, configurable: true })
    scrollTo(0)
    let state!: ReturnType<typeof useScrollDirection>
    const wrapper = mount(
      defineComponent({
        setup() {
          state = useScrollDirection()
          return () => null
        },
      }),
    )
    scrollTo(4)
    expect(state.direction.value).toBeNull()
    scrollTo(300)
    expect(state.direction.value).toBe('down')
    scrollTo(250)
    expect(state.direction.value).toBe('up')
    expect(state.atEnd.value).toBe(false)
    scrollTo(1190)
    expect(state.direction.value).toBe('down')
    expect(state.atEnd.value).toBe(true)
    await nextTick()
    wrapper.unmount()
  })
})
