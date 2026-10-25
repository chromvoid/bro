import { describe, expect, it } from 'vitest'
import { ageOn, profileField } from './profile'

describe('profile calendar age', () => {
  it.each([
    ['1990-10-05', '2026-10-04', 35],
    ['1990-10-05', '2026-10-05', 36],
    ['1990-10-05', '2026-10-06', 36],
    ['2000-02-29', '2025-02-28', 24],
    ['2000-02-29', '2025-03-01', 25],
    ['2026-10-05', '2026-10-05', 0],
    ['2000-10-05', '2015-10-04', 14],
  ])('derives completed years for birth %s on %s', (birth, day, expected) => {
    expect(ageOn(birth, day)).toBe(expected)
  })
  it.each([null, undefined, '', '2026-10-06', '2000-02-30', 'not-a-date'])(
    'keeps missing, future or invalid birth %s unknown',
    (birth) => expect(ageOn(birth, '2026-10-05')).toBeNull(),
  )
  it('only accepts supported focus fields, including rejecting repeated URL values', () => {
    expect(profileField('birth')).toBe('birth')
    expect(profileField('gender')).toBe('gender')
    expect(profileField('height')).toBe('height')
    expect(profileField('#profile-zone')).toBeUndefined()
    expect(profileField(['birth', 'gender'])).toBeUndefined()
  })
})
