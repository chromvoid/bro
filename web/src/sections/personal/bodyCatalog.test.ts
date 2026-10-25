import { describe, expect, it } from 'vitest'
import { BODY_ASSETS, bodyAsset } from './bodyCatalog'

describe('body character catalog', () => {
  it('binds each body to its own animation and neutral poster', () => {
    for (const [key, asset] of Object.entries(BODY_ASSETS)) {
      expect(asset.modelUrl).toContain(`${key}.glb`)
      expect(asset.animationUrl).toContain(`${key}-lookaround.glb`)
      expect(asset.posterUrl).toContain(`${key}.webp`)
    }
    expect(bodyAsset('male', 'spartan')).toBe(BODY_ASSETS.male_spartan)
  })
  it('resolves the incompatible saved choice without modifying the choice', () => {
    expect(bodyAsset('female', 'spartan')).toBe(BODY_ASSETS.female)
    expect(bodyAsset('male', 'sculpture')).toBe(BODY_ASSETS.male)
  })
})
