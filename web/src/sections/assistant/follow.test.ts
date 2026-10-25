import { effectScope, nextTick, shallowRef } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useFollowBottom } from './follow'

let resized: () => void
beforeEach(() => {
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
})
afterEach(() => {
  vi.unstubAllGlobals()
})

/** A scroller with a browser's geometry: the position stays within the content. */
function conversation({ paused = false } = {}) {
  const element = document.createElement('div')
  const inner = document.createElement('div')
  element.append(inner)
  const geometry = { height: 1000, client: 500, top: 0, writes: 0 }
  const clamp = () => {
    geometry.top = Math.max(0, Math.min(geometry.top, geometry.height - geometry.client))
  }
  Object.defineProperties(element, {
    scrollHeight: { get: () => geometry.height },
    clientHeight: { get: () => geometry.client },
    scrollTop: {
      get: () => geometry.top,
      set(value: number) {
        geometry.writes++
        geometry.top = value
        clamp()
      },
    },
  })
  const scope = effectScope()
  const bottom = scope.run(() =>
    useFollowBottom(shallowRef(element), shallowRef(inner), () => paused),
  )!
  const gap = () => geometry.height - geometry.top - geometry.client
  return {
    element,
    inner,
    geometry,
    bottom,
    gap,
    scope,
    /** The user moves the conversation; the browser reports it. */
    scroll(top: number) {
      geometry.top = top
      clamp()
      element.dispatchEvent(new Event('scroll'))
    },
    /** The content changes height: a clamp is reported as a scroll, then the size change. */
    grow(height: number) {
      const before = geometry.top
      geometry.height = height
      clamp()
      if (geometry.top !== before) element.dispatchEvent(new Event('scroll'))
      resized()
    },
    atBottom() {
      geometry.top = geometry.height - geometry.client
      element.dispatchEvent(new Event('scroll'))
    },
    /**
     * WebKit's order: the size change is observed in this frame, the clamp it caused is reported
     * as a scroll in the next one. Returns that report.
     */
    resize(height: number) {
      const before = geometry.top
      geometry.height = height
      clamp()
      const moved = geometry.top !== before
      resized()
      return () => {
        if (moved) element.dispatchEvent(new Event('scroll'))
      }
    },
    /** iOS reports a rubber-band bounce past the bottom as it is, without a clamp. */
    bounce(top: number) {
      geometry.top = top
      element.dispatchEvent(new Event('scroll'))
    },
  }
}

interface Finger {
  identifier: number
  clientX: number
  clientY: number
}
const finger = (identifier: number, clientY = 300, clientX = 100): Finger => ({
  identifier,
  clientX,
  clientY,
})
/** A touch event as the browser sends it: the fingers that changed and all still down. */
function touch(target: EventTarget, type: string, changed: Finger[], still: Finger[] = []) {
  const event = new Event(type, { bubbles: true })
  Object.defineProperties(event, {
    changedTouches: { value: changed },
    touches: { value: type === 'touchstart' ? [...still, ...changed] : still },
  })
  target.dispatchEvent(event)
}

function wheel(target: EventTarget, deltaY: number, { deltaX = 0, ctrlKey = false } = {}) {
  const event = new Event('wheel', { bubbles: true })
  Object.defineProperties(event, {
    deltaX: { value: deltaX },
    deltaY: { value: deltaY },
    ctrlKey: { value: ctrlKey },
  })
  target.dispatchEvent(event)
}

