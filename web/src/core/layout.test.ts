import { effectScope, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useRoomFor } from './layout'

const mocks = vi.hoisted(() => ({
  resize: null as
    null | ((entries: Array<{ target: Element; contentRect: { width: number } }>) => void),
}))
vi.mock('@vueuse/core', () => ({
  useResizeObserver: (_target: unknown, callback: typeof mocks.resize) => {
    mocks.resize = callback
  },
}))
const frames = new Map<number, FrameRequestCallback>()
const scopes: ReturnType<typeof effectScope>[] = []
let sequence = 0
let fontSize = 16
beforeEach(() => {
  sequence = 0
  fontSize = 16
  frames.clear()
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.set(++sequence, callback)
    return sequence
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.stubGlobal('getComputedStyle', () => ({ fontSize: `${fontSize}px` }))
})
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop())
  vi.unstubAllGlobals()
})
function screen() {
  const area = ref(document.createElement('div'))
  const scope = effectScope()
  scopes.push(scope)
  const roomy = scope.run(() => useRoomFor(area, 68.25))!
  const resize = (width: number) => mocks.resize!([{ target: area.value, contentRect: { width } }])
  return { roomy, resize, scope }
}
function draw() {
  const pending = [...frames.values()]
  frames.clear()
  pending.forEach((callback) => callback(0))
}

describe('room for a panel', () => {
  it('applies the latest available width outside the resize delivery cycle', () => {
    const { roomy, resize } = screen()
    resize(1000)
    resize(1120)
    expect(roomy.value).toBe(false)
    draw()
    expect(roomy.value).toBe(true)
    resize(1000)
    expect(roomy.value).toBe(true)
    draw()
    expect(roomy.value).toBe(false)
  })

  it('requires more space when text grows, even at the same measured width', () => {
    const { roomy, resize } = screen()
    resize(1120)
    draw()
    expect(roomy.value).toBe(true)
    fontSize = 24
    resize(1120)
    draw()
    expect(roomy.value).toBe(false)
  })

  it('cancels a queued layout change when its workspace closes', () => {
    const { roomy, resize, scope } = screen()
    resize(1120)
    scope.stop()
    expect(frames.size).toBe(0)
    draw()
    expect(roomy.value).toBe(false)
  })
})
