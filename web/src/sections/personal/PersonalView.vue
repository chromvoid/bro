<script setup lang="ts">
import { provideDisplayUnits } from './displayUnits'
import SleepAnalysisSummary from './SleepAnalysisSummary.vue'
import DerivedScores from './DerivedScores.vue'
import SensorDaySummary from './SensorDaySummary.vue'
import {
  ChevronRight,
  Goal,
  History,
  MoreHorizontal,
  Plug,
  Plus,
  Ruler,
  UserRound,
} from '@lucide/vue'
import { useElementSize, useMediaQuery } from '@vueuse/core'
import { useIsFetching } from '@tanstack/vue-query'
import {
  computed,
  nextTick,
  onScopeDispose,
  ref,
  shallowRef,
  useTemplateRef,
  watch,
  watchEffect,
} from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import DayNav from '@/components/DayNav.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { dayIn, isIsoDay, momentLabel, momentPhrase } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { useQueryIntent } from '@/core/intent'
import messages from '@/locales/dashboard-ru.json'
import { useSession } from '@/stores/session'
import { amountText, type Kind, type Metric, type Observation, type Profile } from './api'
import { useCaliperHistory } from './caliper'
import CaliperCard from './CaliperCard.vue'
import CaliperPanel from './CaliperPanel.vue'
import DashboardBody from './DashboardBody.vue'
import DashboardNutrition from './DashboardNutrition.vue'
import DashboardWeight from './DashboardWeight.vue'
import MetricDetails from './MetricDetails.vue'
import RecordPanel from './RecordPanel.vue'
import {
  capturePersonalReturn,
  personalHistoryLink,
  personalScreenLink,
  usePersonalScrollRestore,
} from './personalNavigation'
import ProfilePanel from './ProfilePanel.vue'
import { useOverview, usePersonal } from './usePersonal'
import WeightPanel from './WeightPanel.vue'
import { metricSleepSessions, sleepConflictKey, sleepInterval, sleepReportConflict } from './sleep'

