<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ChevronRight } from '@lucide/vue'
import { computed, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { dateLabel, dayIn, dayLabel, momentPhrase } from '@/core/dates'
import { useSession } from '@/stores/session'
import CollectorDetails from '@/sections/devices/CollectorDetails.vue'
import { KINDS, type Kind } from './api'
import DdxCard from './DdxCard.vue'
import SourceLinks from './SourceLinks.vue'
import { Rings } from './sensors'
import {
  dayAmount,
  deviceDetail,
  deviceTitle,
  latestDay,
  roleOf,
  rowItems,
  shortNames,
  SOURCE_ROWS,
  useSourceDay,
  useSources,
  useSourcesDay,
  type DeviceKind,
  type SourceEntry,
  type SourceRowSpec,
} from './sources'
import { provideDisplayUnits } from './displayUnits'
import { useOverview, usePersonal } from './usePersonal'

/**
 * One device or service: its saved state, what it measures and whether today's values
 * came from it, its technical details and switching it off. The same page connects a new one.
 */
const SETUP = ['ddx', 'custom'] as const
type Setup = (typeof SETUP)[number]

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const personal = usePersonal()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const canWrite = computed(() => session.can('personal.write'))
const sources = useSources()
// Values below show the owner's display units.
const overview = useOverview(today)
const units = computed(() => {
  const profile = overview.data.value?.profile
  return profile && profile.user_id === session.me?.id ? profile.display_units : 'metric'
})
provideDisplayUnits(units)
const rings = useQuery({
  queryKey: ['personal', 'rings'],
  queryFn: async ({ signal }) => Rings.parse(await api.get('/v1/personal/rings', signal)),
})

const param = computed(() => String(route.params.source ?? ''))
const setup = computed<Setup | null>(() =>
  (SETUP as readonly string[]).includes(param.value) ? (param.value as Setup) : null,
)
const list = computed(() => sources.data.value?.sources ?? [])
const source = computed<SourceEntry | null>(
  () => list.value.find((item) => item.id === param.value) ?? null,
)
const kind = computed<DeviceKind | null>(() => source.value?.device.kind ?? setup.value)
const title = computed(() =>
  source.value
    ? deviceTitle(t, source.value)
    : setup.value
      ? t(`sources.device.${setup.value}`)
      : '',
)
const collector = computed(
  () => rings.data.value?.rings.find((item) => item.source_id === source.value?.id) ?? null,
)
const names = computed(() =>
  shortNames(t, list.value, (item) =>
    dateLabel(dayIn(zone.value, new Date(item.created_at!)), today.value),
  ),
)
const battery = computed(() => collector.value?.device_state?.battery_percent ?? null)
const status = computed(() => {
  const current = source.value
  if (!current) return null
  return [
    battery.value !== null ? t('sources.battery', { value: battery.value }) : null,
    current.last_received_at
      ? t('sources.dataAt', {
          when: momentPhrase(current.last_received_at, zone.value, today.value),
        })
      : t('sources.noData'),
  ]
    .filter(Boolean)
    .join(' · ')
})

// What it measures, its values on its latest day with data and its part in that day.
const rows = computed(() =>
  source.value
    ? SOURCE_ROWS.filter((row) =>
        row.metrics.some((metric) => source.value!.metric_keys.includes(metric)),
      )
    : [],
)
const shownDay = computed(() => latestDay(source.value, zone.value, today.value))
const day = useSourcesDay(shownDay)
const own = useSourceDay(
  computed(() => source.value?.id ?? null),
  shownDay,
)
// A row of several metrics (workouts, runs, running steps) names none of them: its line shows
// the first one with something counted, not «0 шагов» under «Тренировки».
const value = (row: SourceRowSpec) => {
  const items = rowItems(row, own.data.value?.daily ?? [], shownDay.value)
  const item = row.metrics.length > 1 ? items.find((entry) => Number(entry.value) > 0) : items[0]
  return item ? dayAmount((key, count) => t(key, count), item, units.value) : null
}
const role = (row: SourceRowSpec) =>
  source.value ? roleOf(source.value.id, row, day.data.value, shownDay.value) : null
const dataLink = (row: SourceRowSpec) => ({
  name: 'personal-source-data',
  params: { source: source.value!.id, row: row.key },
  query: shownDay.value === today.value ? {} : { day: shownDay.value },
})

// A source registered by the person: switched on and off here, its id for the importer.
async function switchSource() {
  const current = source.value
  if (!current) return
  const status = current.status === 'disabled' ? 'connected' : 'disabled'
  try {
    await personal.operate(
      'source.update',
      { source_id: current.id, status },
      { [current.id]: current.revision },
    )
    toast.success(t(status === 'disabled' ? 'sources.turnedOff' : 'sources.turnedOn'))
  } catch (error) {
    personal.failed(error)
  }
}
const form = reactive({ name: '', kinds: ['sleep'] as Kind[], busy: false, error: '' })
function toggleKind(item: Kind, checked: boolean | 'indeterminate') {
  form.kinds =
    checked === true
      ? [...new Set([...form.kinds, item])]
      : form.kinds.filter((value) => value !== item)
}
async function register() {
  const name = form.name.trim()
  form.error = !name
    ? t('personal.sourceNameRequired')
    : !form.kinds.length
      ? t('personal.kindsRequired')
      : ''
  if (form.error || form.busy) return
  form.busy = true
  try {
    const receipt = (await personal.operate('source.create', { name, kinds: form.kinds })) as {
      source?: { id?: string }
    }
    toast.success(t('personal.registered', { name }))
    if (receipt?.source?.id)
      await router.replace({ name: 'personal-source', params: { source: receipt.source.id } })
  } catch (error) {
    personal.failed(error)
  } finally {
    form.busy = false
  }
}
</script>

<template>
  <PageHeader :title="title || t('sources.title')" :back="{ name: 'personal-sources' }" />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <UnresolvedWrites
      :entries="personal.unresolved.value"
      :describe="personal.describe"
      :check="personal.check"
      :resend="personal.resend"
      :abandon="personal.abandon"
    />
    <RefreshFailed :query="sources" />
    <LoadingState v-if="sources.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="sources.isLoadingError.value"
      :error="sources.error.value"
      @retry="sources.refetch()"
    />
    <EmptyState v-else-if="!source && !setup" :title="t('sources.notFound')" />
    <template v-else>
      <div v-if="source" class="space-y-1" data-testid="source-summary">
        <p v-if="deviceDetail(source)" class="text-muted-foreground text-sm">
          {{ deviceDetail(source) }}
        </p>
        <p class="text-sm">
          <Badge
            v-if="source.status === 'disabled' || source.device.collector_enabled === false"
            variant="secondary"
            >{{ t('sources.off') }}</Badge
          >
          <span v-else :class="source.status === 'unavailable' ? 'text-warning' : ''">{{
            source.status === 'unavailable' ? t('personal.sourceState.unavailable') : status
          }}</span>
        </p>
      </div>

      <DdxCard
        v-if="kind === 'ddx'"
        :key="session.me?.id"
        :sources="list"
        :today="today"
        :timezone="zone"
        :can-write="canWrite"
      />

      <section
        v-if="setup === 'custom' && canWrite"
        class="bg-card space-y-4 rounded-3xl border p-5"
        aria-labelledby="register-title"
      >
        <h2 id="register-title" class="font-semibold">{{ t('sources.custom') }}</h2>
        <form class="space-y-4" novalidate @submit.prevent="register">
          <div class="space-y-2">
            <Label for="source-name">{{ t('personal.sourceName') }}</Label>
            <Input
              id="source-name"
              v-model="form.name"
              maxlength="100"
              :placeholder="t('personal.sourceExample')"
            />
          </div>
          <fieldset class="space-y-2">
            <legend class="mb-2 text-sm font-medium">{{ t('personal.sourceKinds') }}</legend>
            <div class="grid grid-cols-2 gap-2">
              <Label v-for="item in KINDS" :key="item" class="flex items-center gap-2 font-normal">
                <Checkbox
                  :model-value="form.kinds.includes(item)"
                  @update:model-value="(checked) => toggleKind(item, checked)"
                />{{ t(`personal.metric.${item}`) }}
              </Label>
            </div>
          </fieldset>
          <p v-if="form.error" class="text-destructive text-sm" role="alert">{{ form.error }}</p>
          <Button type="submit" variant="outline" :disabled="form.busy">{{
            t('personal.registerButton')
          }}</Button>
        </form>
      </section>

      <section v-if="source && rows.length" class="space-y-2" aria-labelledby="source-measures">
        <div class="flex items-baseline justify-between gap-3">
          <h2 id="source-measures" class="font-semibold">{{ t('sources.data') }}</h2>
          <span class="text-muted-foreground text-sm" data-testid="source-measures-day">{{
            dayLabel(shownDay, today)
          }}</span>
        </div>
        <ul class="bg-card divide-y rounded-3xl border" data-testid="source-measures">
          <li v-for="row in rows" :key="row.key">
            <RouterLink
              :to="dataLink(row)"
              class="flex min-h-14 w-full items-center gap-3 px-4 py-3 text-left"
              :data-testid="`source-measure-${row.key}`"
            >
              <!-- The value under the name: beside the role it squeezed the name mid-word. -->
              <span class="min-w-0 flex-1">
                <span class="block font-medium">{{ t(`sources.row.${row.key}`) }}</span>
                <span
                  v-if="value(row)"
                  class="text-muted-foreground block text-sm tabular-nums"
                  :data-testid="`source-value-${row.key}`"
                  >{{ value(row) }}</span
                >
              </span>
              <Badge
                v-if="role(row)"
                :variant="role(row) === 'primary' ? 'default' : 'secondary'"
                >{{ t(`sources.role.${role(row)}`) }}</Badge
              >
              <ChevronRight class="text-muted-foreground size-4 shrink-0" aria-hidden="true" />
            </RouterLink>
          </li>
        </ul>
      </section>

      <details v-if="source" class="space-y-3" data-testid="source-details">
        <summary class="text-primary min-h-11 cursor-pointer py-2">
          {{ t('sources.details') }}
        </summary>
        <p v-if="source.created_at" class="text-muted-foreground text-sm">
          {{
            t('sources.connectedAt', {
              date: dateLabel(dayIn(zone, new Date(source.created_at)), today),
            })
          }}
        </p>
        <CollectorDetails v-if="collector" :collector="collector" />
        <SourceLinks
          :source="source"
          :sources="list"
          :identities="day.data.value?.source_identities ?? {}"
          :names="names"
          :can-write="canWrite"
        />
        <div v-if="kind === 'custom'" class="space-y-1 text-sm">
          <p class="text-muted-foreground">{{ t('sources.setupId') }}</p>
          <code class="bg-muted block rounded-lg px-2 py-1 break-all select-all">{{
            source.id
          }}</code>
        </div>
      </details>

      <Button
        v-if="source && kind === 'custom' && canWrite"
        variant="ghost"
        @click="switchSource"
        >{{ source.status === 'disabled' ? t('sources.turnOn') : t('sources.turnOff') }}</Button
      >
    </template>
  </div>
</template>
