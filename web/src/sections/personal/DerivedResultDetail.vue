<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import historyMessages from '@/locales/personal-history-ru.json'
import { useQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { api } from '@/core/client'
import { useSession } from '@/stores/session'
import { Button } from '@/components/ui/button'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import DerivedScoreDetails from './DerivedScoreDetails.vue'
import { DerivedResultVersion } from './derivedScores'

const { t } = useI18n({
  useScope: 'local',
  messages: { ru: historyMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const props = defineProps<{
  resultId: string
  sourceId: string | null
  definitionId: string
  formulaVersion: string
  date: string
  timezone: string
  today: string
}>()
const session = useSession()
const owner = computed(() => session.me?.id ?? null)
const permitted = computed(() => !!owner.value && session.can('personal.read'))
const open = ref(false)
function immutableDetails(saved: DerivedResultVersion) {
  // The latest-version flag belongs to refreshed summaries and archives, not this capture.
  const { is_current, ...captured } = saved
  void is_current
  return captured
}
type ImmutableResultDetail = ReturnType<typeof immutableDetails>
const matchesContext = (saved: ImmutableResultDetail) =>
  saved.result_id === props.resultId &&
  saved.source_scope === props.sourceId &&
  saved.definition_id === props.definitionId &&
  saved.formula_version === props.formulaVersion &&
  saved.date === props.date &&
  saved.time_zone === props.timezone
watch(
  () => [
    owner.value,
    permitted.value,
    props.resultId,
    props.sourceId,
    props.definitionId,
    props.formulaVersion,
    props.date,
    props.timezone,
  ],
  () => {
    open.value = false
  },
)
const query = useQuery({
  enabled: computed(() => permitted.value && open.value),
  queryKey: computed(() => ['personal', 'derived-result', owner.value, props.resultId]),
  staleTime: 'static',
  queryFn: async ({ signal }) => {
    const user = owner.value
    const id = props.resultId
    const source = props.sourceId
    const definition = props.definitionId
    const formula = props.formulaVersion
    const date = props.date
    const zone = props.timezone
    const saved = DerivedResultVersion.parse(
      await api.get(`/v1/personal/derived-result-versions/${encodeURIComponent(id)}`, signal),
    )
    if (
      !permitted.value ||
      user !== owner.value ||
      id !== props.resultId ||
      source !== props.sourceId ||
      definition !== props.definitionId ||
      formula !== props.formulaVersion ||
      date !== props.date ||
      zone !== props.timezone ||
      saved.result_id !== id ||
      saved.source_scope !== source ||
      saved.definition_id !== definition ||
      saved.formula_version !== formula ||
      saved.date !== date ||
      saved.time_zone !== zone
    )
      throw new DOMException('Stored result context changed', 'AbortError')
    return immutableDetails(saved)
  },
})
const saved = computed(() =>
  permitted.value && query.data.value && matchesContext(query.data.value) ? query.data.value : null,
)
</script>

<template>
  <div v-if="permitted" class="min-w-0 space-y-2" data-testid="derived-result-detail">
    <Button size="sm" variant="outline" :aria-expanded="open" @click="open = !open">{{
      open ? t('personalHistory.derived.hideSaved') : t('personalHistory.derived.openSaved')
    }}</Button>
    <div v-if="open" class="space-y-2">
      <RefreshFailed :query="query" />
      <LoadingState v-if="query.isPending.value" :rows="2" />
      <ErrorState
        v-else-if="query.isLoadingError.value"
        :error="query.error.value"
        @retry="query.refetch()"
      />
      <template v-else-if="saved">
        <p class="text-muted-foreground break-all text-xs">
          {{
            t('personalHistory.derived.immutableResult', {
              version: saved.version,
              result_id: saved.result_id,
            })
          }}
        </p>
        <DerivedScoreDetails
          :score="saved.score"
          :definition="saved.definition_snapshot"
          :timezone="timezone"
          :today="today"
        />
        <details class="text-muted-foreground text-xs">
          <summary class="min-h-11 cursor-pointer py-2">
            {{ t('personalHistory.derived.savedEvidence') }}
          </summary>
          <pre class="max-h-96 overflow-auto whitespace-pre-wrap break-all">{{
            JSON.stringify(saved, null, 2)
          }}</pre>
        </details>
      </template>
    </div>
  </div>
</template>
