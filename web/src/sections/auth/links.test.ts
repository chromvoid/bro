import { describe, expect, it } from 'vitest'
import { browserAuthLink, LOGIN_PATTERN, readAuthLink, readJoinLink, safeNext } from './links'

describe('readJoinLink', () => {
  const fragment = (
    server = 'https://family.example',
    kind = 'invite',
    token = 'synthetic-token',
  ) => `#server=${encodeURIComponent(server)}&kind=${kind}&token=${encodeURIComponent(token)}`

  it.each(['invite', 'reset'] as const)('reads a %s link for a canonical HTTPS origin', (kind) => {
    for (const server of [
      'https://bro.chromvoid.com',
      'https://family.example:8443',
      'https://[::1]',
    ]) {
      expect(readJoinLink(fragment(server, kind, 'abc+/&=?#'))).toEqual({
        server,
        kind,
        token: 'abc+/&=?#',
      })
    }
    expect(readJoinLink(fragment(undefined, kind, 'a'.repeat(128)))?.token).toHaveLength(128)
  })

  it.each([
    'http://family.example',
    'https://family.example/',
    'https://family.example/path',
    'https://family.example/path/..',
    'https://family.example?query=1',
    'https://family.example?',
    'https://family.example#hash',
    'https://family.example#',
    'https://user:password@family.example',
    'https://@family.example',
    'https://FAMILY.example',
    'https://family.example:443',
    ' https://family.example',
    'https://family.example\n',
    'https:\\family.example',
    'family.example',
    '',
  ])('rejects a noncanonical server: %j', (server) => {
    expect(readJoinLink(fragment(server))).toBeNull()
  })

  it.each([
    '',
    'a'.repeat(129),
    'a b',
    'a\tb',
    'a\nb',
    'a\u0000b',
    'a\u007fb',
    'a\u0085b',
    'a\u00a0b',
  ])('rejects an empty, long, whitespace or control-bearing token: %j', (token) => {
    expect(readJoinLink(fragment(undefined, 'invite', token))).toBeNull()
  })

  it('rejects missing, unknown and repeated parameters', () => {
    for (const value of [
      '',
      '#kind=invite&token=abc',
      '#server=https%3A%2F%2Ffamily.example&token=abc',
      '#server=https%3A%2F%2Ffamily.example&kind=invite',
      fragment(undefined, 'admin'),
      `${fragment()}&server=https%3A%2F%2Fother.example`,
      `${fragment()}&kind=reset`,
      `${fragment()}&token=second`,
      `${fragment()}&token=synthetic-token`,
      `${fragment()}&other=value`,
    ]) {
      expect(readJoinLink(value), value).toBeNull()
    }
  })
})

describe('browserAuthLink', () => {
  it.each(['invite', 'reset'] as const)('builds the exact existing login URL for %s', (kind) => {
    expect(
      browserAuthLink({ server: 'https://family.example:8443', kind, token: 'abc+/&=?#' }),
    ).toBe(`https://family.example:8443/login#kind=${kind}&token=abc%2B%2F%26%3D%3F%23`)
  })
})

describe('readAuthLink', () => {
  it('reads invitation and reset links from the fragment', () => {
    expect(readAuthLink('#kind=invite&token=abc')).toEqual({ kind: 'invite', token: 'abc' })
    expect(readAuthLink('kind=reset&token=xyz')).toEqual({ kind: 'reset', token: 'xyz' })
  })

  it('ignores anything else', () => {
    expect(readAuthLink('')).toBeNull()
    expect(readAuthLink('#kind=admin&token=abc')).toBeNull()
    expect(readAuthLink('#kind=invite')).toBeNull()
    expect(readAuthLink(`#kind=invite&token=${'a'.repeat(129)}`)).toBeNull()
  })
})

describe('safeNext', () => {
  it('keeps paths inside the app', () => {
    expect(safeNext('/food?day=2026-09-25')).toBe('/food?day=2026-09-25')
  })

  it('refuses other origins, the login page itself and malformed values', () => {
    for (const value of [
      '//evil.example/x',
      'https://evil.example',
      '/\\evil',
      '/login?next=/',
      42,
      null,
    ]) {
      expect(safeNext(value)).toBeNull()
    }
  })
})

describe('LOGIN_PATTERN', () => {
  it('matches the server rule', () => {
    expect(LOGIN_PATTERN.test('maria.k')).toBe(true)
    expect(LOGIN_PATTERN.test('ma')).toBe(false)
    expect(LOGIN_PATTERN.test('-maria')).toBe(false)
    expect(LOGIN_PATTERN.test('мария')).toBe(false)
  })
})