const { t } = useI18n({
  useScope: 'local',
  messages: { ru: messages },
  missingWarn: false,
  fallbackWarn: false,
})
const route = useRoute()
const router = useRouter()
const session = useSession()
const personal = usePersonal()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const day = computed(() =>
  isIsoDay(route.query.day) && route.query.day <= today.value ? route.query.day : today.value,
)
const overview = useOverview(day)
const displayUnits = computed(() => {
  const profile = overview.data.value?.profile
  return profile && profile.user_id === session.me?.id ? profile.display_units : 'metric'
})
provideDisplayUnits(displayUnits)
const data = computed(() => overview.data.value)
const mobile = useMediaQuery('(max-width: 767px)')
const moreOpen = ref(false)
watch(mobile, () => (moreOpen.value = false))
const dateElement = useTemplateRef<HTMLElement>('dateElement')
const { height: dateHeight } = useElementSize(dateElement, undefined, { box: 'border-box' })
watchEffect(() => {
  if (mobile.value && dateHeight.value > 0)
    document.documentElement.style.setProperty('--page-sticky-top', `${dateHeight.value}px`)
  else document.documentElement.style.removeProperty('--page-sticky-top')
})
onScopeDispose(() => document.documentElement.style.removeProperty('--page-sticky-top'))
const fetching = useIsFetching({
  predicate: (query) =>
    query.isActive() && ['personal', 'nutrition'].includes(String(query.queryKey[0])),
})
const ready = computed(() => !overview.isPending.value && fetching.value === 0)
usePersonalScrollRestore(route, ready)
const allHistoryLink = computed(() => personalHistoryLink(day.value, 'weight', 'all'))
const sourcesLink = computed(() => personalScreenLink('personal-sources', day.value, 'daily'))
const goalsLink = computed(() => personalScreenLink('personal-goals', day.value, 'daily'))
const weightHistoryLinks = computed(() =>
  Object.fromEntries(
    [7, 30, 90, 365].map((period) => [
      period,
      personalHistoryLink(day.value, 'weight', 'weight', period),
    ]),
  ),
)
const weightHistoryLink = (period: number) => weightHistoryLinks.value[period]!
const sleepSessions = computed(() =>
  data.value ? metricSleepSessions(data.value.metrics.sleep) : [],
)
const sleepConflict = computed(() => sleepReportConflict(data.value?.metrics.sleep.resolution))
const canWrite = computed(() => session.can('personal.write'))
// Retain the profile while changing timezone/day so an open form does not lose its draft.
const profile = shallowRef<Profile | null>(null)
watch(
  data,
  (value) => {
    if (value) profile.value = value.profile
  },
  { immediate: true },
)
const scoreOpen = ref(false)
const weightOpen = ref(false)
const profileOpen = ref(false)
const caliperOpen = ref(false)
const caliperHistoryOpen = ref(false)
useQueryIntent('weight', () => (weightOpen.value = canWrite.value))
// «Журнал замеров» on body history opens the caliper records, not just this page.
useQueryIntent('caliper', () => (caliperHistoryOpen.value = true))
const detailKind = ref<Kind | null>(null)
const detail = computed(() =>
  detailKind.value ? (data.value?.metrics[detailKind.value] ?? null) : null,
)
const detailHistoryLink = computed(() =>
  personalHistoryLink(day.value, 'daily', detailKind.value ?? 'all'),
)
const detailOpen = computed({
  get: () => detailKind.value !== null,
  set: (value: boolean) => {
    if (!value) detailKind.value = null
  },
})
const record = shallowRef<Observation | null>(null)
const returnDetailKind = ref<Kind | null>(null)
function editRecord(observation: Observation) {
  returnDetailKind.value = detailKind.value
  detailKind.value = null
  record.value = observation
}
watch(record, async (current, previous) => {
  if (current || !previous || !returnDetailKind.value) return
  detailKind.value = returnDetailKind.value
  returnDetailKind.value = null
  await nextTick()
  const target = document.querySelector<HTMLElement>(
    `[data-testid="metric-record-${canWrite.value ? 'correct' : 'detail'}-${CSS.escape(previous.id)}"]`,
  )
  target?.scrollIntoView({ block: 'nearest' })
  target?.focus({ preventScroll: true })
})
const recordOpen = computed({
  get: () => record.value !== null,
  set: (value: boolean) => {
    if (!value) record.value = null
  },
})
// Movement (steps, active minutes and energy, distance) lives in «Активность»; the day here is sleep.
const DAY_KINDS = ['sleep'] as const
const reported = computed(() =>
  data.value
    ? DAY_KINDS.map((kind) => data.value!.metrics[kind]).filter(
        (metric): metric is Metric => !!metric && metric.status !== 'no_data',
      )
    : [],
)
const silent = computed(() => {
  const names = data.value
    ? DAY_KINDS.filter((kind) => data.value!.metrics[kind]?.status === 'no_data')
        .map((kind) => t(`personal.metric.${kind}`).toLowerCase())
        .join(', ')
    : ''
  return names ? names[0]!.toUpperCase() + names.slice(1) : null
})
const caliper = useCaliperHistory(day)
const lastCaliper = computed(
  () =>
    [...(caliper.data.value?.records ?? [])].sort((a, b) =>
      b.measured_at.localeCompare(a.measured_at),
    )[0],
)
const caliperSourceId = computed(() => {
  const latest = lastCaliper.value
  if (
    !mobile.value ||
    !latest?.body_fat_record_id ||
    latest.body_fat_percent === null ||
    caliper.isPending.value ||
    caliper.isLoadingError.value ||
    caliper.isRefetchError.value
  )
    return null
  const represented = data.value?.metrics_by_source?.body_fat_percent?.find(
    (metric) =>
      metric.source_id === latest.source_id &&
      metric.status === 'available' &&
      metric.value !== null &&
      Number(metric.value) === Number(latest.body_fat_percent) &&
      !metric.source_unavailable &&
      metric.records?.some(
        (record) =>
          record.id === latest.body_fat_record_id && !record.excluded && !record.corrected,
      ),
  )
  return represented ? latest.source_id : null
})
const caliperNeedsProfile = computed(
  () =>
    lastCaliper.value?.calculation.status === 'incomplete' &&
    lastCaliper.value.calculation.missing_fields.some(
      (field) => field === 'gender' || field === 'age_years',
    ),
)
const sourceProblems = computed(
  () => data.value?.sources.filter((source) => source.status === 'unavailable') ?? [],
)

function go(next: string) {
  if (!isIsoDay(next) || next > today.value) return
  detailKind.value = null
  void router.replace({ query: { ...route.query, day: next === today.value ? undefined : next } })
}
</script>

