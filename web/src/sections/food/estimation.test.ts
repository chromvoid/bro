import { describe, expect, it } from 'vitest'
import { estimationDetails } from './estimation'

describe('estimationDetails', () => {
  it('keeps human portion notes and missing evidence compatible', () => {
    expect(estimationDetails('Обычная чашка')).toEqual({
      note: 'Обычная чашка',
      sourceEstimated: false,
    })
    expect(estimationDetails(null)).toEqual({ note: '', sourceEstimated: false })
    expect(estimationDetails('{не JSON')).toEqual({ note: '{не JSON', sourceEstimated: false })
  })

  it('shows the note and recipe uncertainty without exposing its storage envelope', () => {
    expect(
      estimationDetails(
        JSON.stringify({
          kind: 'nutrition-assumptions/v1',
          quantity_estimated: false,
          note: '150 г по весам',
          composition: { unknown_fields: ['preparation'], source_recipe_verified: false },
        }),
      ),
    ).toEqual({ note: '150 г по весам', sourceEstimated: true })
  })
})
