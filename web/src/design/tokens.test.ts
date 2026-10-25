// @vitest-environment node
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const source = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8')

type Color = [number, number, number]
type Tokens = Record<string, Color>
type Declarations = Record<string, string>
type Gamut = 'srgb' | 'p3'

/** Opaque OKLCH color-mix defaults to the shorter hue arc. */
function mixOklch(first: Color, firstWeight: number, second: Color): Color {
  if (firstWeight < 0 || firstWeight > 1)
    throw new Error('Color mix weight must be between 0 and 1')
  let firstHue = first[2]
  let secondHue = second[2]
  if (first[1] === 0) firstHue = secondHue
  else if (second[1] === 0) secondHue = firstHue
  if (secondHue - firstHue > 180) firstHue += 360
  else if (firstHue - secondHue > 180) secondHue += 360
  const secondWeight = 1 - firstWeight
  return [
    first[0] * firstWeight + second[0] * secondWeight,
    first[1] * firstWeight + second[1] * secondWeight,
    (((firstHue * firstWeight + secondHue * secondWeight) % 360) + 360) % 360,
  ]
}

/** Read each theme's baseline and its optional P3 media overrides separately. */
function themes(css: string): { light: Tokens; dark: Tokens; lightP3: Tokens; darkP3: Tokens } {
  const [light = '', dark = ''] = css.split('@variant dark')
  const read = (block: string): Declarations =>
    Object.fromEntries(
      [...block.matchAll(/--([\w-]+):\s*([^;]+);/g)].map((match) => [match[1], match[2]!.trim()]),
    )
  const resolve = (declarations: Declarations): Tokens => {
    const colors: Tokens = {}
    const visit = (name: string, stack: string[] = []): Color | undefined => {
      if (colors[name]) return colors[name]
      if (stack.includes(name))
        throw new Error(`Cyclic color alias: ${[...stack, name].join(' -> ')}`)
      const value = declarations[name]
      if (value === undefined) throw new Error(`Missing color token: ${name}`)
      const literal = /^oklch\(([\d.]+) ([\d.]+) ([\d.]+)\)$/.exec(value)
      const alias = /^var\(--([\w-]+)\)$/.exec(value)
      // The palette's opaque mixes use two aliases and one percentage; translucent effects
      // are deliberately excluded because they also need the surface behind them.
      const mix = /^color-mix\(in oklch, var\(--([\w-]+)\) ([\d.]+)%, var\(--([\w-]+)\)\)$/.exec(
        value,
      )
      let color: Color | undefined
      if (literal) color = [Number(literal[1]), Number(literal[2]), Number(literal[3])]
      else if (alias) color = visit(alias[1]!, [...stack, name])
      else if (mix) {
        const first = visit(mix[1]!, [...stack, name])
        const second = visit(mix[3]!, [...stack, name])
        if (first && second) color = mixOklch(first, Number(mix[2]) / 100, second)
      }
      if (color) colors[name] = color
      return color
    }
    for (const name of Object.keys(declarations)) visit(name)
    return colors
  }
  const splitGamut = (block: string) => {
    const media = /@media \(color-gamut: p3\)\s*\{([^{}]*)\}/g
    return {
      baseline: read(block.replace(media, '')),
      p3: read([...block.matchAll(media)].map((match) => match[1]).join('\n')),
    }
  }
  const lightTokens = splitGamut(light)
  const darkTokens = splitGamut(dark)
  const baselineDark = { ...lightTokens.baseline, ...darkTokens.baseline }
  return {
    light: resolve(lightTokens.baseline),
    dark: resolve(baselineDark),
    lightP3: resolve({ ...lightTokens.baseline, ...lightTokens.p3 }),
    darkP3: resolve({ ...baselineDark, ...darkTokens.p3 }),
  }
}