describe('following the newest message', () => {
  const scopes: Array<{ stop(): void }> = []
  afterEach(() => scopes.splice(0).forEach((scope) => scope.stop()))
  const open = (options?: { paused?: boolean }) => {
    const chat = conversation(options)
    scopes.push(chat.scope)
    chat.atBottom()
    return chat
  }

  it('keeps a growing answer in view at the bottom', () => {
    const chat = open()
    chat.grow(1200)
    expect(chat.gap()).toBe(0)
    chat.grow(1500)
    expect(chat.gap()).toBe(0)
  })

  it('never pulls back a user who has scrolled up a few pixels', () => {
    const chat = open()
    chat.scroll(495)
    for (const height of [1100, 1200, 1300]) chat.grow(height)
    expect(chat.geometry.top).toBe(495)
    expect(chat.bottom.following.value).toBe(false)
    expect(chat.bottom.atBottom.value).toBe(false)
  })

  it('follows again once the user is back at the bottom', () => {
    const chat = open()
    chat.scroll(300)
    chat.grow(1200)
    expect(chat.geometry.top).toBe(300)
    chat.scroll(700)
    expect(chat.bottom.following.value).toBe(true)
    expect(chat.bottom.atBottom.value).toBe(true)
    chat.grow(1400)
    expect(chat.gap()).toBe(0)
  })

  it('stops at a wheel turned up, before the scroll moves', () => {
    const chat = open()
    wheel(chat.element, -40)
    expect(chat.bottom.following.value).toBe(false)
    expect(chat.bottom.atBottom.value).toBe(true)
    chat.grow(1200)
    expect(chat.geometry.top).toBe(500)
    expect(chat.bottom.atBottom.value).toBe(false)
  })

  it('ignores a wheel that cannot move the conversation, zooms or swipes sideways', () => {
    const chat = open()
    wheel(chat.element, -40, { ctrlKey: true })
    chat.grow(1200)
    expect(chat.gap()).toBe(0)
    wheel(chat.element, -2, { deltaX: -30 })
    chat.grow(1400)
    expect(chat.gap()).toBe(0)
    const short = open()
    short.grow(400)
    expect(short.geometry.top).toBe(0)
    wheel(short.element, -40)
    short.grow(1000)
    expect(short.gap()).toBe(0)
  })

  it('leaves the position to a finger and catches up when it lifts', () => {
    const chat = open()
    touch(chat.inner, 'touchstart', [finger(1)])
    chat.grow(1200)
    expect(chat.geometry.top).toBe(500)
    touch(chat.inner, 'touchend', [finger(1, 304)])
    expect(chat.gap()).toBe(0)
  })

  it('stops at a finger that pulled the list down, before its scroll is reported', () => {
    const chat = open()
    touch(chat.inner, 'touchstart', [finger(1, 300)])
    chat.grow(1200)
    touch(chat.inner, 'touchend', [finger(1, 360)])
    chat.grow(1300)
    expect(chat.geometry.top).toBe(500)
  })

  it('is not held by a finger resting outside the conversation', () => {
    const chat = open()
    const composer = document.createElement('textarea')
    touch(composer, 'touchstart', [finger(2, 700)])
    touch(chat.inner, 'touchstart', [finger(1)], [finger(2, 700)])
    // The conversation's finger lifts first; the other one ends where nothing listens.
    touch(chat.inner, 'touchend', [finger(1)], [finger(2, 700)])
    touch(composer, 'touchend', [finger(2, 700)])
    chat.grow(1200)
    expect(chat.gap()).toBe(0)
  })

  it('hears the end of a touch on a node that has left the conversation', () => {
    const chat = open()
    const answer = document.createElement('p')
    chat.inner.append(answer)
    touch(answer, 'touchstart', [finger(1)])
    answer.remove()
    touch(answer, 'touchend', [finger(1)])
    chat.grow(1200)
    expect(chat.gap()).toBe(0)
  })

  it('does not follow again when content shrinking below the user clamps them down', () => {
    const chat = open()
    chat.scroll(400)
    chat.grow(850)
    expect(chat.gap()).toBe(0)
    chat.grow(1200)
    expect(chat.geometry.top).toBe(350)
  })

  it('keeps following when an answer grows while a bounce settles onto the bottom', () => {
    const chat = open()
    chat.scroll(300)
    // A flick back down overshoots the bottom and bounces back while a line is added.
    chat.bounce(530)
    chat.bounce(510)
    chat.geometry.height = 1040
    chat.bounce(505)
    chat.grow(1040)
    expect(chat.gap()).toBe(0)
  })

  it('keeps following when a clamp is reported after the answer has grown', () => {
    const chat = open()
    // Rows measured shorter clamp the bottom; the first words of the answer arrive before the
    // clamp is reported, and are observed only after it.
    const reported = chat.resize(968)
    chat.geometry.height = 1132
    reported()
    resized()
    expect(chat.bottom.following.value).toBe(true)
    expect(chat.gap()).toBe(0)
  })

  it('stops following a reader who scrolls up in the frame the answer was followed', () => {
    const chat = open()
    chat.resize(1500)
    // One report for the write to the bottom and the reader's move up after it.
    chat.scroll(chat.geometry.top - 120)
    expect(chat.bottom.following.value).toBe(false)
    chat.grow(1484)
    expect(chat.geometry.top).toBe(880)
  })

  it('writes nothing when the view is already at the bottom', () => {
    const chat = open()
    const writes = chat.geometry.writes
    chat.grow(1000)
    expect(chat.geometry.writes).toBe(writes)
  })

  it('shows the newest message on request, even under a finger', async () => {
    const chat = open()
    chat.scroll(100)
    touch(chat.inner, 'touchstart', [finger(1)])
    chat.bottom.follow()
    await nextTick()
    expect(chat.gap()).toBe(0)
    expect(chat.bottom.following.value).toBe(true)
    expect(chat.bottom.atBottom.value).toBe(true)
  })

  it('keeps the position after a release until the user comes back down', () => {
    const chat = open()
    chat.bottom.release()
    chat.grow(1200)
    expect(chat.geometry.top).toBe(500)
  })

  it('never moves a paused conversation', async () => {
    const chat = open({ paused: true })
    chat.grow(1200)
    chat.bottom.follow()
    await nextTick()
    expect(chat.geometry.top).toBe(500)
    expect(chat.bottom.atBottom.value).toBe(false)
  })

  it('reflects a content clamp without pretending the reader resumed following', () => {
    const chat = open()
    chat.scroll(400)
    chat.grow(850)
    expect(chat.bottom.atBottom.value).toBe(true)
    expect(chat.bottom.following.value).toBe(false)
    chat.grow(1200)
    expect(chat.bottom.atBottom.value).toBe(false)
    expect(chat.bottom.following.value).toBe(false)
  })
})

