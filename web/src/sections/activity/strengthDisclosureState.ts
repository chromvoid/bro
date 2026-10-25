/** Reading state only, kept for returning from a session. No workout values are retained. */
const contexts = new Map<string, Set<string>>()
const MAX_CONTEXTS = 32
const MAX_DISCLOSURES = 64

export function strengthDisclosureOpen(context: string, disclosure: string): boolean {
  return contexts.get(context)?.has(disclosure) ?? false
}

export function rememberStrengthDisclosure(context: string, disclosure: string, open: boolean) {
  const entries = contexts.get(context) ?? new Set<string>()
  if (open) {
    entries.delete(disclosure)
    entries.add(disclosure)
  } else entries.delete(disclosure)
  if (!entries.size) {
    contexts.delete(context)
    return
  }
  contexts.delete(context)
  contexts.set(context, entries)
  while (entries.size > MAX_DISCLOSURES) entries.delete(entries.values().next().value!)
  while (contexts.size > MAX_CONTEXTS) contexts.delete(contexts.keys().next().value!)
}
