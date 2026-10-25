import { describe, expect, it } from 'vitest'
import { admission, storedAdmission } from './admission'

const receipt = {
  submission_key: 'request',
  message_id: 'message',
  state: 'queued',
  admitted_at: '2026-10-02T12:00:00Z',
  trace_id: null,
}

describe('admission receipt identity', () => {
  it('keeps the original timestamp and trace while verifying the submission key', () => {
    const value = { ...receipt, trace_id: '0123456789abcdef0123456789abcdef' }
    expect(admission(value, 'request')).toEqual(value)
    expect(() => admission(value, 'another-request')).toThrow('Invalid admission')
  })
  it.each([
    '<html>success</html>',
    {},
    { ...receipt, message_id: '' },
    { ...receipt, admitted_at: 'yesterday' },
    { ...receipt, trace_id: 'private text' },
    { message_id: 'message', state: 'queued' },
  ])('does not confirm a malformed or old network receipt: %j', (value) => {
    expect(() => admission(value, 'request')).toThrow('Invalid admission')
  })
  it('reads stored legacy receipts, including their optional trace, but not partial new formats', () => {
    expect(
      storedAdmission({ message_id: 'old', state: 'completed', trace_id: null }, 'key')?.message_id,
    ).toBe('old')
    expect(
      storedAdmission({ message_id: 'old', state: 'queued', submission_key: 'key' }, 'key'),
    ).toBeNull()
    expect(storedAdmission({ ...receipt, submission_key: 'wrong' }, 'request')).toBeNull()
  })
})