<template>
  <PageHeader :title="t('personal.title')" width="6xl">
    <template v-if="mobile" #actions>
      <DropdownMenu v-model:open="moreOpen">
        <DropdownMenuTrigger as-child>
          <Button
            variant="ghost"
            size="icon"
            :aria-label="t('common.more')"
            data-testid="personal-more"
          >
            <MoreHorizontal />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem as-child class="min-h-11">
            <RouterLink :to="allHistoryLink" @click="capturePersonalReturn(allHistoryLink)"
              ><History />{{ t('dashboard.allMeasurements') }}</RouterLink
            >
          </DropdownMenuItem>
          <DropdownMenuItem
            v-if="profile"
            class="min-h-11"
            data-testid="profile-open"
            @select="profileOpen = true"
            ><UserRound />{{ t('personal.bodyProfileTitle') }}</DropdownMenuItem
          >
          <DropdownMenuItem as-child class="min-h-11">
            <RouterLink :to="sourcesLink" @click="capturePersonalReturn(sourcesLink)"
              ><Plug />{{ t('personal.sources')
              }}<span v-if="sourceProblems.length" class="text-warning">
                · {{ sourceProblems.length }}</span
              ></RouterLink
            >
          </DropdownMenuItem>
          <DropdownMenuItem as-child class="min-h-11">
            <RouterLink :to="goalsLink" @click="capturePersonalReturn(goalsLink)"
              ><Goal />{{ t('sources.goals') }}</RouterLink
            >
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </template>
  </PageHeader>
  <div
    class="mx-auto min-w-0 max-w-6xl space-y-4 px-4 pb-8 md:px-8"
    data-testid="personal-dashboard"
  >
    <div
      ref="dateElement"
      class="bg-background sticky top-(--app-top) z-10 -mx-4 px-4 sm:mx-auto sm:max-w-sm sm:px-0 md:static"
      data-testid="personal-day-nav"
    >
      <DayNav :day="day" :today="today" @go="go" />
    </div>
    <UnresolvedWrites
      :entries="personal.unresolved.value"
      :describe="personal.describe"
      :check="personal.check"
      :resend="personal.resend"
      :abandon="personal.abandon"
    />
    <RefreshFailed :query="overview" />
    <LoadingState v-if="overview.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="overview.isLoadingError.value"
      :error="overview.error.value"
      @retry="overview.refetch()"
    />
    <template v-else-if="data">
      <div class="grid min-w-0 gap-4 lg:grid-cols-3 lg:items-start">
        <DashboardWeight
          id="personal-weight"
          class="min-w-0 md:order-1 lg:order-2 lg:col-span-2"
          :day="day"
          :today="today"
          :timezone="zone"
          :weight="data.metrics.weight"
          :can-write="canWrite"
          :history-link="weightHistoryLink"
          :editing-record-id="record?.id ?? null"
          @history="capturePersonalReturn"
          @edit="editRecord"
          @add-weight="weightOpen = canWrite"
        />
        <section
          id="personal-daily"
          class="bg-card min-w-0 space-y-2 rounded-3xl border p-4 md:order-4 md:space-y-3 md:p-5 lg:col-span-3"
          aria-labelledby="metrics-title"
        >
          <h2 id="metrics-title" class="font-semibold">{{ t('personal.dayMetrics') }}</h2>
          <ul v-if="reported.length" class="grid gap-1 md:grid-cols-3 md:gap-2">
            <li v-for="metric in reported" :key="metric.kind">
              <button
                type="button"
                class="hover:bg-accent/60 grid min-h-14 w-full grid-cols-[5rem_minmax(0,1fr)_1rem] items-center gap-x-3 rounded-xl px-2 py-2 text-left md:flex md:min-h-20 md:flex-col md:items-start md:rounded-2xl md:border md:p-3"
                :data-testid="`metric-${metric.kind}`"
                @click="detailKind = metric.kind"
              >
                <span class="text-muted-foreground text-sm">{{
                  t(`personal.metric.${metric.kind}`)
                }}</span>
                <!-- A column: the value, sleep intervals and notes are separate lines. -->
                <span class="flex min-w-0 flex-col items-start gap-1">
                  <SleepAnalysisSummary
                    v-if="metric.kind === 'sleep' && metric.resolution?.sleep_analysis"
                    :analysis="metric.resolution.sleep_analysis"
                    :timezone="zone"
                    :today="today"
                    strip
                  />
                  <span v-else-if="metric.status === 'conflict'" class="text-warning font-medium">{{
                    metric.kind === 'sleep' && sleepConflict
                      ? t(sleepConflictKey(sleepConflict))
                      : t('resolution.status.conflict')
                  }}</span>
                  <template
                    v-else-if="
                      metric.kind === 'sleep' && metric.value === null && sleepSessions.length
                    "
                  >
                    <span
                      v-for="episode in sleepSessions"
                      :key="episode.id"
                      class="font-semibold tabular-nums"
                    >
                      {{ sleepInterval(episode.start_at, episode.end_at, zone, today) }}
                    </span>
                  </template>
                  <span v-else class="text-lg font-semibold tabular-nums md:text-xl">{{
                    amountText(metric.kind, metric.value, displayUnits) || t('personal.noData')
                  }}</span>
                  <span
                    v-if="
                      metric.kind === 'sleep' &&
                      !metric.resolution?.sleep_analysis &&
                      metric.status === 'available' &&
                      metric.value === null
                    "
                    class="text-muted-foreground text-sm"
                  >
                    {{ t('personal.periodOnly') }}
                  </span>
                  <span
                    v-if="metric.source_unavailable || metric.status === 'unavailable'"
                    class="text-warning text-sm"
                    >{{ t('personal.sourceDown') }}</span
                  >
                  <span
                    v-else-if="
                      metric.complete === false &&
                      !(metric.kind === 'sleep' && metric.value === null)
                    "
                    class="text-muted-foreground text-sm"
                    >{{ t('personal.incomplete') }}</span
                  >
                </span>
                <span class="text-primary flex items-center gap-1 text-sm md:mt-1"
                  ><span class="hidden md:inline">{{ t('dashboard.details') }}</span
                  ><ChevronRight class="size-4"
                /></span>
              </button>
            </li>
          </ul>
          <div
            v-if="silent"
            class="text-muted-foreground flex flex-col items-start gap-2 text-sm sm:flex-row sm:items-center sm:justify-between"
            data-testid="metrics-silent"
          >
            <p class="min-w-0 sm:flex-1">{{ silent }} · {{ t('personal.noData') }}</p>
            <Button variant="ghost" as-child
              ><RouterLink :to="sourcesLink" @click="capturePersonalReturn(sourcesLink)">{{
                t('personal.seeSources')
              }}</RouterLink></Button
            >
          </div>
        </section>
        <DashboardBody
          id="personal-body"
          class="min-w-0 md:order-2 lg:order-1 lg:row-span-2"
          :metrics="data.metrics"
          :profile="data.profile"
          :day="day"
          :today="today"
          :timezone="zone"
          :sources="data.sources"
          :fat-sources="data.metrics_by_source?.body_fat_percent"
          :caliper-source-id="caliperSourceId"
          :can-resolve="canWrite"
          @profile="profileOpen = true"
        >
          <template #caliper>
            <div v-if="profile" class="space-y-2 border-t pt-3" data-testid="dashboard-caliper">
              <details :open="!mobile" class="space-y-2">
                <summary
                  class="flex min-h-11 cursor-pointer flex-wrap items-center justify-between gap-2 text-sm md:hidden"
                  data-testid="caliper-actions-toggle"
                >
                  <span class="flex items-center gap-2 font-medium"
                    ><Ruler class="size-4" />{{ t('dashboard.caliperTitle') }}</span
                  >
                  <span v-if="lastCaliper" class="tabular-nums"
                    >{{
                      lastCaliper.body_fat_percent === null
                        ? t('dashboard.caliperRaw')
                        : `${formatAmount(lastCaliper.body_fat_percent, 1)} %`
                    }}
                    · {{ momentLabel(lastCaliper.measured_at, lastCaliper.timezone, today) }}</span
                  >
                  <span v-else class="text-muted-foreground">{{
                    t(
                      caliper.isPending.value
                        ? 'dashboard.caliperLoading'
                        : 'dashboard.caliperEmpty',
                    )
                  }}</span>
                </summary>
                <p v-if="!mobile && lastCaliper" class="text-muted-foreground text-sm break-words">
                  {{
                    t('dashboard.caliperLast', {
                      when: momentPhrase(lastCaliper.measured_at, lastCaliper.timezone, today),
                    })
                  }}
                  <span v-if="lastCaliper.body_fat_percent === null" class="text-warning">
                    · {{ t('dashboard.caliperRaw') }}</span
                  >
                </p>
                <div class="flex flex-wrap gap-2">
                  <Button
                    v-if="canWrite"
                    variant="outline"
                    class="flex-1"
                    data-testid="caliper-add"
                    @click="caliperOpen = true"
                  >
                    <Plus /><Ruler />{{ t('personal.caliper.add') }}
                  </Button>
                  <Button
                    variant="ghost"
                    class="flex-1"
                    data-testid="caliper-history-open"
                    @click="caliperHistoryOpen = true"
                  >
                    {{ t('dashboard.caliperHistory') }}
                  </Button>
                </div>
              </details>
              <RefreshFailed :query="caliper" />
              <p v-if="caliper.isLoadingError.value" role="status" class="text-warning text-sm">
                {{ t('dashboard.caliperFailed') }}
              </p>
              <div
                v-if="caliperNeedsProfile"
                class="text-warning flex flex-wrap items-center gap-2 text-sm"
                role="status"
              >
                <p>{{ t('dashboard.caliperProfile') }}</p>
                <Button variant="ghost" class="min-h-11" @click="profileOpen = true">{{
                  t('personal.bodyProfileTitle')
                }}</Button>
              </div>
            </div>
          </template>
        </DashboardBody>
        <DashboardNutrition
          id="personal-nutrition"
          class="min-w-0 md:order-3 lg:col-span-2"
          :day="day"
          :today="today"
          :timezone="zone"
        />
      </div>
      <!-- The ring follows weight, sleep, body and food: its readings are the day's detail. -->
      <SensorDaySummary :day="day" :timezone="zone" :today="today" />
      <div class="flex flex-wrap items-center gap-3">
        <Button variant="outline" @click="scoreOpen = !scoreOpen">{{
          scoreOpen ? 'Скрыть оценки Soul' : 'Оценки Soul за день'
        }}</Button>
        <Button variant="ghost" as-child>
          <RouterLink :to="personalHistoryLink(day, 'daily', 'derived_scores')"
            >История оценок</RouterLink
          >
        </Button>
      </div>
      <DerivedScores v-if="scoreOpen" :from="day" :to="day" :timezone="zone" :today="today" />

      <nav
        class="bg-card hidden gap-2 rounded-3xl border p-3 md:grid md:grid-cols-4"
        :aria-label="t('personal.more')"
      >
        <Button variant="ghost" class="justify-start" as-child
          ><RouterLink :to="allHistoryLink" @click="capturePersonalReturn(allHistoryLink)"
            ><History />{{ t('dashboard.allMeasurements') }}</RouterLink
          ></Button
        >
        <Button
          v-if="profile"
          variant="ghost"
          class="justify-start"
          data-testid="profile-open"
          @click="profileOpen = true"
          ><UserRound />{{ t('personal.bodyProfileTitle') }}</Button
        >
        <Button variant="ghost" class="justify-start" as-child
          ><RouterLink :to="sourcesLink" @click="capturePersonalReturn(sourcesLink)"
            ><Plug />{{ t('personal.sources')
            }}<span v-if="sourceProblems.length" class="text-warning">
              · {{ sourceProblems.length }}</span
            ></RouterLink
          ></Button
        >
        <Button variant="ghost" class="justify-start" as-child
          ><RouterLink :to="goalsLink" @click="capturePersonalReturn(goalsLink)"
            ><Goal />{{ t('sources.goals') }}</RouterLink
          ></Button
        >
      </nav>
      <p
        v-if="sourceProblems.length"
        class="text-warning px-3 text-sm break-words sm:col-span-3"
        role="status"
      >
        {{
          t('dashboard.sourcesUnavailable', {
            names: sourceProblems.map((source) => source.name).join(', '),
          })
        }}
      </p>
    </template>
    <Panel
      v-if="detail"
      v-model:open="detailOpen"
      :title="t(`personal.metric.${detail.kind}`)"
      :autofocus="false"
    >
      <MetricDetails
        :metric="detail"
        :day="day"
        :today="today"
        :timezone="zone"
        :can-write="canWrite"
        :history-link="detailHistoryLink"
        @history="capturePersonalReturn"
        @edit="editRecord"
      />
    </Panel>
    <Panel
      v-model:open="caliperHistoryOpen"
      :title="t('dashboard.caliperHistory')"
      :autofocus="false"
    >
      <CaliperCard
        v-if="profile && caliperHistoryOpen"
        :profile="profile"
        :day="day"
        :today="today"
        :can-write="canWrite"
      />
    </Panel>
    <ProfilePanel
      v-if="profile"
      v-model:open="profileOpen"
      :profile="profile"
      :today="today"
      :read-only="!canWrite"
      :autofocus="false"
    />
    <CaliperPanel
      v-if="profile"
      v-model:open="caliperOpen"
      :profile="profile"
      :day="day"
      :today="today"
    />
    <WeightPanel v-model:open="weightOpen" :day="day" />
    <RecordPanel v-if="record" v-model:open="recordOpen" :record="record" :today="today" />
  </div>
</template>
