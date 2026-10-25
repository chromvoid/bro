/**
 * Read-only sharing of the owner's personal sections. A grant lets one person see a summary or
 * the details of a section; nobody can edit the owner's records or pass access on.
 */
import { z } from '@/core/zod'

export const SECTIONS = ['personal', 'nutrition'] as const
export type SectionName = (typeof SECTIONS)[number]
export const PERMISSIONS = ['summary', 'detail'] as const
export type Permission = (typeof PERMISSIONS)[number]

export const Section = z.looseObject({
  id: z.string(),
  name: z.string(),
  owner_id: z.string(),
  revision: z.number(),
})
export type Section = z.infer<typeof Section>

export const Grant = z.looseObject({
  resource_id: z.string(),
  user_id: z.string(),
  permission: z.string(),
  name: z.string(),
  login: z.string().nullish(),
})
export type Grant = z.infer<typeof Grant>

export const OpenedToMe = z.looseObject({
  id: z.string(),
  name: z.string(),
  owner_id: z.string(),
  permission: z.string(),
  owner_name: z.string(),
  owner_login: z.string().nullish(),
})
export type OpenedToMe = z.infer<typeof OpenedToMe>

export const Sharing = z.looseObject({
  sections: z.array(Section),
  grants: z.array(Grant),
  // Older servers do not list what others opened.
  shared: z.array(OpenedToMe).nullish(),
})
export type Sharing = z.infer<typeof Sharing>

export const Person = z.object({
  user: z.looseObject({ id: z.string(), name: z.string(), login: z.string() }).nullable(),
})

/** The scope a reader needs to open a section someone shared. */
export function readScope(section: string): string {
  return section === 'nutrition' ? 'nutrition.read' : 'personal.read'
}

/** Grants of one section, by name. */
export function grantsOf(sharing: Sharing, section: Section): Grant[] {
  return sharing.grants
    .filter((grant) => grant.resource_id === section.id)
    .sort((a, b) => a.name.localeCompare(b.name, 'ru'))
}
