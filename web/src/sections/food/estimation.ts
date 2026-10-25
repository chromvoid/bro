/** Present both legacy portion notes and versioned source assumptions as readable text. */
export function estimationDetails(value: string | null | undefined): {
  note: string
  sourceEstimated: boolean
} {
  const legacy = { note: value ?? '', sourceEstimated: false }
  if (!value?.startsWith('{')) return legacy
  try {
    const data: unknown = JSON.parse(value)
    if (
      typeof data !== 'object' ||
      data === null ||
      !('kind' in data) ||
      data.kind !== 'nutrition-assumptions/v1' ||
      !('composition' in data) ||
      typeof data.composition !== 'object' ||
      data.composition === null ||
      Array.isArray(data.composition)
    )
      return legacy
    return {
      note: 'note' in data && typeof data.note === 'string' ? data.note : '',
      sourceEstimated: true,
    }
  } catch {
    return legacy
  }
}