/** OKLCH → linear sRGB. Do not clip: that could hide an out-of-gamut contrast failure. */
function linearRgb([lightness, chroma, hue]: Color): Color {
  const a = chroma * Math.cos((hue * Math.PI) / 180)
  const b = chroma * Math.sin((hue * Math.PI) / 180)
  const l = (lightness + 0.3963377774 * a + 0.2158037573 * b) ** 3
  const m = (lightness - 0.1055613458 * a - 0.0638541728 * b) ** 3
  const s = (lightness - 0.0894841775 * a - 1.291485548 * b) ** 3
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ]
}

/** sRGB and Display-P3 share D65; this matrix changes linear RGB primaries. */
function channels(color: Color, gamut: Gamut): Color {
  const [red, green, blue] = linearRgb(color)
  if (gamut === 'srgb') return [red, green, blue]
  return [
    0.8224619687 * red + 0.1775380313 * green,
    0.0331941989 * red + 0.9668058011 * green,
    0.0170826307 * red + 0.0723974407 * green + 0.9105199286 * blue,
  ]
}

/** Relative luminance stays the same when an in-gamut color changes RGB primaries. */
function luminance(color: Color): number {
  const [red, green, blue] = linearRgb(color)
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

/** Euclidean distance in OKLab: about 0.02 is a just-noticeable difference. */
function oklabDistance([l1, c1, h1]: Color, [l2, c2, h2]: Color): number {
  const radians = Math.PI / 180
  return Math.hypot(
    l1 - l2,
    c1 * Math.cos(h1 * radians) - c2 * Math.cos(h2 * radians),
    c1 * Math.sin(h1 * radians) - c2 * Math.sin(h2 * radians),
  )
}

function contrast(tokens: Tokens, text: string, surface: string): number {
  const first = luminance(tokens[text]!)
  const second = luminance(tokens[surface]!)
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05)
}

// Text (or icons carrying meaning) on the surfaces it is used on.
const PAIRS: Array<[string, string]> = [
  ['foreground', 'background'],
  ['foreground', 'card'],
  ['foreground', 'muted'],
  ['card-foreground', 'card'],
  ['popover-foreground', 'popover'],
  ['muted-foreground', 'background'],
  ['muted-foreground', 'card'],
  ['muted-foreground', 'muted'],
  ['primary', 'background'],
  ['primary', 'card'],
  ['primary', 'sidebar'],
  ['primary-foreground', 'primary'],
  ['secondary-foreground', 'secondary'],
  ['accent-foreground', 'accent'],
  ['accent-foreground', 'muted'],
  ['destructive', 'background'],
  ['destructive', 'card'],
  ['destructive', 'destructive-surface'],
  ['destructive-foreground', 'destructive'],
  ['warning', 'warning-surface'],
  ['warning', 'card'],
  ['success', 'success-surface'],
  ['success', 'card'],
  ['sidebar-foreground', 'sidebar'],
  ['sidebar-accent-foreground', 'sidebar-accent'],
  ['sidebar-primary-foreground', 'sidebar-primary'],
  // The launch screen's mark (boot.css and both shells).
  ['splash-foreground', 'splash-background'],
]

