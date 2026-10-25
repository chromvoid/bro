<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import historyMessages from '@/locales/personal-history-ru.json'
import { useQuery } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { api } from '@/core/client'
import { isIsoDay } from '@/core/dates'
import { useSession } from '@/stores/session'
import { Button } from '@/components/ui/button'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import { DerivedBackfillReceipt, DerivedBackfillRun } from './derivedScores'
import { usePersonal } from './usePersonal'

const { t } = useI18n({
  useScope: 'local',
  messages: { ru: historyMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const props = defineProps<{ from: string; to: string; timezone: string; pending: boolean }>()
const emit = defineEmits<{ queued: []; completed: [] }>()
const session = useSession()
const personal = usePersonal()
const owner = computed(() => session.me?.id ?? null)
const canRead = computed(() => !!owner.value && session.can('personal.read'))
const canWrite = computed(() => !!owner.value && session.can('personal.write'))
const busy = ref(false)
const activeRun = ref<string | null>(null)
const runQueued = ref(false)
const submittedDays = ref<number | null>(null)
const days = computed(() =>
  isIsoDay(props.from) && isIsoDay(props.to)
    ? Math.round((Date.parse(props.to) - Date.parse(props.from)) / 86400000) + 1
    : 0,
)
watch(
  () => [owner.value, canRead.value, props.from, props.to, props.timezone],
  () => {
    busy.value = false
    activeRun.value = null
    submittedDays.value = null
    runQueued.value = false
  },
)
const run = useQuery({
  enabled: computed(() => canRead.value && activeRun.value !== null),
  queryKey: computed(() => ['personal', 'derived-backfill', owner.value, activeRun.value]),
  queryFn: async ({ signal }) => {
    const user = owner.value
    const id = activeRun.value!
    const snapshot = DerivedBackfillRun.parse(
      await api.get(`/v1/personal/derived-backfills/${encodeURIComponent(id)}`, signal),
    )
    if (!canRead.value || user !== owner.value || id !== activeRun.value || snapshot.run_id !== id)
      throw new DOMException('Derivation run scope changed', 'AbortError')
    return snapshot
  },
  refetchInterval: computed(() => (runQueued.value ? 5000 : false)),
  refetchIntervalInBackground: false,
})
watch(
  () => run.data.value?.state,
  (state) => {
    runQueued.value = state === 'queued'
    if (state && state !== 'queued') emit('completed')
  },
)
const unknownOrPending = computed(
  () => !!activeRun.value && (!run.data.value || run.data.value.state === 'queued'),
)
const blocked = computed(
  () =>
    busy.value ||
    props.pending ||
    unknownOrPending.value ||
    !!personal.unresolved.value.length ||
    days.value < 1 ||
    days.value > 366,
)
async function backfill() {
  if (!canWrite.value || blocked.value) return
  const user = owner.value
  const from = props.from
  const to = props.to
  const time_zone = props.timezone
  busy.value = true
  try {
    const receipt = DerivedBackfillReceipt.parse(
      await personal.operate('derived.backfill', { from, to, time_zone }),
    )
    if (
      !canRead.value ||
      user !== owner.value ||
      from !== props.from ||
      to !== props.to ||
      time_zone !== props.timezone
    )
      return
    activeRun.value = receipt.derivation_run_id
    submittedDays.value = receipt.scheduled_days
    runQueued.value = true
    emit('queued')
  } catch (error) {
    personal.failed(error)
  } finally {
    if (
      user === owner.value &&
      from === props.from &&
      to === props.to &&
      time_zone === props.timezone
    )
      busy.value = false
  }
}
const stateLabels = {
  queued: t('personalHistory.backfill.queued'),
  succeeded: t('personalHistory.backfill.succeeded'),
  failed: t('personalHistory.backfill.failed'),
  cancelled: t('personalHistory.backfill.cancelled'),
}
</script>

<template>
  <div class="space-y-2" data-testid="derived-backfill-control">
    <UnresolvedWrites
      :entries="personal.unresolved.value"
      :describe="personal.describe"
      :check="personal.check"
      :resend="personal.resend"
      :abandon="personal.abandon"
    />
    <div v-if="canWrite" class="flex flex-wrap items-center gap-2">
      <Button size="sm" variant="outline" :disabled="blocked" @click="backfill">{{
        t('personalHistory.backfill.start')
      }}</Button>
      <p class="text-muted-foreground text-xs">
        {{ t('personalHistory.backfill.explanation', { from: from, to: to }) }}
      </p>
    </div>
    <p
      v-if="submittedDays !== null && !run.data.value"
      class="text-muted-foreground text-sm"
      role="status"
    >
      {{ t('personalHistory.backfill.submitted', { submittedDays: submittedDays }) }}
    </p>
    <template v-if="activeRun"
      ><RefreshFailed :query="run" /><ErrorState
        v-if="run.isLoadingError.value"
        :error="run.error.value"
        @retry="run.refetch()"
      />
      <div
        v-if="run.data.value"
        class="space-y-1 rounded-xl border p-3 text-sm"
        data-testid="derived-backfill-status"
        role="status"
      >
        <p class="font-medium">{{ stateLabels[run.data.value.state] }}</p>
        <p>
          {{
            t('personalHistory.backfill.progress', {
              processed_days: run.data.value.processed_days,
              scheduled_days: run.data.value.scheduled_days,
              results_saved: run.data.value.results_saved,
              results_unchanged: run.data.value.results_unchanged,
            })
          }}
        </p>
        <p class="text-muted-foreground text-xs">
          {{
            t('personalHistory.backfill.context', {
              from: run.data.value.from,
              to: run.data.value.to,
              time_zone: run.data.value.time_zone,
              as_of: run.data.value.as_of,
            })
          }}
        </p>
        <p v-if="run.data.value.error" class="text-warning">{{ run.data.value.error }}</p>
        <Button size="sm" variant="ghost" :disabled="run.isFetching.value" @click="run.refetch()">{{
          t('personalHistory.backfill.refresh')
        }}</Button>
      </div></template
    >
  </div>
</template>
