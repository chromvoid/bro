import { SHAPE_NAMES } from './morphology'

export const CORRECTION_NAMES = [
  'pose_shoulder_raise_l',
  'pose_shoulder_raise_r',
  'pose_elbow_flex_l',
  'pose_elbow_flex_r',
] as const
export type SurfaceRole = 'skin' | 'head_skin' | 'eye' | 'hair' | 'cloth' | 'accessory'
export type ReliefWeights = [number, number, number]
export interface PoseCorrective {
  name: (typeof CORRECTION_NAMES)[number]
  bone: string
  axis: [number, number, number]
  sign: number
  start_radians: number
  end_radians: number
  composition_scales: number[]
}
export interface BodyAssetContract {
  version: 'body-assets-v3'
  body_morph_names: string[]
  /** Nodes driven by the 30 composition shapes and four pose corrections. */
  body_surface_nodes: string[]
  /** Anatomical skin that frames the character, including a separate fixed head. */
  framing: { body_surface_nodes: string[] }
  surfaces: { node: string; role: SurfaceRole; material: string }[]
  pose_correctives: PoseCorrective[]
  relief: {
    normal_textures: { soft: number; neutral: number; defined: number }
    mask_texture: number
    grid_weights: ReliefWeights[]
    region_channels: ['torso', 'arms', 'legs']
  }
}

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    throw new Error('Invalid body asset metadata')
  return value as Record<string, unknown>
}
function strings(value: unknown): string[] {
  if (!Array.isArray(value) || !value.every((entry) => typeof entry === 'string' && entry.length))
    throw new Error('Invalid body asset names')
  return value as string[]
}
function finite(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value))
    throw new Error('Invalid body asset number')
  return value
}
function numbers(value: unknown, length: number): number[] {
  if (!Array.isArray(value) || value.length !== length) throw new Error('Invalid body asset vector')
  return value.map(finite)
}

/** Every runtime role and texture reference is explicit in the shipped v3 asset. */
export function readBodyAsset(value: unknown, textureCount: number): BodyAssetContract {
  const asset = record(value)
  if (asset.version !== 'body-assets-v3') throw new Error('Unsupported body asset version')
  const bodyNames = strings(asset.body_morph_names)
  if (
    bodyNames.length !== SHAPE_NAMES.length ||
    bodyNames.some((name, i) => name !== SHAPE_NAMES[i])
  )
    throw new Error('Invalid body morph contract')
  const bodyNodes = strings(asset.body_surface_nodes)
  if (!bodyNodes.length || new Set(bodyNodes).size !== bodyNodes.length)
    throw new Error('Invalid body morph nodes')
  if (!Array.isArray(asset.surfaces) || !asset.surfaces.length)
    throw new Error('Missing body surface roles')
  const surfaces = asset.surfaces.map((value) => {
    const surface = record(value)
    const [node, material] = strings([surface.node, surface.material]) as [string, string]
    if (
      typeof surface.role !== 'string' ||
      !['skin', 'head_skin', 'eye', 'hair', 'cloth', 'accessory'].includes(surface.role)
    )
      throw new Error('Invalid body surface role')
    const role = surface.role as SurfaceRole
    if (
      (role === 'skin' && !bodyNodes.includes(node)) ||
      (bodyNodes.includes(node) && role !== 'skin' && role !== 'head_skin')
    )
      throw new Error('Skin and body morph roles disagree')
    return { node, material, role }
  })
  if (
    new Set(surfaces.map(({ node, material }) => JSON.stringify([node, material]))).size !==
      surfaces.length ||
    bodyNodes.some(
      (node) => !surfaces.some((surface) => surface.node === node && surface.role === 'skin'),
    )
  )
    throw new Error('Ambiguous body surface roles')
  const framingNodes = strings(record(asset.framing).body_surface_nodes)
  if (
    !framingNodes.length ||
    new Set(framingNodes).size !== framingNodes.length ||
    framingNodes.some((node) => !surfaces.some((surface) => surface.node === node)) ||
    surfaces.some(
      ({ node, role }) => (role === 'skin' || role === 'head_skin') !== framingNodes.includes(node),
    )
  )
    throw new Error('Invalid anatomical framing nodes')
  if (
    !Array.isArray(asset.pose_correctives) ||
    asset.pose_correctives.length !== CORRECTION_NAMES.length
  )
    throw new Error('Invalid pose correction contract')
  const corrections = asset.pose_correctives.map((value, i): PoseCorrective => {
    const correction = record(value)
    if (correction.name !== CORRECTION_NAMES[i]) throw new Error('Invalid pose correction names')
    const [bone] = strings([correction.bone]) as [string]
    const axis = numbers(correction.axis, 3) as [number, number, number]
    if (Math.abs(Math.hypot(...axis) - 1) > 0.0001)
      throw new Error('Pose correction axis must be normalized')
    const sign = finite(correction.sign)
    const start = finite(correction.start_radians)
    const end = finite(correction.end_radians)
    const scales = numbers(correction.composition_scales, SHAPE_NAMES.length)
    if (
      Math.abs(sign) !== 1 ||
      start < 0 ||
      end <= start ||
      end > Math.PI ||
      scales.some((scale) => scale < 0 || scale > 2)
    )
      throw new Error('Invalid pose correction range')
    return {
      name: CORRECTION_NAMES[i]!,
      bone,
      axis,
      sign,
      start_radians: start,
      end_radians: end,
      composition_scales: scales,
    }
  })
  const relief = record(asset.relief)
  const textureIds = record(relief.normal_textures)
  const textureIndex = (value: unknown) => {
    const index = finite(value)
    if (!Number.isInteger(index) || index < 0 || index >= textureCount)
      throw new Error('Invalid relief texture reference')
    return index
  }
  const normalTextures = {
    soft: textureIndex(textureIds.soft),
    neutral: textureIndex(textureIds.neutral),
    defined: textureIndex(textureIds.defined),
  }
  const maskTexture = textureIndex(relief.mask_texture)
  if (new Set([...Object.values(normalTextures), maskTexture]).size !== 4)
    throw new Error('Relief textures must be independent')
  if (!Array.isArray(relief.grid_weights) || relief.grid_weights.length !== SHAPE_NAMES.length)
    throw new Error('Invalid relief grid')
  const gridWeights = relief.grid_weights.map((value): ReliefWeights => {
    const weights = numbers(value, 3) as ReliefWeights
    if (
      weights.some((weight) => weight < 0 || weight > 1) ||
      Math.abs(weights.reduce((sum, weight) => sum + weight, 0) - 1) > 0.0001
    )
      throw new Error('Relief weights must be convex')
    return weights
  })
  const channels = strings(relief.region_channels)
  if (channels.join(',') !== 'torso,arms,legs') throw new Error('Invalid relief regions')
  return {
    version: 'body-assets-v3',
    body_morph_names: bodyNames,
    body_surface_nodes: bodyNodes,
    framing: { body_surface_nodes: framingNodes },
    surfaces,
    pose_correctives: corrections,
    relief: {
      normal_textures: normalTextures,
      mask_texture: maskTexture,
      grid_weights: gridWeights,
      region_channels: ['torso', 'arms', 'legs'],
    },
  }
}