describe('a question just sent', () => {
  it('holds the screen while its answer grows, until the reader scrolls themselves', async () => {
    const chat = conversation()
    chat.atBottom()
    chat.bottom.hold()
    // Placed with the question at the top and exactly at the end of the room below it.
    chat.scroll(500)
    expect(chat.bottom.following.value).toBe(false)
    expect(chat.bottom.atBottom.value).toBe(true)
    const writes = chat.geometry.writes
    chat.grow(1400)
    await nextTick()
    expect(chat.geometry.writes).toBe(writes)
    expect(chat.geometry.top).toBe(500)
    expect(chat.bottom.atBottom.value).toBe(false)
    // The reader's own wheel ends the hold; coming back to the end follows again.
    chat.element.dispatchEvent(new WheelEvent('wheel', { deltaY: 40 }))
    expect(chat.bottom.held.value).toBe(false)
    chat.atBottom()
    expect(chat.bottom.following.value).toBe(true)
    chat.scope.stop()
  })

  it('stays held when a programmatic scroll lands at the end', () => {
    const chat = conversation()
    chat.bottom.hold()
    chat.atBottom()
    expect(chat.bottom.following.value).toBe(false)
    chat.element.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageDown' }))
    chat.atBottom()
    expect(chat.bottom.following.value).toBe(true)
    chat.scope.stop()
  })
})
