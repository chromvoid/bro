import { describe, expect, it } from 'vitest'
import { readBodyAsset } from './assetContract'
import { syntheticBodyAsset } from './assetFixture'

describe('explicit v3 resource contract', () => {
  it('keeps independent body, correction, surface and texture roles', () => {
    const asset = syntheticBodyAsset()
    asset.surfaces.push({ node: 'SpartanHair', material: 'Spartan hair', role: 'hair' })
    expect(readBodyAsset(asset, 4)).toEqual(asset)
  })

  it('frames fixed head skin and keeps its neck material separate from body relief', () => {
    const asset = syntheticBodyAsset()
    asset.surfaces.push(
      { node: 'InBodyCharacter', material: 'Authored neck skin', role: 'head_skin' },
      { node: 'AuthoredHead', material: 'Authored head skin', role: 'head_skin' },
      { node: 'AuthoredEyes', material: 'Authored eyes', role: 'eye' },
      { node: 'AuthoredBeard', material: 'Authored beard', role: 'hair' },
    )
    asset.framing.body_surface_nodes.push('AuthoredHead')
    const parsed = readBodyAsset(asset, 4)
    expect(parsed).toEqual(asset)
    expect(parsed.body_surface_nodes).toEqual(['InBodyCharacter'])
    expect(parsed.framing.body_surface_nodes).toEqual(['InBodyCharacter', 'AuthoredHead'])
  })

  it('rejects a missing head or an eye/accessory in anatomical framing', () => {
    const asset = syntheticBodyAsset()
    asset.surfaces.push({ node: 'AuthoredHead', material: 'Head', role: 'head_skin' })
    expect(() => readBodyAsset(asset, 4)).toThrow('framing')
    asset.framing.body_surface_nodes.push('AuthoredHead')
    asset.surfaces.push({ node: 'AuthoredEyes', material: 'Eyes', role: 'eye' })
    expect(readBodyAsset(asset, 4)).toEqual(asset)
    asset.framing.body_surface_nodes.push('AuthoredEyes')
    expect(() => readBodyAsset(asset, 4)).toThrow('framing')
    asset.surfaces[2]!.role = 'hair'
    expect(() => readBodyAsset(asset, 4)).toThrow('framing')
    asset.surfaces[2]!.role = 'accessory'
    expect(() => readBodyAsset(asset, 4)).toThrow('framing')
  })

  it('rejects repeated framing names and ambiguous materials on the body bridge', () => {
    const asset = syntheticBodyAsset()
    asset.framing.body_surface_nodes.push('InBodyCharacter')
    expect(() => readBodyAsset(asset, 4)).toThrow('framing')
    asset.framing.body_surface_nodes.pop()
    asset.surfaces.push({ ...asset.surfaces[0]!, role: 'head_skin' })
    expect(() => readBodyAsset(asset, 4)).toThrow('Ambiguous')
  })

  it.each([
    (asset: ReturnType<typeof syntheticBodyAsset>) => ({ ...asset, version: 'body-assets-v2' }),
    (asset: ReturnType<typeof syntheticBodyAsset>) => ({ ...asset, body_morph_names: [] }),
    (asset: ReturnType<typeof syntheticBodyAsset>) => ({ ...asset, surfaces: [] }),
    (asset: ReturnType<typeof syntheticBodyAsset>) => ({ ...asset, framing: undefined }),
    (asset: ReturnType<typeof syntheticBodyAsset>) => ({ ...asset, pose_correctives: [] }),
    (asset: ReturnType<typeof syntheticBodyAsset>) => ({
      ...asset,
      relief: { ...asset.relief, mask_texture: 12 },
    }),
  ])('rejects a missing or incompatible asset role rather than guessing', (change) => {
    expect(() => readBodyAsset(change(syntheticBodyAsset()), 4)).toThrow()
  })

  it('rejects invalid directional drivers and non-convex relief coefficients', () => {
    const asset = syntheticBodyAsset()
    asset.pose_correctives[0]!.axis = [0, 0, 2]
    expect(() => readBodyAsset(asset, 4)).toThrow('normalized')
    asset.pose_correctives[0]!.axis = [0, 0, 1]
    asset.pose_correctives[0]!.composition_scales[0] = Infinity
    expect(() => readBodyAsset(asset, 4)).toThrow()
    asset.pose_correctives[0]!.composition_scales[0] = 1
    asset.relief.grid_weights[0] = [1, 1, 0]
    expect(() => readBodyAsset(asset, 4)).toThrow('convex')
  })
})
