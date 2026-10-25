import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { api } from '@/core/client'
import { useSession } from '@/stores/session'
import { BodyPresentation, type BodyCharacterId } from './api'
import { usePersonal } from './usePersonal'

/** Account-owned settings participate in the existing personal.changed invalidation. */
export function useBodyPresentation() {
  const session = useSession()
  const personal = usePersonal()
  const queries = useQueryClient()
  const key = computed(() => ['personal', 'body-presentation', session.me?.id ?? null])
  const query = useQuery({
    queryKey: key,
    enabled: computed(() => !!session.me && session.can('personal.read')),
    queryFn: async ({ signal }) =>
      BodyPresentation.parse(await api.get('/v1/personal/body/presentation', signal)),
  })
  // A read that failed once shows the default figure, which stays while the read is retried: a
  // refetch without data puts the query back to pending and would unmount the figure each time.
  const ready = computed(
    () =>
      !session.me ||
      !!query.data.value ||
      query.isLoadingError.value ||
      query.errorUpdatedAt.value > 0,
  )
  const saving = ref(false)
  const characterId = computed(() => query.data.value?.character_id ?? 'sculpture')
  const writable = computed(
    () => session.can('personal.write') && !!query.data.value && !saving.value,
  )

  async function select(character: BodyCharacterId) {
    const presentation = query.data.value
    if (!presentation || !writable.value || presentation.character_id === character) return
    const owner = session.me?.id
    const cacheKey = [...key.value]
    saving.value = true
    try {
      const receipt = await personal.operate('body.presentation.set', {
        character_id: character,
        revision: presentation.revision,
      })
      const saved = BodyPresentation.parse(
        (receipt as { body_presentation: unknown }).body_presentation,
      )
      // A late receipt belongs to its original account, including when another account is open.
      if (session.me?.id === owner) {
        queries.setQueryData(cacheKey, (current: BodyPresentation | undefined) =>
          !current || saved.revision >= current.revision ? saved : current,
        )
      }
    } catch (error) {
      if (session.me?.id === owner) personal.failed(error)
    } finally {
      saving.value = false
    }
  }

  return { ...query, characterId, writable, ready, saving, select }
}
