import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, type EffectScope } from 'vue'

/** A visual viewport the test resizes, as a keyboard or a rotation would. */
class Viewport extends EventTarget {
  constructor(
    public width: number,
    public height: number,
  ) {
    super()
  }
  resize(width: number, height: number) {
    this.width = width
    this.height = height
    this.dispatchEvent(new Event('resize'))
  }
}

let viewport: Viewport
let scope: EffectScope
let composing: typeof import('./composing')

beforeEach(async () => {
  vi.useFakeTimers()
  vi.resetModules()
  viewport = new Viewport(390, 844)
  Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport })
  composing = await import('./composing')
  scope = effectScope()
  scope.run(() => composing.watchKeyboard())
})

afterEach(() => {
  scope.stop()
  vi.useRealTimers()
})

describe('the tab bar while composing', () => {
  it('steps aside only while the keyboard is up over the focused composer', () => {
    composing.setComposerFocus(true)
    expect(composing.tabBarHidden.value).toBe(false)
    viewport.resize(390, 508)
    expect(composing.tabBarHidden.value).toBe(true)
    // Android's back button closes the keyboard and leaves the field focused.
    viewport.resize(390, 844)
    expect(composing.tabBarHidden.value).toBe(false)
  })

  it('stays for the browser toolbar collapsing, which is no keyboard', () => {
    composing.setComposerFocus(true)
    viewport.resize(390, 780)
    expect(composing.tabBarHidden.value).toBe(false)
  })

  it('waits for a tap on the composer to land before coming back', () => {
    composing.setComposerFocus(true)
    viewport.resize(390, 508)
    composing.setComposerFocus(false)
    vi.advanceTimersByTime(200)
    expect(composing.tabBarHidden.value).toBe(true)
    // Focus came back within the same tap: nothing moves.
    composing.setComposerFocus(true)
    vi.advanceTimersByTime(400)
    expect(composing.tabBarHidden.value).toBe(true)
    composing.setComposerFocus(false)
    vi.advanceTimersByTime(300)
    expect(composing.tabBarHidden.value).toBe(false)
  })

  it('measures a rotated screen afresh', () => {
    composing.setComposerFocus(true)
    viewport.resize(844, 390)
    expect(composing.tabBarHidden.value).toBe(false)
    viewport.resize(844, 200)
    expect(composing.tabBarHidden.value).toBe(true)
  })
})
