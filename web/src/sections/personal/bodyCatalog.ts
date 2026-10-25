import type { BodyCharacterId } from './api'
import type { BodyVisualState } from './bodyVisual'

type BodyTemplate = BodyVisualState['template']

export interface BodyAsset {
  template: BodyTemplate
  characterId: BodyCharacterId
  modelUrl: string
  animationUrl: string
  posterUrl: string
}

/** Every catalog entry is one compatible body surface, skeleton and animation graph. */
export const BODY_ASSETS: Record<'male' | 'female' | 'male_spartan', BodyAsset> = {
  male: {
    template: 'male',
    characterId: 'sculpture',
    modelUrl: new URL('../../assets/body/male.glb', import.meta.url).href,
    animationUrl: new URL('../../assets/body/male-lookaround.glb', import.meta.url).href,
    posterUrl: new URL('../../assets/body/male.webp', import.meta.url).href,
  },
  female: {
    template: 'female',
    characterId: 'sculpture',
    modelUrl: new URL('../../assets/body/female.glb', import.meta.url).href,
    animationUrl: new URL('../../assets/body/female-lookaround.glb', import.meta.url).href,
    posterUrl: new URL('../../assets/body/female.webp', import.meta.url).href,
  },
  male_spartan: {
    template: 'male',
    characterId: 'spartan',
    modelUrl: new URL('../../assets/body/male_spartan.glb', import.meta.url).href,
    animationUrl: new URL('../../assets/body/male_spartan-lookaround.glb', import.meta.url).href,
    posterUrl: new URL('../../assets/body/male_spartan.webp', import.meta.url).href,
  },
}

/** A saved choice never changes the profile or overwrites itself when it is incompatible. */
export function bodyAsset(template: BodyTemplate, characterId: BodyCharacterId): BodyAsset {
  return BODY_ASSETS[template === 'male' && characterId === 'spartan' ? 'male_spartan' : template]
}
