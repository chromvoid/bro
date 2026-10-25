import { describe, expect, it } from 'vitest'
import { amountText, hasTarget, Profile } from './api'
import {
  distanceTargetMeters,
  distanceText,
  heightText,
  massText,
  temperatureText,
} from './displayUnits'
import { sensorAmount } from './sensors'

describe('presentation units and canonical goals', () => {
  it('converts the view without changing canonical observations', () => {
    expect(Profile.parse({ user_id: 'owner', timezone: 'UTC', revision: 1 }).display_units).toBe(
      'metric',
    )
    expect(amountText('distance', '1609.344', 'imperial')).toBe('1 мили')
    expect(amountText('distance', '1609.344', 'metric')).toBe('1,61 км')
    expect(distanceText(null, 'imperial')).toBeNull()
    expect(amountText('active_energy', '113.719', 'imperial')).toBe('114 ккал')
    expect(hasTarget('active_energy')).toBe(true)
    expect(hasTarget('distance')).toBe(true)
  })
  it('converts mass, height and measured temperature only for presentation', () => {
    expect(amountText('weight', '80', 'imperial')).toBe('176,37 lb')
    expect(amountText('skeletal_muscle_mass', '32', 'imperial')).toBe('70,55 lb')
    expect(amountText('body_fat_percent', '18', 'imperial')).toBe('18 %')
    expect(heightText('180', 'imperial')).toBe('5 ft 10,9 in')
    expect(heightText('182.88', 'imperial')).toBe('6 ft 0 in')
    expect(heightText('180', 'metric')).toBe('180 см')
    expect(sensorAmount('36.5', 'celsius', 'imperial')).toBe('97,7 °F')
    expect(sensorAmount(null, 'celsius', 'imperial')).toBeNull()
    expect(sensorAmount('0', 'celsius', 'imperial')).toBe('32 °F')
    expect(massText(null, 'imperial')).toBeNull()
    expect(temperatureText('', 'imperial')).toBeNull()
  })
  it('turns decimal kilometer/mile input into an exact decimal meter target', () => {
    expect(distanceTargetMeters('1,25', 'imperial')).toBe('2011.68')
    expect(distanceTargetMeters('0.001', 'imperial')).toBe('1.609344')
    expect(distanceTargetMeters('1.58275', 'metric')).toBe('1582.75')
    expect(distanceTargetMeters('invalid', 'metric')).toBeNull()
  })
})
