import { describe, expect, it } from 'vitest'
import { Day } from '@/sections/food/api'
import { StatsDay } from '@/sections/food/stats'
import {
  compareNutritionDecimals,
  DashboardNutritionRange,
  nutritionStatus,
  rangeNutritionRow,
  selectedNutritionRow,
  type NutritionRow,
} from './dashboardNutrition'

const row: NutritionRow = {
  date: '2026-10-05',
  logged: true,
  energy: '2200',
  energyComplete: true,
  protein: '120',
  proteinComplete: true,
  fat: '60',
  carbs: '200',
  completed: true,
  limit: '2200',
  minimum: '120',
}

describe('dashboard nutrition goals', () => {
  it('uses inclusive decimal boundaries before display rounding', () => {
    expect(nutritionStatus(row)).toEqual({ tone: 'goal', reason: 'bothGoals' })
    expect(nutritionStatus({ ...row, energy: '2200.000000000000001' }).reason).toBe('overLimit')
    expect(nutritionStatus({ ...row, protein: '119.999999999999999' }).reason).toBe('proteinLow')
    expect(compareNutritionDecimals('2.2E+3', '2200.000')).toBe(0)
    expect(compareNutritionDecimals('1.2E+2', '120')).toBe(0)
    expect(compareNutritionDecimals(null, '120')).toBeNull()
    expect(compareNutritionDecimals('', '120')).toBeNull()
    expect(compareNutritionDecimals('bad', '120')).toBeNull()
  })

  it('requires logged food, both goals, energy completeness and explicit day completion', () => {
    expect(nutritionStatus({ ...row, logged: false })).toEqual({ tone: 'empty', reason: 'empty' })
    expect(nutritionStatus({ ...row, completed: false }).tone).toBe('pending')
    expect(nutritionStatus({ ...row, energyComplete: false }).tone).toBe('pending')
    expect(nutritionStatus({ ...row, limit: null }).reason).toBe('goalsMissing')
    expect(nutritionStatus({ ...row, minimum: null }).reason).toBe('goalsMissing')
    expect(nutritionStatus({ ...row, energy: 'NaN' }).reason).toBe('energyUnknown')
    expect(nutritionStatus(row).tone).toBe('goal')
  })

  it('proves a protein minimum from known amounts but never invents a deficit', () => {
    expect(nutritionStatus({ ...row, proteinComplete: false }).tone).toBe('goal')
    expect(nutritionStatus({ ...row, proteinComplete: undefined }).tone).toBe('goal')
    expect(nutritionStatus({ ...row, protein: '103', proteinComplete: false }).reason).toBe(
      'proteinUnknown',
    )
    expect(nutritionStatus({ ...row, protein: '103', proteinComplete: undefined }).tone).toBe(
      'pending',
    )
    expect(nutritionStatus({ ...row, protein: '103', proteinComplete: true }).reason).toBe(
      'proteinLow',
    )
    expect(
      nutritionStatus({ ...row, protein: '103', proteinComplete: false, energy: '2300' }).reason,
    ).toBe('overLimit')
  })

  it('keeps historical goals and optional completeness in shared range parsing', () => {
    const historical = {
      date: '2026-09-29',
      logged: true,
      energy_kcal: '2030',
      energy_complete: true,
      protein_g: '115',
      protein_complete: false,
      fat_g: '60',
      carbohydrate_g: '200',
      completed: true,
      target: '2100',
      macro_targets: { protein_g: '115' },
      kcal_by_kind: {},
    }
    const parsed = StatsDay.parse(historical)
    expect(parsed.protein_complete).toBe(false)
    const transformed = rangeNutritionRow(
      DashboardNutritionRange.parse({ days: [parsed] }).days[0]!,
    )
    expect(transformed.limit).toBe('2100')
    expect(transformed.minimum).toBe('115')
    expect(nutritionStatus(transformed).tone).toBe('goal')
    const { protein_complete: omitted, ...older } = historical
    expect(omitted).toBe(false)
    expect(StatsDay.parse(older).protein_complete).toBeUndefined()
    expect(() => StatsDay.parse({ ...historical, protein_complete: 'true' })).toThrow()
  })

  it('uses selected-day completeness and guards empty totals and unresolved drafts', () => {
    const day = Day.parse({
      date: '2026-10-05',
      day_revision: 1,
      completed: true,
      entries: [{ id: 'entry', revision: 1, quantity: '1', unit: 'g', local_date: '2026-10-05' }],
      items: [],
      drafts: [],
      remaining_kcal: '0',
      remaining_complete: true,
      totals: {
        energy_kcal: { known: '2200', complete: true },
        protein_g: { known: '103', complete: true },
      },
      goal: {
        id: 'goal',
        revision: 1,
        calorie_target: '2200',
        effective_local_date: '2026-09-30',
        macro_targets: { protein_g: '120' },
      },
    })
    expect(nutritionStatus(selectedNutritionRow(day)).reason).toBe('proteinLow')
    expect(
      nutritionStatus(
        selectedNutritionRow({
          ...day,
          drafts: [
            {
              id: 'draft',
              label: 'unresolved',
              status: 'unresolved',
              missing_fields: [],
              revision: 1,
            },
          ],
        }),
      ).tone,
    ).toBe('pending')
    const empty = selectedNutritionRow({ ...day, entries: [] })
    expect(empty.energy).toBeNull()
    expect(empty.protein).toBeNull()
    expect(nutritionStatus(empty).tone).toBe('empty')
  })
})
