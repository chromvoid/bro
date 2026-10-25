import { describe, expect, it } from 'vitest'
import { DdxConnection, DdxLoginReply, ddxPhone, ddxCode, waitSeconds } from './ddx'

describe('DDX public contracts', () => {
  it('accepts familiar phone formatting without guessing a country for incomplete numbers', () => {
    expect(ddxPhone('8 (900) 000-00-01')).toBe('+79000000001')
    expect(ddxPhone('7 900 000 00 01')).toBe('+79000000001')
    expect(ddxPhone('+381 60 000-0000')).toBe('+381600000000')
    for (const phone of ['9000000001', '+123', '+7abc9000000001', '+79000000001/extra', ''])
      expect(ddxPhone(phone)).toBeNull()
    expect(ddxCode('001234')).toBe('001234')
    expect(ddxCode('12345')).toBeNull()
    expect(ddxCode('1234ab')).toBeNull()
  })
  it('retains only public login and connection fields', () => {
    const reply = DdxLoginReply.parse({
      login: {
        id: 'synthetic-login',
        state: 'waiting_code',
        phone_masked: '+7••••••0001',
        can_resend: false,
        code: '123456',
        access_token: 'synthetic-secret',
      },
      refresh_token: 'synthetic-secret',
    })
    expect(JSON.stringify(reply)).not.toMatch(/secret|123456|access_token|refresh_token/)
    const connection = DdxConnection.parse({
      provider: 'ddx',
      generation: 1,
      revision: 1,
      auth_state: 'active',
      automatic: true,
      credential_ref: '/private/synthetic',
      phone: '+79000000001',
      tokens: { access_token: 'secret' },
    })
    expect(JSON.stringify(connection)).not.toMatch(/credential|private|79000000001|tokens/)
  })
  it('shows server cooldowns without allowing early resend', () => {
    const now = Date.parse('2026-10-02T10:00:00Z')
    expect(waitSeconds('2026-10-02T10:00:30.100Z', now)).toBe(31)
    expect(waitSeconds('2026-10-02T09:00:00Z', now)).toBe(0)
    expect(waitSeconds(null, now)).toBe(0)
  })
})
