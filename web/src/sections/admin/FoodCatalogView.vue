<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { Database } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Progress } from '@/components/ui/progress'
import { api } from '@/core/client'
import { dayIn, momentLabel } from '@/core/dates'
import { errorText } from '@/core/errors'
import { formatAmount, formatBytes } from '@/core/numbers'
import { useLedger, type Operation } from '@/core/operations'
import { useSession } from '@/stores/session'
import {
  ATTEMPTS,
  CATALOG,
  FoodCatalog,
  UNIT_SOURCES,
  releaseDay,
  stagePercent,
  type CatalogJob,
  type CatalogRelease,
} from './foodCatalog'

/**
 * The Open Food Facts products sold in Russia that food search reads: the active release, its
 * refresh from OFF's export (a job on the server, polled while it runs) and earlier releases.
 * System administrators only; the server checks it again.
 */
const { t, te } = useI18n()
const session = useSession()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const catalog = useQuery({
  queryKey: ['catalog', 'openfoodfacts'],
  queryFn: async ({ signal }) => FoodCatalog.parse(await api.get('/v1/nutrition/catalog', signal)),
  // A refresh takes minutes: its stage and progress are read every 2 s until it ends.
  refetchInterval: (query) => (query.state.data?.job?.state === 'running' ? 2000 : false),
})
const ledger = useLedger(CATALOG)
const busy = ref(false)
const choosing = ref<CatalogRelease | null>(null)
const job = computed(() => catalog.data.value?.job ?? null)
const running = computed(() => job.value?.state === 'running')
const percent = computed(() => (job.value ? stagePercent(job.value) : null))
const number = (value: number | null | undefined) => formatAmount(value ?? 0, 0)
const release = (day: string) => t('foodCatalog.release', { date: releaseDay(day) })

function progressText(current: CatalogJob): string[] {
  const parts = stageText(current)
  // A restart (a deploy) starts the refresh again from its download.
  if (current.attempt > 1) {
    parts.unshift(t('foodCatalog.attempt', { attempt: current.attempt, total: ATTEMPTS }))
  }
  return parts
}
function stageText(current: CatalogJob): string[] {
  const { progress } = current
  if (current.stage === 'download') {
    const done = formatBytes(progress.bytes ?? 0)
    return [
      progress.bytes_total
        ? t('foodCatalog.downloaded', { done, total: formatBytes(progress.bytes_total) })
        : t('foodCatalog.downloadedSoFar', { done }),
      t('foodCatalog.lines', { count: number(progress.lines) }),
      t('foodCatalog.kept', { count: number(progress.kept) }),
    ]
  }
  if (current.stage === 'units')
    return [
      t('foodCatalog.unitsAsked', {
        done: number(progress.units?.asked),
        total: number(progress.units?.to_read),
      }),
      t('foodCatalog.unitsRead', { count: number(progress.units?.read) }),
    ]
  return [t('foodCatalog.loaded', { done: number(progress.loaded), total: number(progress.kept) })]
}
const finished = (current: CatalogJob) =>
  momentLabel(current.finished_at ?? current.created_at, zone.value, today.value)
const reason = (code: string) =>
  te(`foodCatalog.error.${code}`)
    ? t(`foodCatalog.error.${code}`)
    : t('foodCatalog.error.internal_error')
/** How many products kept no unit because a model call failed, and the gateway's reason. */
function unread(current: CatalogJob): string | null {
  const units = current.progress.units
  if (!units?.failed) return null
  const why = `assistant.failure.model_${units.error}`
  return [
    t('foodCatalog.unread', { count: number(units.failed) }),
    units.error && te(why) ? t(why) : t('foodCatalog.unreadUnknown'),
  ].join(' ')
}

async function run(operation: Operation, done: string) {
  if (busy.value) return false
  busy.value = true
  try {
    await ledger.operate(operation)
    toast.success(done)
    return true
  } catch (error) {
    toast.error(errorText(error, t))
    return false
  } finally {
    busy.value = false
  }
}
const refresh = () => run({ kind: 'catalog.refresh', payload: {} }, t('foodCatalog.started'))
const cancel = (current: CatalogJob) =>
  run({ kind: 'catalog.cancel', payload: { job_id: current.id } }, t('foodCatalog.cancelled'))
function closeChoice(open: boolean) {
  if (!open) choosing.value = null
}
async function activate() {
  const chosen = choosing.value
  if (!chosen) return
  const payload = { import_version_id: chosen.import_version_id }
  if (await run({ kind: 'catalog.activate', payload }, t('foodCatalog.activated')))
    choosing.value = null
}
</script>

