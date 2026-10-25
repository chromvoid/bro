import { Bone, PropertyBinding, Quaternion, Vector3, type Mesh, type Object3D } from 'three/webgpu'
import type { BodyAssetContract, PoseCorrective } from './assetContract'
import { compositionWeights, ease, type Composition } from './morphology'

/** Signed twist relative to the bind pose, invariant to quaternion sign and full turns. */
export function correctiveActivation(
  rotation: Quaternion,
  rest: Quaternion,
  correction: Pick<PoseCorrective, 'axis' | 'sign' | 'start_radians' | 'end_radians'>,
): number {
  const delta = rest.clone().invert().multiply(rotation).normalize()
  if (delta.w < 0) delta.set(-delta.x, -delta.y, -delta.z, -delta.w)
  const axis = new Vector3(...correction.axis)
  const projected = delta.x * axis.x + delta.y * axis.y + delta.z * axis.z
  const angle = correction.sign * 2 * Math.atan2(projected, delta.w)
  return ease(
    (angle - correction.start_radians) / (correction.end_radians - correction.start_radians),
  )
}

/** Bone-driven corrections only write their own additive target indices. */
export function createPoseCorrections(root: Object3D, meshes: Mesh[], asset: BodyAssetContract) {
  const drivers = asset.pose_correctives.map((correction) => {
    const bone = root.getObjectByName(PropertyBinding.sanitizeNodeName(correction.bone))
    if (!(bone instanceof Bone)) throw new Error('Missing pose correction bone')
    const bindings = meshes.flatMap((mesh) => {
      const index = mesh.morphTargetDictionary?.[correction.name]
      return index === undefined ? [] : [{ mesh, index }]
    })
    if (!bindings.length) throw new Error('Missing pose correction target')
    return { correction, bone, rest: bone.quaternion.clone(), bindings }
  })
  return {
    apply(composition: Composition) {
      const bodyWeights = compositionWeights(composition)
      let maximum = 0
      for (const { correction, bone, rest, bindings } of drivers) {
        const scale = bodyWeights.reduce(
          (sum, weight, i) => sum + weight * correction.composition_scales[i]!,
          0,
        )
        const weight = correctiveActivation(bone.quaternion, rest, correction) * scale
        maximum = Math.max(maximum, weight)
        for (const { mesh, index } of bindings)
          if (mesh.morphTargetInfluences![index] !== weight)
            mesh.morphTargetInfluences![index] = weight
      }
      return maximum
    },
  }
}
