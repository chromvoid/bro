import { describe, expect, it } from 'vitest'
import { Login, Quota, groupScopes, loginAction, quotaView, resetCredits, tokenLink } from './api'

const NOW = Date.parse('2026-09-25T12:00:00Z')
const NOW_ISO = '2026-09-25T12:00:00Z'

describe('users', () => {
  it('groups scopes in product words and keeps unknown ones', () => {
    expect(
      groupScopes([
        'tasks.read',
        'session.control',
        'nutrition.read',
        'timers.read',
        'calls.write',
        'challenges.read',
        'calls.read',
        'weather.read',
        'future.thing',
      ]),
    ).toEqual([
      { key: 'assistant', scopes: ['session.control'] },
      { key: 'nutrition', scopes: ['nutrition.read'] },
      { key: 'tasks', scopes: ['tasks.read', 'timers.read'] },
      { key: 'circle', scopes: ['calls.write', 'challenges.read', 'calls.read'] },
      { key: 'weather', scopes: ['weather.read'] },
      { key: 'other', scopes: ['future.thing'] },
    ])
  })

  it.each(['invite', 'reset'] as const)(
    'builds a public join link for %s on any server',
    (kind) => {
      expect(tokenLink('https://a.example:8443', kind, 'abc+/&')).toBe(
        `https://bro.chromvoid.com/join#server=https%3A%2F%2Fa.example%3A8443&kind=${kind}&token=abc%2B%2F%26`,
      )
      expect(tokenLink('https://bro.chromvoid.com', kind, 'synthetic-token')).toBe(
        `https://bro.chromvoid.com/join#server=https%3A%2F%2Fbro.chromvoid.com&kind=${kind}&token=synthetic-token`,
      )
    },
  )
})

describe('subscriptions', () => {
  const quota = (changes: Record<string, unknown>) =>
    Quota.parse({
      account_id: 'a',
      scope: '*',
      bucket: '18000',
      exhausted: false,
      remaining_percent: 68,
      resets_at: '2026-09-25T14:00:00Z',
      observed_at: '2026-09-25T11:58:00Z',
      ...changes,
    })

  it('shows a remaining share only for a fresh, unexpired observation', () => {
    expect(quotaView(quota({}), NOW)).toMatchObject({
      period: { kind: 'hours', count: 5 },
      remaining: 68,
      stale: false,
    })
    expect(quotaView(quota({ observed_at: '2026-09-25T11:00:00Z' }), NOW)).toMatchObject({
      remaining: null,
      stale: true,
    })
    // A passed reset does not mean the quota was replenished.
    expect(
      quotaView(quota({ resets_at: '2026-09-25T11:59:00Z', exhausted: true }), NOW),
    ).toMatchObject({
      remaining: null,
      expired: true,
      exhausted: false,
    })
    expect(quotaView(quota({ bucket: '604800' }), NOW).period).toEqual({ kind: 'week' })
    expect(quotaView(quota({ bucket: 'request' }), NOW).period).toEqual({ kind: 'request' })
    expect(quotaView(quota({ remaining_percent: null }), NOW).remaining).toBeNull()
    // Reset exactly now: the window is over, the 0 % belongs to the previous one.
    expect(
      quotaView(quota({ resets_at: NOW_ISO, exhausted: true, remaining_percent: 0 }), NOW),
    ).toMatchObject({ remaining: null, exhausted: false })
  })

  it('keeps an unexpired refusal visible when its observation is old', () => {
    expect(
      quotaView(
        quota({
          bucket: 'request',
          exhausted: true,
          remaining_percent: null,
          observed_at: '2026-09-25T10:00:00Z',
        }),
        NOW,
      ),
    ).toMatchObject({ exhausted: true, stale: true, remaining: null })
  })

  it('shows reset credits only when recently observed', () => {
    const account = {
      id: 'a',
      provider: 'codex',
      label: 'Основная',
      enabled: true,
      revision: 1,
      reset_credits: 2,
      reset_credits_observed_at: '2026-09-25T11:55:00Z',
    }
    expect(resetCredits(account, NOW)).toEqual({ value: '2', stale: false })
    expect(resetCredits({ ...account, reset_credits: 0 }, NOW).value).toBe('0')
    expect(resetCredits({ ...account, reset_credits: null }, NOW).value).toBe('—')
    expect(
      resetCredits({ id: 'a', provider: 'codex', label: 'x', enabled: true, revision: 1 }, NOW),
    ).toEqual({
      value: '—',
      stale: false,
    })
    expect(
      resetCredits({ ...account, reset_credits_observed_at: '2026-09-25T10:00:00Z' }, NOW),
    ).toEqual({
      value: '—',
      stale: true,
    })
  })

  it('links only to the providers’ own sign-in pages', () => {
    const login = (provider: string, action: Record<string, unknown>) =>
      Login.parse({ id: 'l', provider, label: 'x', state: 'waiting_user', action })
    expect(
      loginAction(
        login('codex', { url: 'https://auth.openai.com/codex/device', code: 'ABCD-1234' }),
      ),
    ).toEqual({
      url: 'https://auth.openai.com/codex/device',
      code: 'ABCD-1234',
      pasteCode: false,
    })
    expect(
      loginAction(
        login('claude', {
          url: 'https://claude.ai/oauth/authorize?x=1',
          input: 'authorization_code',
        }),
      ),
    ).toMatchObject({ pasteCode: true, code: null })
    for (const url of [
      'http://auth.openai.com/device',
      'https://auth.openai.com.evil.example/device',
      'https://user:pass@auth.openai.com/device',
      'https://auth.openai.com:8443/device',
      'javascript:alert(1)',
    ])
      expect(loginAction(login('codex', { url, code: 'X' })), url).toBeNull()
    expect(
      loginAction(
        login('claude', {
          url: 'https://claude.com/cai/oauth/authorize?state=s',
          input: 'authorization_code',
        }),
      ),
    ).toMatchObject({ pasteCode: true })
    for (const url of [
      'https://claude.ai/elsewhere',
      'https://auth.openai.com/codex/device',
      'https://claude.com.evil.example/oauth/authorize',
      'http://claude.ai/oauth/authorize',
      'https://user@claude.ai/oauth/authorize',
    ])
      expect(loginAction(login('claude', { url, input: 'authorization_code' })), url).toBeNull()
    // A provider never follows the other one's sign-in page.
    expect(
      loginAction(login('codex', { url: 'https://claude.ai/oauth/authorize', code: 'X' })),
    ).toBeNull()
    for (const state of ['ready', 'cancelled', 'expired', 'failed', 'verifying'])
      expect(
        loginAction(
          Login.parse({
            id: 'l',
            provider: 'codex',
            label: 'x',
            state,
            action: { url: 'https://auth.openai.com/codex/device', code: 'X' },
          }),
        ),
        state,
      ).toBeNull()
    expect(
      loginAction(Login.parse({ id: 'l', label: 'x', state: 'verifying', action: null })),
    ).toBeNull()
  })
})