<template>
  <PageHeader :title="t('foodCatalog.title')" :back="{ name: 'admin' }" />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <p class="text-muted-foreground text-sm">{{ t('foodCatalog.text') }}</p>
    <RefreshFailed :query="catalog" />
    <LoadingState v-if="catalog.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="catalog.isLoadingError.value"
      :error="catalog.error.value"
      @retry="catalog.refetch()"
    />
    <template v-else-if="catalog.data.value">
      <section class="space-y-2" aria-labelledby="catalog-active">
        <h2 id="catalog-active" class="font-semibold">{{ t('foodCatalog.active') }}</h2>
        <div
          v-if="catalog.data.value.active"
          class="bg-card space-y-3 rounded-3xl border p-4"
          data-testid="catalog-active"
        >
          <p class="font-medium">{{ release(catalog.data.value.active.release) }}</p>
          <dl class="grid grid-cols-1 gap-x-4 gap-y-1 text-sm sm:grid-cols-2">
            <div class="flex justify-between gap-3">
              <dt class="text-muted-foreground">{{ t('foodCatalog.imported') }}</dt>
              <dd class="font-medium">
                {{ momentLabel(catalog.data.value.active.imported_at, zone, today) }}
              </dd>
            </div>
            <div class="flex justify-between gap-3">
              <dt class="text-muted-foreground">{{ t('foodCatalog.products') }}</dt>
              <dd class="font-medium">{{ number(catalog.data.value.active.products) }}</dd>
            </div>
            <div class="flex justify-between gap-3">
              <dt class="text-muted-foreground">{{ t('foodCatalog.ml') }}</dt>
              <dd class="font-medium">{{ number(catalog.data.value.active.ml) }}</dd>
            </div>
            <div class="flex justify-between gap-3">
              <dt class="text-muted-foreground">{{ t('foodCatalog.withPackage') }}</dt>
              <dd class="font-medium">{{ number(catalog.data.value.active.with_package) }}</dd>
            </div>
          </dl>
          <div class="space-y-1">
            <h3 class="text-sm font-medium">{{ t('foodCatalog.units') }}</h3>
            <dl class="grid grid-cols-1 gap-x-4 gap-y-1 text-sm sm:grid-cols-2">
              <div v-for="source in UNIT_SOURCES" :key="source" class="flex justify-between gap-3">
                <dt class="text-muted-foreground">{{ t(`foodCatalog.unitSource.${source}`) }}</dt>
                <dd class="font-medium">{{ number(catalog.data.value.active.units[source]) }}</dd>
              </div>
            </dl>
          </div>
        </div>
        <EmptyState v-else :icon="Database" :title="t('foodCatalog.empty')" />
      </section>

      <section class="space-y-2" aria-labelledby="catalog-refresh">
        <h2 id="catalog-refresh" class="font-semibold">{{ t('foodCatalog.update') }}</h2>
        <div class="bg-card space-y-3 rounded-3xl border p-4" data-testid="catalog-job">
          <template v-if="job && running">
            <p class="font-medium">{{ t(`foodCatalog.stage.${job.stage}`) }}</p>
            <Progress
              v-if="percent !== null"
              :model-value="percent"
              :aria-label="t(`foodCatalog.stage.${job.stage}`)"
            />
            <p class="text-muted-foreground text-sm">{{ progressText(job).join(' · ') }}</p>
            <Button variant="outline" :disabled="busy" @click="cancel(job)">{{
              t('foodCatalog.cancel')
            }}</Button>
          </template>
          <template v-else>
            <p v-if="job" class="text-sm" role="status">
              <span class="font-medium">{{ t(`foodCatalog.outcome.${job.state}`) }}</span
              ><template v-if="job.state === 'failed' && job.error"
                >: {{ reason(job.error) }}</template
              >
              <span class="text-muted-foreground"> · {{ finished(job) }}</span>
            </p>
            <p v-if="job && unread(job)" class="text-muted-foreground text-sm">{{ unread(job) }}</p>
            <Button :disabled="busy" @click="refresh">{{ t('foodCatalog.refresh') }}</Button>
          </template>
        </div>
      </section>

      <section
        v-if="catalog.data.value.releases.length"
        class="space-y-2"
        aria-labelledby="catalog-releases"
      >
        <h2 id="catalog-releases" class="font-semibold">{{ t('foodCatalog.earlier') }}</h2>
        <ul class="bg-card divide-y rounded-3xl border" data-testid="catalog-releases">
          <li
            v-for="earlier in catalog.data.value.releases"
            :key="earlier.import_version_id"
            class="flex flex-wrap items-center gap-3 px-4 py-3"
          >
            <span class="min-w-0 flex-1">
              <span class="block font-medium">{{ release(earlier.release) }}</span>
              <span class="text-muted-foreground block text-sm">{{
                t('foodCatalog.productCount', { count: number(earlier.products) })
              }}</span>
            </span>
            <Button size="sm" variant="outline" :disabled="busy" @click="choosing = earlier">{{
              t('foodCatalog.activate')
            }}</Button>
          </li>
        </ul>
      </section>
    </template>
  </div>

  <Dialog :open="choosing !== null" @update:open="closeChoice">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{
          t('foodCatalog.activateTitle', { date: choosing ? releaseDay(choosing.release) : '' })
        }}</DialogTitle>
        <DialogDescription>{{ t('foodCatalog.activateText') }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="choosing = null">{{ t('common.cancel') }}</Button>
        <Button :disabled="busy" @click="activate">{{ t('foodCatalog.activate') }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
