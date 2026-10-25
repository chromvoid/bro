import { describe, expect, it } from 'vitest'
import { describeError, errorText, retryAfterSeconds } from './errors'
import { ApiError, NetworkError } from './http'

const api = (status: number) => new ApiError(status, null, null, null, null)

describe('describeError', () => {
  it('names what the user can do about each failure', () => {
    expect(describeError(new NetworkError(false))).toBe('errors.network')
    expect(describeError(new NetworkError(true))).toBe('errors.timeout')
    expect(describeError(api(401))).toBe('errors.session')
    expect(describeError(api(409))).toBe('errors.conflict')
    expect(describeError(api(429))).toBe('errors.limit')
    expect(describeError(api(503))).toBe('errors.server')
    expect(describeError(new Error('x'))).toBe('errors.unknown')
  })
})

describe('errorText', () => {
  const t = (key: string) => `t:${key}`
  it('prefers the explanation the server wrote for client errors', () => {
    const taken = new ApiError(409, 'conflict', null, null, null, 'Логин уже занят')
    expect(errorText(taken, t)).toBe('Логин уже занят')
    const crash = new ApiError(500, null, null, null, null, 'Traceback')
    expect(errorText(crash, t)).toBe('t:errors.server')
    expect(errorText(new NetworkError(false), t)).toBe('t:errors.network')
  })

  it('reads the wait time of a rate limit', () => {
    expect(retryAfterSeconds(new ApiError(429, null, null, '60', null))).toBe(60)
    expect(retryAfterSeconds(new ApiError(429, null, null, null, null))).toBeNull()
    expect(retryAfterSeconds(api(503))).toBeNull()
  })
})
