import { describe, expect, it } from 'vitest'
import { rememberStrengthDisclosure, strengthDisclosureOpen } from './strengthDisclosureState'

describe('strength reading state', () => {
  it('returns to opened details in the same context and keeps another exercise independent', () => {
    rememberStrengthDisclosure('exercise-one', 'total-barbell', true)
    expect(strengthDisclosureOpen('exercise-one', 'total-barbell')).toBe(true)
    expect(strengthDisclosureOpen('exercise-two', 'total-barbell')).toBe(false)
    expect(strengthDisclosureOpen('exercise-one', 'per-implement-dumbbell')).toBe(false)
    rememberStrengthDisclosure('exercise-one', 'total-barbell', false)
    expect(strengthDisclosureOpen('exercise-one', 'total-barbell')).toBe(false)
  })

  it('bounds retained contexts and disclosures', () => {
    for (let index = 0; index < 40; index++)
      rememberStrengthDisclosure(`context-${index}`, 'sets', true)
    expect(strengthDisclosureOpen('context-0', 'sets')).toBe(false)
    expect(strengthDisclosureOpen('context-39', 'sets')).toBe(true)
    for (let index = 0; index < 70; index++)
      rememberStrengthDisclosure('many-series', `series-${index}`, true)
    expect(strengthDisclosureOpen('many-series', 'series-0')).toBe(false)
    expect(strengthDisclosureOpen('many-series', 'series-69')).toBe(true)
  })
})
