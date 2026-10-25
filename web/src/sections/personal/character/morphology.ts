export interface Composition {
  fat: number
  muscle: number
}
export type PhysiquePreset = 'neutral' | 'athletic' | 'soft'
export const COMPOSITION_PRESETS: Record<PhysiquePreset, Composition> = {
  neutral: { fat: 0.4, muscle: 0.5 },
  athletic: { fat: 0.2, muscle: 1 },
  soft: { fat: 0.85, muscle: 0.2 },
}
export const SHAPE_NAMES = Array.from(
  { length: 30 },
  (_, index) => `shape_f${Math.floor(index / 5)}_m${index % 5}`,
)
const unit = (value: number, fallback: number) =>
  Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : fallback
export function normalizeComposition(value: Composition): Composition {
  return { fat: unit(value.fat, 0.4), muscle: unit(value.muscle, 0.5) }
}
export const ease = (value: number) => {
  const t = Math.min(1, Math.max(0, value))
  return t * t * (3 - 2 * t)
}

/** Four adjacent authored shapes; no extrapolation, accumulation or medical inference. */
export function compositionWeights(value: Composition): number[] {
  const composition = normalizeComposition(value)
  const fat = composition.fat * 5
  const muscle = composition.muscle * 4
  return SHAPE_NAMES.map(
    (_, index) =>
      Math.max(0, 1 - Math.abs(fat - Math.floor(index / 5))) *
      Math.max(0, 1 - Math.abs(muscle - (index % 5))),
  )
}
export function interpolateComposition(
  from: Composition,
  to: Composition,
  progress: number,
): Composition {
  const t = ease(progress)
  return {
    fat: from.fat + (to.fat - from.fat) * t,
    muscle: from.muscle + (to.muscle - from.muscle) * t,
  }
}
// The relief grid and local mask already govern definition. Keep the body
// material strength stable so composition does not attenuate the details twice.
export const BODY_NORMAL_STRENGTH = 0.6
