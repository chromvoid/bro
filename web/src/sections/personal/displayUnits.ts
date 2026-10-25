import { computed, inject, provide, type InjectionKey, type Ref } from 'vue'
import { formatAmount, parseAmount } from '@/core/numbers'

export type DisplayUnits = 'metric' | 'imperial'
const displayUnitsKey: InjectionKey<Readonly<Ref<DisplayUnits>>> = Symbol('personal-display-units')
export function provideDisplayUnits(units: Readonly<Ref<DisplayUnits>>) {
  provide(displayUnitsKey, units)
}
export function useDisplayUnits(): Readonly<Ref<DisplayUnits>> {
  return inject(
    displayUnitsKey,
    computed(() => 'metric' as const),
  )
}
export const distanceUnit = (units: DisplayUnits) => (units === 'imperial' ? 'мили' : 'км')
export const massUnit = (units: DisplayUnits) => (units === 'imperial' ? 'lb' : 'кг')
const presentNumber = (value: string | number | null): number | null =>
  value === null || (typeof value === 'string' && !value.trim()) || !Number.isFinite(Number(value))
    ? null
    : Number(value)
export function massValue(kilograms: string | number | null, units: DisplayUnits): number | null {
  const value = presentNumber(kilograms)
  return value === null ? null : units === 'imperial' ? value / 0.45359237 : value
}
export function massText(kilograms: string | number | null, units: DisplayUnits): string | null {
  const value = massValue(kilograms, units)
  return value === null ? null : `${formatAmount(value, 2)} ${massUnit(units)}`
}
export function heightText(
  centimeters: string | number | null,
  units: DisplayUnits,
): string | null {
  const value = presentNumber(centimeters)
  if (value === null) return null
  if (units === 'metric') return `${formatAmount(value, 1)} см`
  const totalInches = Math.round((value / 2.54) * 10) / 10
  const feet = Math.floor(totalInches / 12)
  return `${feet} ft ${formatAmount(totalInches - feet * 12, 1)} in`
}
export function temperatureText(
  celsius: string | number | null,
  units: DisplayUnits,
): string | null {
  const value = presentNumber(celsius)
  return value === null
    ? null
    : `${formatAmount(units === 'imperial' ? (value * 9) / 5 + 32 : value, 2)} ${units === 'imperial' ? '°F' : '°C'}`
}
export function distanceText(meters: string | number | null, units: DisplayUnits): string | null {
  if (
    meters === null ||
    (typeof meters === 'string' && !meters.trim()) ||
    !Number.isFinite(Number(meters))
  )
    return null
  return `${distanceNumber(Number(meters), units)} ${distanceUnit(units)}`
}
/** Meters as a number of display units: «1,58» kilometres or «0,98» miles. */
export function distanceNumber(meters: number, units: DisplayUnits): string {
  return formatAmount(meters / (units === 'imperial' ? 1609.344 : 1000), 2)
}
/** Display rounding never rewrites the stored decimal; unchanged drafts keep their original. */
export function distanceInput(meters: string | null, units: DisplayUnits): string {
  return meters === null
    ? ''
    : String(Number((Number(meters) / (units === 'imperial' ? 1609.344 : 1000)).toFixed(6)))
}
/** Exact decimal multiplication for a new km/mile goal, without binary floating point. */
export function distanceTargetMeters(input: string, units: DisplayUnits): string | null {
  const amount = parseAmount(input)
  if (!amount || amount.length > 50) return null
  const [whole = '0', fraction = ''] = amount.split('.')
  const scale = fraction.length + (units === 'imperial' ? 3 : 0)
  const multiplier = units === 'imperial' ? 1609344n : 1000n
  const digits = (BigInt(whole + fraction) * multiplier).toString().padStart(scale + 1, '0')
  const decimal = scale ? `${digits.slice(0, -scale)}.${digits.slice(-scale)}` : digits
  return decimal.includes('.') ? decimal.replace(/0+$/, '').replace(/\.$/, '') : decimal
}