describe('design tokens', () => {
  const { light, dark, lightP3, darkP3 } = themes(source)

  for (const [name, tokens, gamut] of [
    ['light sRGB', light, 'srgb'],
    ['dark sRGB', dark, 'srgb'],
    ['light Display-P3', lightP3, 'p3'],
    ['dark Display-P3', darkP3, 'p3'],
  ] as const) {
    it(`meet WCAG AA text contrast in the ${name} theme`, () => {
      const failing = PAIRS.map(([text, surface]) => ({
        pair: `${text} on ${surface}`,
        ratio: contrast(tokens, text, surface),
      })).filter(({ ratio }) => ratio < 4.5)
      expect(failing).toEqual([])
    })

    it(`fit the ${gamut} gamut in the ${name} theme`, () => {
      // Allow the tiny conversion/decimal-rounding error at white and the sRGB boundary.
      const outside = Object.entries(tokens)
        .map(([token, color]) => ({ token, channels: channels(color, gamut) }))
        .filter(({ channels }) => channels.some((value) => value < -0.0001 || value > 1.0001))
      expect(outside).toEqual([])
    })

    it(`keep controls, focus and chart marks at 3:1 in the ${name} theme`, () => {
      const controls: Array<[string, string]> = [
        ['input', 'background'],
        ['input', 'card'],
        ['input', 'popover'],
        ['input', 'muted'],
        ['ring', 'background'],
        ['ring', 'card'],
        ['sidebar-ring', 'sidebar'],
        // Bars and dots past a goal, and the trading charts' rising bars. A goal's amber bar is
        // always full, so it meets the page rather than its track.
        ['warning-fill', 'background'],
        ['warning-fill', 'card'],
        ['success', 'background'],
        ['success', 'card'],
        ...[1, 2, 3, 4, 5].flatMap((index): Array<[string, string]> => [
          [`chart-${index}`, 'background'],
          [`chart-${index}`, 'card'],
        ]),
      ]
      const failing = controls
        .map(([mark, surface]) => ({
          pair: `${mark} on ${surface}`,
          ratio: contrast(tokens, mark, surface),
        }))
        .filter(({ ratio }) => ratio < 3)
      expect(failing).toEqual([])
    })

    it(`keep chart colours apart from the status colours in the ${name} theme`, () => {
      // A snack segment beside an «over goal» bar must not read as a warning (chart-4 was amber).
      const statuses = ['success', 'warning', 'warning-fill', 'destructive']
      const close = [1, 2, 3, 4, 5]
        .flatMap((index) =>
          statuses.map((status) => ({
            pair: `chart-${index} and ${status}`,
            distance: oklabDistance(tokens[`chart-${index}`]!, tokens[status]!),
          })),
        )
        .filter(({ distance }) => distance < 0.06)
      expect(close).toEqual([])
    })
  }

  it('reads both themes from the token file', () => {
    expect(Object.keys(light).length).toBeGreaterThan(30)
    expect(dark.background![0]).toBeLessThan(0.3)
  })

  it('enhances focus beyond sRGB on Display-P3 without saturating filled surfaces', () => {
    for (const [baseline, enhanced] of [
      [light, lightP3],
      [dark, darkP3],
    ] as const) {
      expect(enhanced.ring![1]).toBeGreaterThan(baseline.ring![1])
      expect(channels(enhanced.ring!, 'srgb').some((value) => value < 0 || value > 1)).toBe(true)
      expect(enhanced.primary).toEqual(baseline.primary)
      expect(enhanced.accent).toEqual(baseline.accent)
      expect(enhanced.success).toEqual(baseline.success)
      expect(enhanced.background).toEqual(baseline.background)
      expect(enhanced.foreground).toEqual(baseline.foreground)
    }
  })

  it('preserves white and black luminance across both RGB gamuts', () => {
    for (const gamut of ['srgb', 'p3'] as const) {
      for (const channel of channels([1, 0, 0], gamut)) expect(channel).toBeCloseTo(1, 6)
      expect(channels([0, 0, 0], gamut)).toEqual([0, 0, 0])
    }
    expect(luminance([1, 0, 0])).toBeCloseTo(1, 6)
    expect(luminance([0, 0, 0])).toBe(0)
  })

  it('interpolates opaque mix hues across the shorter arc', () => {
    const wrapped = mixOklch([0.7, 0.1, 350], 0.5, [0.3, 0.02, 10])
    expect(wrapped[0]).toBeCloseTo(0.5, 6)
    expect(wrapped[1]).toBeCloseTo(0.06, 6)
    expect(wrapped[2]).toBe(0)
    const surface = mixOklch([0.72, 0.115, 25], 0.1, [0.29, 0.01, 275])
    expect(surface[0]).toBeCloseTo(0.333, 6)
    expect(surface[1]).toBeCloseTo(0.0205, 6)
    expect(surface[2]).toBeCloseTo(286, 6)
  })
})
