import { Bone, Box3, SkinnedMesh, Vector3, type Mesh, type Object3D } from 'three/webgpu'

export interface CharacterViewState {
  yaw: number
  polar: number
  radius: number
}

/** Keep a continuous angle while choosing the nearest equivalent destination. */
export function nearestViewYaw(from: number, to: number): number {
  return from + Math.atan2(Math.sin(to - from), Math.cos(to - from))
}

/** Explicit anatomical surfaces frame the scene, excluding their child accessories. */
export function bodyFramingBounds(meshes: Mesh[]): Box3 {
  const bounds = new Box3().makeEmpty()
  for (const mesh of meshes) {
    let surface: Box3 | null
    if (mesh instanceof SkinnedMesh) {
      mesh.computeBoundingBox()
      surface = mesh.boundingBox
    } else {
      mesh.geometry.computeBoundingBox()
      surface = mesh.geometry.boundingBox
    }
    if (!surface) throw new Error('Missing body framing bounds')
    bounds.union(surface.clone().applyMatrix4(mesh.matrixWorld))
  }
  return bounds
}

/** Capture the shared anatomical rig axis once, before sampling any animation. */
export function bodyOrbitAxis(meshes: Mesh[]): Vector3 {
  const joints = new Set<Bone>()
  for (const mesh of meshes) {
    if (mesh instanceof SkinnedMesh) {
      for (const bone of mesh.skeleton.bones) joints.add(bone)
    }
  }
  const roots = [...joints].filter(
    (bone) => !(bone.parent instanceof Bone) || !joints.has(bone.parent),
  )
  // Rigid or disconnected rigs still use only their declared anatomical surfaces.
  return roots.length === 1
    ? roots[0]!.getWorldPosition(new Vector3())
    : bodyFramingBounds(meshes).getCenter(new Vector3())
}

/** Ground and normalize the body around its anatomical axis, independent of authored offsets. */
export function normalizeBodyFrame(model: Object3D, bounds: Box3, axis: Vector3): Vector3 {
  const height = bounds.getSize(new Vector3()).y
  if (!Number.isFinite(height) || height < 0.1) throw new Error('Invalid character bounds')
  const stageHeight = 3.25
  const scale = stageHeight / height
  // The scene root's translation is not scaled with its children. Subtract it
  // before converting the world-space anchor and floor to the normalized stage.
  model.position.set(
    (model.position.x - axis.x) * scale,
    (model.position.y - bounds.min.y) * scale,
    (model.position.z - axis.z) * scale,
  )
  model.scale.multiplyScalar(scale)
  model.updateMatrixWorld(true)
  return new Vector3(0, stageHeight / 2, 0)
}
