import { CORRECTION_NAMES, type BodyAssetContract } from './assetContract'
import { SHAPE_NAMES } from './morphology'

/** Synthetic metadata for renderer unit fixtures; never imported by the application. */
export function syntheticBodyAsset(): BodyAssetContract {
  return {
    version: 'body-assets-v3',
    body_morph_names: [...SHAPE_NAMES],
    body_surface_nodes: ['InBodyCharacter'],
    framing: { body_surface_nodes: ['InBodyCharacter'] },
    surfaces: [{ node: 'InBodyCharacter', role: 'skin', material: 'InBody sculpt / anatomy' }],
    pose_correctives: CORRECTION_NAMES.map((name, i) => ({
      name,
      bone: ['upperarm01.L', 'upperarm01.R', 'lowerarm01.L', 'lowerarm01.R'][i]!,
      axis: i < 2 ? [0, 0, 1] : [1, 0, 0],
      sign: i === 1 ? -1 : 1,
      start_radians: 0.35,
      end_radians: i < 2 ? 1.2 : 1.55,
      composition_scales: Array(30).fill(1),
    })),
    relief: {
      normal_textures: { soft: 0, neutral: 1, defined: 2 },
      mask_texture: 3,
      region_channels: ['torso', 'arms', 'legs'],
      grid_weights: SHAPE_NAMES.map((_, i) => {
        const detail = Math.min(1, (((i % 5) / 4) * (1 - Math.floor(i / 5) / 5)) / 0.8)
        return detail <= 0.5
          ? [1 - 2 * detail, 2 * detail, 0]
          : [0, 2 * (1 - detail), 2 * detail - 1]
      }),
    },
  }
}
