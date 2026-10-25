import { describe, expect, it } from 'vitest'
import { keyBytes, supported } from './push'

describe('web push', () => {
  it('decodes the VAPID key from base64url', () => {
    expect(Array.from(keyBytes('AQID_w'))).toEqual([1, 2, 3, 255])
  })

  it('is offered only where the browser has the Push API', () => {
    expect(supported({})).toBe(false)
    expect(supported({ navigator: {}, PushManager: {}, Notification: {} })).toBe(false)
    expect(supported({ navigator: { serviceWorker: {} }, PushManager: {}, Notification: {} })).toBe(
      true,
    )
  })
})
