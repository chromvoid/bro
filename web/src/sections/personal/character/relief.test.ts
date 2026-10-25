import { describe, expect, it } from 'vitest'
import { Mesh, MeshStandardMaterial, Texture, Vector2 } from 'three/webgpu'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { syntheticBodyAsset } from './assetFixture'
import { createReliefNode, reliefWeights } from './relief'
import { BODY_NORMAL_STRENGTH } from './morphology'

describe('relief composition', () => {
  it('remains a convex continuous blend on both sides of every body-grid boundary', () => {
    const grid = syntheticBodyAsset().relief.grid_weights
    for (const fat of [0, 0.2, 0.4, 0.6, 0.8, 1])
      for (const muscle of [0, 0.25, 0.5, 0.75, 1]) {
        const weights = reliefWeights({ fat, muscle }, grid)
        expect(weights.reduce((sum, value) => sum + value, 0)).toBeCloseTo(1)
        expect(weights.every((value) => value >= 0 && value <= 1)).toBe(true)
        const before = reliefWeights({ fat: fat - 1e-6, muscle: muscle - 1e-6 }, grid)
        const after = reliefWeights({ fat: fat + 1e-6, muscle: muscle + 1e-6 }, grid)
        expect(Math.max(...before.map((value, i) => Math.abs(value - after[i]!)))).toBeLessThan(
          0.00003,
        )
      }
    expect(reliefWeights({ fat: 0.2, muscle: 1 }, grid)).toEqual([0, 0, 1])
    expect(reliefWeights({ fat: 1, muscle: 1 }, grid)).toEqual([1, 0, 0])
  })

  it('changes definition with either composition axis', () => {
    const grid = syntheticBodyAsset().relief.grid_weights
    const detail = (fat: number, muscle: number) => {
      const composition = { fat, muscle }
      const weights = reliefWeights(composition, grid)
      return weights[1] / 2 + weights[2]
    }
    const lowMuscle = detail(0.4, 0)
    const middleMuscle = detail(0.4, 0.5)
    const highMuscle = detail(0.4, 1)
    expect(lowMuscle).toBeLessThan(middleMuscle)
    expect(middleMuscle).toBeLessThan(highMuscle)

    const lowFat = detail(0, 0.5)
    const middleFat = detail(0.4, 0.5)
    const highFat = detail(1, 0.5)
    expect(lowFat).toBeGreaterThan(middleFat)
    expect(middleFat).toBeGreaterThan(highFat)
  })
})

describe('relief tangent orientation', () => {
  it('preserves real GLTFLoader tangent and derivative signs without changing gain or sharing vectors', async () => {
    const loadedTexture = new Texture()
    const loader = new GLTFLoader().register(() => ({
      name: 'SYNTHETIC_NORMAL_TEXTURE',
      loadTexture: async () => loadedTexture,
    }))
    const gltf = await loader.parseAsync(
      JSON.stringify({
        asset: { version: '2.0' },
        scene: 0,
        scenes: [{ nodes: [0] }],
        nodes: [{ mesh: 0 }],
        meshes: [
          {
            primitives: [
              { attributes: { POSITION: 0, NORMAL: 0, TEXCOORD_0: 1 }, material: 0 },
              { attributes: { POSITION: 0, NORMAL: 0, TEXCOORD_0: 1, TANGENT: 2 }, material: 0 },
            ],
          },
        ],
        materials: [{ normalTexture: { index: 0, scale: BODY_NORMAL_STRENGTH } }],
        textures: [{ source: 0 }],
        images: [{}],
        accessors: [
          { componentType: 5126, type: 'VEC3', count: 3, min: [0, 0, 0], max: [0, 0, 0] },
          { componentType: 5126, type: 'VEC2', count: 3 },
          { componentType: 5126, type: 'VEC4', count: 3 },
        ],
      }),
      '',
    )
    const meshes: Mesh[] = []
    gltf.scene.traverse((object) => {
      if (object instanceof Mesh) meshes.push(object)
    })
    expect(meshes).toHaveLength(2)
    const imported = meshes.map((mesh) => (mesh.material as MeshStandardMaterial).normalScale)
    const originals = imported.map((scale) => scale.toArray())
    expect(meshes.map((mesh) => mesh.geometry.hasAttribute('tangent'))).toEqual([false, true])
    expect(originals).toEqual([
      [BODY_NORMAL_STRENGTH, -BODY_NORMAL_STRENGTH],
      [BODY_NORMAL_STRENGTH, BODY_NORMAL_STRENGTH],
    ])
    const maps = Array.from({ length: 4 }, () => new Texture())
    const relief = createReliefNode(syntheticBodyAsset(), maps)
    const bindings = imported.map((scale) => relief.forSurface(scale))
    expect(bindings[0]!.scale.value).not.toBe(imported[0])
    expect(bindings[0]!.scale.value).not.toBe(bindings[1]!.scale.value)
    for (const strength of [
      BODY_NORMAL_STRENGTH,
      0,
      0.2,
      BODY_NORMAL_STRENGTH,
      BODY_NORMAL_STRENGTH,
    ]) {
      relief.update({ fat: 0.2, muscle: 1 }, strength)
      expect(bindings[0]!.scale.value.x).toBe(strength)
      expect(bindings[0]!.scale.value.y).toBe(-strength)
      expect(bindings[1]!.scale.value.toArray()).toEqual([strength, strength])
      expect(imported.map((scale) => scale.toArray())).toEqual(originals)
    }
    for (const mesh of meshes) {
      mesh.geometry.dispose()
      ;(mesh.material as MeshStandardMaterial).dispose()
    }
    for (const map of maps) map.dispose()
    loadedTexture.dispose()
  })

  it('keeps anisotropy, zero axes and a newly bound surface after updates', () => {
    const maps = Array.from({ length: 4 }, () => new Texture())
    const relief = createReliefNode(syntheticBodyAsset(), maps)
    relief.update({ fat: 0.2, muscle: 1 }, 0.6)
    const source = new Vector2(0.3, -0.6)
    const anisotropic = relief.forSurface(source)
    const zeroAxis = relief.forSurface(new Vector2(0, -0.6))
    const disabled = relief.forSurface(new Vector2(0, 0))
    source.set(1, 1)
    for (const strength of [0.6, 0, 0.6]) {
      relief.update({ fat: 0.2, muscle: 1 }, strength)
      expect(anisotropic.scale.value.x).toBe(strength / 2)
      expect(anisotropic.scale.value.y).toBe(-strength)
      expect(zeroAxis.scale.value.x).toBe(0)
      expect(zeroAxis.scale.value.y).toBe(-strength)
      expect(disabled.scale.value.toArray()).toEqual([0, 0])
    }
    for (const map of maps) map.dispose()
  })
})
