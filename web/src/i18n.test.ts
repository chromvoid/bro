import { describe, expect, it } from 'vitest'
import { russianPlural } from './i18n'

describe('russian plurals', () => {
  it('picks one, few and many', () => {
    expect([1, 2, 5, 11, 21, 22, 25].map((count) => russianPlural(count, 3))).toEqual([
      0, 1, 2, 2, 0, 1, 2,
    ])
  })

  it('puts a zero form first when there are four', () => {
    expect([0, 1, 3, 7].map((count) => russianPlural(count, 4))).toEqual([0, 1, 2, 3])
  })
})
