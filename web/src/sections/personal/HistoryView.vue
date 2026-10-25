<script setup lang="ts">
import historyMessages from '@/locales/personal-history-ru.json'
import { provideDisplayUnits, massValue } from './displayUnits'
import {
  ChevronLeft,
  ChevronRight,
  History as HistoryIcon,
  NotebookText,
  PencilLine,
  Plus,
} from '@lucide/vue'
import { computed, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import LineChart from '@/components/chart/LineChart.vue'
import HeaderAction from '@/components/HeaderAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DropdownMenuItem } from '@/components/ui/dropdown-menu'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { dayIn, dayLabel, isIsoDay, momentLabel, shiftDay } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { useSession } from '@/stores/session'
import {
  KINDS,
  amountText,
  provenance,
  weightPoints,
  type HistoryKind,
  type Kind,
  type Observation,
  type Origin,
  type Profile,
  historyUnit,
} from './api'
import BodyHistory from './BodyHistory.vue'
import DerivedScores from './DerivedScores.vue'
import ActivityReportHistory from '@/sections/activity/ActivityReportHistory.vue'
import RawReportHistory from './RawReportHistory.vue'
import RecordVersionHistory from './RecordVersionHistory.vue'
import {
  ACTIVITY_REPORT_KINDS,
  isActivityReportKind,
  type ActivityReportKind,
} from '@/sections/activity/activityReports'
import CaliperPanel from './CaliperPanel.vue'
import RecordPanel from './RecordPanel.vue'
import ProfilePanel from './ProfilePanel.vue'
import { ageOn } from './profile'
import { useHistory, useOverview, usePersonal } from './usePersonal'
import WeightPanel from './WeightPanel.vue'
import SensorHistory from './SensorHistory.vue'
import { SENSOR_METRICS, isSensorMetric, type SensorMetric } from './sensors'
import DailyResolution from './DailyResolution.vue'
import { sleepInterval } from './sleep'
import { personalReturnLink, personalReturnQuery } from './personalNavigation'

/** P03: exact dates, values and origins; gaps are not smoothed and nothing is concluded. */
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: historyMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const route = useRoute()
const router = useRouter()
const session = useSession()
const personal = usePersonal()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const overview = useOverview(today)
const displayUnits = computed(() => {
  const profile = overview.data.value?.profile
  return profile && profile.user_id === session.me?.id ? profile.display_units : 'metric'
})
provideDisplayUnits(displayUnits)
// A timezone change can select a different day's overview. Keep only the form context,
// so a pending query never destroys an open measurement draft or displays old metrics.
const retainedProfile = shallowRef<Profile>()
watch(
  () => overview.data.value?.profile,
  (profile) => {
    if (profile) retainedProfile.value = profile
  },
  { immediate: true },
)
const profileAgeMissing = computed(
  () =>
    !!overview.data.value?.profile &&
    ageOn(overview.data.value.profile.birth_date, today.value) === null,
)
const profileOpen = ref(false)
const scoreHistory = computed(() => route.query.kind === 'derived_scores')
const PERIODS = [7, 30, 90, 365] as const
const period = computed(() => {
  const asked = Number(route.query.period)
  return (PERIODS as readonly number[]).includes(asked)
    ? asked
    : route.query.kind === 'body'
      ? 365
      : isSensorMetric(route.query.kind)
        ? 7
        : 30
})
const kind = computed<HistoryKind | SensorMetric | ActivityReportKind | null>(() => {
  const asked = route.query.kind
  if (isSensorMetric(asked) || isActivityReportKind(asked)) return asked
  if (asked === 'body') return 'body'
  if (asked === 'all') return null
  return KINDS.includes(asked as Kind) ? (asked as Kind) : 'weight'
})
const end = computed(() =>
  isIsoDay(route.query.end) && route.query.end <= today.value ? route.query.end : today.value,
)
const back = computed(() => personalReturnLink(route.query, today.value))
const caliperJournal = computed(() => ({
  ...back.value,
  query: { ...back.value.query, caliper: '1' },
}))
const profileLink = (field: 'birth' | 'gender') => ({
  name: 'personal-profile',
  query: { ...personalReturnQuery(route.query), field },
})
const range = computed(() => ({
  from: shiftDay(end.value, 1 - period.value),
  to: end.value,
  kind: kind.value,
}))
const activityMetric = computed(() => (isActivityReportKind(kind.value) ? kind.value : null))
const quantityMetric = computed(() =>
  kind.value === 'active_energy' || kind.value === 'distance' ? kind.value : null,
)
const sensorMetric = computed(() => (isSensorMetric(kind.value) ? kind.value : null))
const recordRange = computed(() => ({
  ...range.value,
  kind: sensorMetric.value ? null : (kind.value as HistoryKind | null),
}))
const history = useHistory(
  recordRange,
  computed(() => !sensorMetric.value && !scoreHistory.value && !activityMetric.value),
)
const records = computed(() =>
  [...(history.data.value?.records ?? [])].sort((a, b) => b.start_at.localeCompare(a.start_at)),
)
const displayMode = ref<'result' | 'all'>('result')
const resolvedDays = computed(() =>
  (history.data.value?.daily ?? []).filter(
    (day) => day.resolution && (kind.value === null || day.kind === kind.value),
  ),
)
const supersededReports = computed(
  () =>
    new Set(
      (history.data.value?.daily ?? []).flatMap(
        (day) =>
          day.resolution?.alternatives
            .filter((fact) => fact.reason === 'superseded_report')
            .map((fact) => fact.id) ?? [],
      ),
    ),
)
const displayedRecords = computed(() => {
  if (displayMode.value === 'all' || !resolvedDays.value.length) return records.value
  const chosen = new Set(
    resolvedDays.value.flatMap((day) => day.resolution?.selected.map((fact) => fact.id) ?? []),
  )
  return records.value.filter((record) => chosen.has(record.id))
})
const points = computed(() => (history.data.value ? weightPoints(history.data.value) : []))
// The goal in force on the last day shown: a dashed line on the chart.
const weightTarget = computed(() => {
  const days = history.data.value?.daily ?? []
  const last = days.filter((day) => day.kind === 'weight' && day.target !== null).at(-1)
  return last ? Number(last.target) : null
})
const canWrite = computed(() => session.can('personal.write'))

const record = shallowRef<Observation | null>(null)
const recordOpen = computed({
  get: () => record.value !== null,
  set: (value: boolean) => {
    if (!value) record.value = null
  },
})
const weightOpen = ref(false)
const caliperOpen = ref(false)
const latestWeight = computed(() => records.value.find((item) => item.kind === 'weight') ?? null)

const origin = (value: Origin) => t(`personal.origin.${value}`)
const shortDay = (x: number) => dayLabel(new Date(x).toISOString().slice(0, 10), today.value)
const query = (changes: Record<string, string | number>) => ({
  name: 'personal-history',
  query: { ...route.query, ...changes },
})
const periods = computed(() =>
  PERIODS.map((days) => ({
    value: String(days),
    label: days === 365 ? t('personal.year') : t('personal.days', { count: days }),
    to: query({ period: days }),
  })),
)
function selectKind(value: string | undefined) {
  void router.replace({
    query: {
      ...route.query,
      kind: value,
      period: value === 'body' ? 365 : isSensorMetric(value) ? 7 : undefined,
    },
  })
}
function movePeriod(direction: number) {
  const next = shiftDay(end.value, direction * period.value)
  void router.replace({ query: { ...route.query, end: next >= today.value ? undefined : next } })
}
</script>

<template>
  <PageHeader
    :title="t(kind === 'body' ? 'personal.bodyComposition' : 'personal.historyTitle')"
    :back="back"
    :width="kind === 'body' ? '5xl' : '3xl'"
  >
    <template #actions>
      <HeaderAction
        v-if="kind === 'body' && canWrite"
        primary
        :icon="Plus"
        :label="t('personal.caliper.historyAdd')"
        :disabled="!overview.data.value?.profile"
        @click="caliperOpen = true"
      />
    </template>
    <template v-if="kind === 'body'" #menu>
      <DropdownMenuItem as-child>
        <RouterLink :to="caliperJournal">
          <NotebookText />{{ t('personal.caliper.journal') }}
        </RouterLink>
      </DropdownMenuItem>
    </template>
  </PageHeader>
  <div
    :class="['mx-auto space-y-5 px-4 pb-8 md:px-8', kind === 'body' ? 'max-w-5xl' : 'max-w-3xl']"
  >
    <template v-if="kind === 'body'">
      <p class="text-muted-foreground text-sm" data-testid="caliper-history-hint">
        {{ t('personal.caliper.profileHistoryHint') }}
      </p>
      <Alert
        v-if="
          overview.data.value?.profile && (profileAgeMissing || !overview.data.value.profile.gender)
        "
        variant="warning"
        data-testid="caliper-history-profile-notice"
      >
        <AlertTitle>{{ t('personal.caliper.profileNotificationTitle') }}</AlertTitle>
        <AlertDescription class="space-y-2">
          <p>{{ t('personal.caliper.missingProfileHint') }}</p>
          <div class="flex flex-wrap gap-x-4 gap-y-2">
            <RouterLink
              v-if="profileAgeMissing"
              :to="profileLink('birth')"
              class="text-primary underline underline-offset-4"
              >{{ t('personal.caliper.profileBirthAction') }}</RouterLink
            >
            <RouterLink
              v-if="!overview.data.value.profile.gender"
              :to="profileLink('gender')"
              class="text-primary underline underline-offset-4"
              >{{ t('personal.caliper.profileGenderAction') }}</RouterLink
            >
          </div>
        </AlertDescription>
      </Alert>
    </template>
    <div class="space-y-3">
      <div class="max-w-sm space-y-1.5">
        <Label for="history-kind">{{ t('personal.kindFilter') }}</Label>
        <NativeSelect
          id="history-kind"
          :model-value="scoreHistory ? 'derived_scores' : (kind ?? 'all')"
          @update:model-value="selectKind"
        >
          <option value="derived_scores">{{ t('personalHistory.scores') }}</option>
          <option value="body">{{ t('personal.bodyComposition') }}</option>
          <option v-for="option in KINDS" :key="option" :value="option">
            {{ t(`personal.metric.${option}`) }}
          </option>
          <option v-for="option in SENSOR_METRICS" :key="option" :value="option">
            {{ t(`ring.metric.${option}`) }}
          </option>
          <option v-for="option in ACTIVITY_REPORT_KINDS" :key="option" :value="option">
            {{
              option === 'running_steps'
                ? t('personalHistory.runningSteps')
                : option === 'workouts'
                  ? t('personalHistory.workoutReports')
                  : t('personalHistory.runningReports')
            }}
          </option>
          <option value="all">{{ t('personal.allKinds') }}</option>
        </NativeSelect>
      </div>
      <SegmentedControl
        :segments="periods"
        :label="t('personal.period')"
        :model-value="String(period)"
      />
      <div class="flex items-center justify-between gap-3">
        <Button
          variant="outline"
          size="icon"
          :aria-label="t('personal.previousPeriod')"
          @click="movePeriod(-1)"
          ><ChevronLeft
        /></Button>
        <p class="text-muted-foreground text-center text-sm tabular-nums">
          {{ dayLabel(range.from, today) }} — {{ dayLabel(range.to, today) }}
        </p>
        <Button
          variant="outline"
          size="icon"
          :disabled="end === today"
          :aria-label="t('personal.nextPeriod')"
          @click="movePeriod(1)"
          ><ChevronRight
        /></Button>
      </div>
    </div>

    <DerivedScores
      v-if="scoreHistory"
      :from="range.from"
      :to="range.to"
      :timezone="zone"
      :today="today"
    />
    <ActivityReportHistory
      v-else-if="activityMetric"
      :kind="activityMetric"
      :from="range.from"
      :to="range.to"
      :timezone="zone"
      :today="today"
    />
    <SensorHistory
      v-else-if="sensorMetric"
      :metric="sensorMetric"
      :from="range.from"
      :to="range.to"
      :timezone="zone"
      :today="today"
    />
    <template v-else>
      <UnresolvedWrites
        :entries="personal.unresolved.value"
        :describe="personal.describe"
        :check="personal.check"
        :resend="personal.resend"
        :abandon="personal.abandon"
      />

      <RefreshFailed :query="history" />
      <LoadingState v-if="history.isPending.value" :rows="4" />
      <ErrorState
        v-else-if="history.isLoadingError.value"
        :error="history.error.value"
        @retry="history.refetch()"
      />
      <template v-else>
        <template v-if="kind === 'body' && history.data.value">
          <template v-if="!overview.data.value?.profile">
            <ErrorState
              v-if="overview.isLoadingError.value"
              :title="t('personal.atlas.profileLoadFailed')"
              :error="overview.error.value"
              @retry="overview.refetch()"
            />
            <p v-else class="text-muted-foreground text-sm" role="status">
              {{ t('personal.atlas.profileLoading') }}
            </p>
          </template>
          <BodyHistory
            :history="history.data.value"
            :today="today"
            :timezone="zone"
            :can-write="canWrite"
            :profile="overview.data.value?.profile"
            @edit="record = $event"
            @profile="profileOpen = true"
          />
        </template>
        <template v-else>
          <div
            v-if="resolvedDays.length"
            class="flex flex-wrap gap-2"
            role="group"
            :aria-label="t('resolution.mode')"
          >
            <Button
              variant="outline"
              :aria-pressed="displayMode === 'result'"
              @click="displayMode = 'result'"
              >{{ t('resolution.result') }}</Button
            >
            <Button
              variant="outline"
              :aria-pressed="displayMode === 'all'"
              @click="displayMode = 'all'"
              >{{ t('resolution.allSources') }}</Button
            >
          </div>
          <template v-if="displayMode === 'result'">
            <DailyResolution
              v-for="day in resolvedDays"
              :key="`${day.date}-${day.kind}`"
              :date="day.date"
              :kind="day.kind"
              :value="day.value"
              :unit="historyUnit(day.kind, day.unit)"
              :resolution="day.resolution!"
              :timezone="zone"
              :today="today"
            />
          </template>
          <section
            v-if="kind === 'weight' && points.length > 1"
            class="bg-card rounded-3xl border p-5"
          >
            <LineChart
              :points="points"
              :label="t('personal.weightChart', { count: period })"
              :format-x="shortDay"
              :format-y="(value) => amountText('weight', String(value), displayUnits) ?? '—'"
              :format-tick="(value) => formatAmount(massValue(value, displayUnits), 1)"
              :target="weightTarget"
              :target-label="t('personal.targetLine')"
              trend
              :gap-days="7"
            />
          </section>

          <div v-if="canWrite && (kind === 'weight' || kind === null)" class="flex flex-wrap gap-2">
            <Button @click="weightOpen = true"
              ><Plus />{{ t('personal.newWeighing') }} · {{ dayLabel(today, today) }}</Button
            >
            <Button v-if="latestWeight" variant="outline" @click="record = latestWeight">
              <PencilLine />{{ t('personal.correctLatest') }}
            </Button>
          </div>

          <EmptyState
            v-if="!displayedRecords.length && !resolvedDays.length"
            :icon="HistoryIcon"
            :title="t('personal.historyEmpty')"
          />
          <ul
            v-else-if="displayedRecords.length"
            class="bg-card divide-y rounded-3xl border"
            data-testid="history"
          >
            <li v-for="item in displayedRecords" :key="item.id">
              <button
                type="button"
                class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3 text-left disabled:cursor-default"
                @click="record = item"
              >
                <span class="min-w-0 flex-1">
                  <span class="flex flex-wrap items-center gap-2">
                    <span
                      :class="[
                        'font-medium tabular-nums',
                        item.excluded && 'text-muted-foreground line-through',
                      ]"
                    >
                      {{
                        amountText(item.kind, item.data.value, displayUnits) ??
                        (item.kind === 'sleep'
                          ? sleepInterval(item.start_at, item.end_at, zone, today)
                          : t('personal.noValue'))
                      }}
                    </span>
                    <span v-if="kind === null" class="text-muted-foreground text-sm">{{
                      t(`personal.metric.${item.kind}`)
                    }}</span>
                    <Badge v-if="item.excluded" variant="outline">{{
                      t('personal.excluded')
                    }}</Badge>
                    <Badge v-if="supersededReports.has(item.id)" variant="outline">{{
                      t('resolution.reason.superseded_report')
                    }}</Badge>
                  </span>
                  <span
                    v-if="item.kind === 'sleep' && item.data.value === null"
                    class="text-muted-foreground block text-sm"
                    >{{ t('personal.periodOnly') }}</span
                  >
                  <span class="text-muted-foreground block text-sm">
                    {{ momentLabel(item.start_at, zone, today) }} ·
                    {{ provenance(item, origin) }}
                    <template v-if="item.corrected && item.original">
                      ·
                      {{
                        t('personal.was', {
                          value:
                            amountText(item.kind, item.original.data.value, displayUnits) ??
                            t('personal.noValue'),
                        })
                      }}</template
                    >
                  </span>
                  <span
                    v-if="item.quality?.includes('protocol_unverified')"
                    class="text-warning block text-sm"
                    >{{ t('ring.protocolUnverified') }}</span
                  >
                </span>
              </button>
              <RecordVersionHistory
                v-if="item.kind === 'steps' || item.kind === 'sleep'"
                family="record"
                :record-id="item.id"
                :source-id="item.source_id ?? null"
                :metric="item.kind"
                :timezone="zone"
                :today="today"
              />
            </li>
          </ul>
        </template>
      </template>
    </template>
    <RawReportHistory
      v-if="
        !scoreHistory &&
        (kind === null || activityMetric === 'workouts' || activityMetric === 'running_workouts')
      "
      metric="workouts"
      :from="range.from"
      :to="range.to"
      :timezone="zone"
      :today="today"
    />
    <ActivityReportHistory
      v-if="(kind === null || quantityMetric) && !scoreHistory"
      :kind="quantityMetric ?? undefined"
      :from="range.from"
      :to="range.to"
      :timezone="zone"
      :today="today"
    />
  </div>
  <RecordPanel v-model:open="recordOpen" :record="record" :today="today" />
  <WeightPanel v-model:open="weightOpen" :day="today" />
  <CaliperPanel
    v-if="kind === 'body' && retainedProfile && canWrite"
    v-model:open="caliperOpen"
    :profile="retainedProfile"
    :day="today"
    :today="today"
  />
  <ProfilePanel
    v-if="overview.data.value && canWrite"
    v-model:open="profileOpen"
    :profile="overview.data.value.profile"
    :today="today"
  />
</template>
