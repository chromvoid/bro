import { NoColorSpace, Vector2, Vector3, type Texture } from 'three/webgpu'
import { normalMap, texture, uniform } from 'three/tsl'
import type { BodyAssetContract, ReliefWeights } from './assetContract'
import { compositionWeights, type Composition } from './morphology'

export function reliefWeights(composition: Composition, grid: ReliefWeights[]): ReliefWeights {
  const bodyWeights = compositionWeights(composition)
  return [0, 1, 2].map((channel) =>
    bodyWeights.reduce((sum, weight, i) => sum + weight * grid[i]![channel]!, 0),
  ) as ReliefWeights
}

/** Alternative tangent-space surfaces share UVs; interpolate directions, then renormalize. */
export function createReliefNode(asset: BodyAssetContract, maps: Texture[]) {
  const { normal_textures: ids, mask_texture: maskId } = asset.relief
  for (const index of [ids.soft, ids.neutral, ids.defined, maskId]) {
    if (!maps[index]) throw new Error('Missing relief texture')
    maps[index]!.colorSpace = NoColorSpace
  }
  const weights = uniform(new Vector3(0, 1, 0))
  const surfaces: { orientation: Vector2; scale: { value: Vector2 } }[] = []
  let strength = 0
  const unpack = (index: number) => texture(maps[index]!).rgb.mul(2).sub(1).normalize()
  const normal = unpack(ids.soft)
    .mul(weights.x)
    .add(unpack(ids.neutral).mul(weights.y))
    .add(unpack(ids.defined).mul(weights.z))
    .normalize()
  const mask = texture(maps[maskId]!)
  const regionalStrength = mask.r.add(mask.g.mul(0.85)).add(mask.b.mul(0.8)).clamp(0, 1)
  return {
    forSurface(importedScale: Vector2) {
      // GLTFLoader reverses Y for derivative tangents. Preserve each material's
      // signs and relative component magnitudes; strength sets the largest axis.
      const magnitude = Math.max(Math.abs(importedScale.x), Math.abs(importedScale.y))
      const orientation = importedScale.clone().divideScalar(magnitude || 1)
      const scale = uniform(orientation.clone().multiplyScalar(strength))
      const node = normalMap(normal.mul(0.5).add(0.5), scale.mul(regionalStrength))
      surfaces.push({ orientation, scale })
      return { node, scale }
    },
    update(composition: Composition, normalStrength: number) {
      weights.value.fromArray(reliefWeights(composition, asset.relief.grid_weights))
      strength = normalStrength
      for (const surface of surfaces)
        surface.scale.value.copy(surface.orientation).multiplyScalar(strength)
    },
  }
}
