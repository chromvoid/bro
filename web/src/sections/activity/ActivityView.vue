<script setup lang="ts">
import { ChevronRight, Plus } from '@lucide/vue'
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import DayNav from '@/components/DayNav.vue'
import HeaderAction from '@/components/HeaderAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import { dayLabel, isIsoDay, rangeLabel, shiftDay } from '@/core/dates'
import { useCalendarDay } from '@/core/useCalendarDay'
import { useInitialLoadError } from '@/core/useInitialLoadError'
import { provideDisplayUnits } from '@/sections/personal/displayUnits'
import { useOverview, usePersonal } from '@/sections/personal/usePersonal'
import { useSession } from '@/stores/session'
import ActivityDayOverview from './ActivityDayOverview.vue'
import ActivityDynamics from './ActivityDynamics.vue'
import ActivityEntryRow from './ActivityEntryRow.vue'
import ActivityReportHistory from './ActivityReportHistory.vue'
import {
  activityReadingQuery,
  captureActivityAnchor,
  captureActivityPosition,
  useActivityScrollRestore,
} from './activityScroll'
import { KINDS, type ActivityKind } from './api'
import DeviceWorkoutCard from './DeviceWorkoutCard.vue'
import { workoutTitle } from './sports'
import ExerciseCatalog from './ExerciseCatalog.vue'
import ExerciseProgress from './ExerciseProgress.vue'
import RangeNav from './RangeNav.vue'
import { journalGroups, useRunningData, type WorkoutEntry } from './running'
import RunningStepsCard from './RunningStepsCard.vue'
import RunningWorkoutsSummary from './RunningWorkoutsSummary.vue'
import SessionForm from './SessionForm.vue'
import StepsPanel from './StepsPanel.vue'
import { useActivity, useActivitySessions, useExercises } from './useActivity'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const account = useSession()
const activity = useActivity()
const personal = usePersonal()
const zone = computed(() => account.me?.timezone ?? 'UTC')
const today = useCalendarDay(zone)
const day = computed(() =>
  isIsoDay(route.query.day) && route.query.day <= today.value ? route.query.day : today.value,
)
// Analytical periods and the overview day retain separate contexts.
const anchor = computed(() =>
  isIsoDay(route.query.end) && route.query.end <= today.value ? route.query.end : day.value,
)
const period = computed(() =>
  [7, 30, 90].includes(Number(route.query.period)) ? Number(route.query.period) : 30,
)
const kind = computed<ActivityKind | null>(() =>
  KINDS.includes(route.query.kind as ActivityKind) ? (route.query.kind as ActivityKind) : null,
)
const tab = computed(() =>
  ['journal', 'progress', 'exercises'].includes(String(route.query.tab))
    ? String(route.query.tab)
    : 'overview',
)
const subject = computed<'steps' | 'active_minutes' | 'sessions' | 'strength'>(() => {
  const value = route.query.metric
  if (
    value === 'steps' ||
    value === 'active_minutes' ||
    value === 'sessions' ||
    value === 'strength'
  )
    return value
  return route.query.exercise ? 'strength' : 'steps'
})
const selectedExercise = computed({
  get: () => (typeof route.query.exercise === 'string' ? route.query.exercise : ''),
  set: (exercise: string) => update({ exercise: exercise || undefined }),
})
const analyticsRange = computed(() => ({
  from: shiftDay(anchor.value, 1 - period.value),
  to: anchor.value,
  kind: kind.value,
}))
const listRange = computed(() =>
  tab.value === 'overview'
    ? { from: shiftDay(day.value, -29), to: day.value, kind: null }
    : analyticsRange.value,
)
const canWrite = computed(() => account.can('activity.write'))
const canPersonal = computed(() => account.can('personal.read'))
const overview = useOverview(today, canPersonal)
const displayUnits = computed(() => {
  const profile = overview.data.value?.profile
  return profile && profile.user_id === account.me?.id ? profile.display_units : 'metric'
})
provideDisplayUnits(displayUnits)
const canSteps = computed(() => canPersonal.value && account.can('personal.write'))
const listing = computed(() => tab.value === 'overview' || tab.value === 'journal')
const sessions = useActivitySessions(listRange, listing)
const sessionsError = useInitialLoadError(sessions)
const catalog = useExercises(
  computed(
    () => tab.value === 'exercises' || (tab.value === 'progress' && subject.value === 'strength'),
  ),
)
const dataOpen = ref(false)
const running = useRunningData(
  computed(() => ({ from: listRange.value.from, to: listRange.value.to })),
  computed(
    () =>
      canPersonal.value &&
      (dataOpen.value ||
        (listing.value &&
          (tab.value === 'overview' || kind.value === null || kind.value === 'running'))),
  ),
  computed(() => canPersonal.value && dataOpen.value),
)
const runningSteps = running.steps
const runningWorkouts = running.workouts
const runningWorkoutsError = useInitialLoadError(runningWorkouts)
const dataDate = computed(() => (tab.value === 'overview' ? day.value : anchor.value))
const recordingDate = computed(() => (tab.value === 'overview' ? day.value : anchor.value))
const addLabel = computed(() =>
  recordingDate.value === today.value
    ? t('activity.addSession')
    : t('activityRedesign.addDated', { date: dayLabel(recordingDate.value, today.value) }),
)
const formOpen = ref(false)
const formDate = ref(today.value)
const formKind = ref<ActivityKind>('walking')
const stepsOpen = ref(false)
const stepsDate = ref(today.value)
const deviceReport = ref<WorkoutEntry | null>(null)
let deviceOpener: HTMLElement | null = null
watch([() => account.me?.id, canPersonal], ([user, allowed], [previousUser]) => {
  if (allowed && user === previousUser) return
  deviceReport.value = null
  deviceOpener = null
  dataOpen.value = false
})
function openDevice(report: WorkoutEntry) {
  deviceOpener = document.getElementById(`activity-entry-device:${report.id}`)
  deviceReport.value = report
}
const deviceOpen = computed({
  get: () => !!deviceReport.value,
  set: (open: boolean) => {
    if (open) return
    deviceReport.value = null
    const opener = deviceOpener
    deviceOpener = null
    void nextTick(() => {
      if (opener?.isConnected) opener.focus({ preventScroll: true })
    })
  },
})
const busy = ref(false)
let pendingSaved: (() => void) | undefined
activity.onResolved(() => {
  pendingSaved?.()
  pendingSaved = undefined
})
const disabled = computed(() => busy.value || !!activity.unresolved.value.length)
function update(values: Record<string, string | undefined>) {
  void router.replace({ query: { ...route.query, ...values } })
}
function addSession(selectedKind?: ActivityKind) {
  formDate.value = tab.value === 'overview' ? day.value : anchor.value
  formKind.value =
    selectedKind ??
    (tab.value === 'exercises' ? 'strength' : tab.value === 'overview' ? null : kind.value) ??
    'walking'
  formOpen.value = true
}
function correctSteps() {
  stepsDate.value = day.value
  stepsOpen.value = true
}
const tabs = computed(() =>
  ['overview', 'journal', 'progress'].map((value) => ({
    value,
    label: t(value === 'progress' ? 'activityRedesign.dynamics' : `activity.tabs.${value}`),
  })),
)
const subjects = computed(() =>
  ['steps', 'active_minutes', 'sessions', 'strength'].map((value) => ({
    value,
    label: t(`activityRedesign.subjects.${value}`),
  })),
)
const grouped = computed(() =>
  journalGroups(
    sessions.data.value?.sessions ?? [],
    canPersonal.value ? running.reports.value : [],
    tab.value === 'overview' ? null : kind.value,
  ),
)
// Bound by records: one busy date cannot fill the overview with twenty entries.
const recent = computed(() => {
  let remaining = 3
  return grouped.value.flatMap(([date, entries]) => {
    const items = entries.slice(0, remaining)
    remaining -= items.length
    return items.length ? [[date, items] as (typeof grouped.value)[number]] : []
  })
})
const showDevice = computed(
  () =>
    canPersonal.value &&
    (tab.value === 'overview' || kind.value === null || kind.value === 'running'),
)
const returnQuery = computed(() => activityReadingQuery(route.query, today.value))
const progressReady = ref(false)
const dayReady = ref(false)
useActivityScrollRestore(
  returnQuery,
  computed(() =>
    tab.value === 'progress'
      ? progressReady.value
      : !sessions.isPending.value &&
        (!showDevice.value || !runningWorkouts.isPending.value) &&
        (tab.value !== 'overview' || !canPersonal.value || dayReady.value),
  ),
)
async function operate(
  operation: string,
  payload: Record<string, unknown>,
  expected: Record<string, number>,
  saved?: () => void,
) {
  if (disabled.value) return
  busy.value = true
  try {
    pendingSaved = saved
    await activity.operate(operation, payload, expected)
    pendingSaved?.()
    pendingSaved = undefined
  } catch (error) {
    activity.failed(error)
  } finally {
    busy.value = false
  }
}
function progress(exercise: string) {
  update({ tab: 'progress', metric: 'strength', exercise })
}
async function recover(key: string, resend = false) {
  const entry = await (resend ? activity.resend(key) : activity.check(key))
  if (entry?.state === 'resolved') {
    pendingSaved?.()
    pendingSaved = undefined
  }
}
</script>

<template>
  <PageHeader :title="t('activity.title')" width="5xl">
    <template #actions
      ><HeaderAction
        v-if="canWrite"
        :icon="Plus"
        :label="addLabel"
        :disabled="disabled"
        @click="addSession()"
    /></template>
  </PageHeader>
  <div class="mx-auto max-w-5xl space-y-4 px-4 pb-8 md:px-8" data-testid="activity-view">
    <UnresolvedWrites
      :entries="activity.unresolved.value"
      :describe="activity.describe"
      :check="(key) => recover(key)"
      :resend="(key) => recover(key, true)"
      :abandon="activity.abandon"
    />
    <UnresolvedWrites
      :entries="personal.unresolved.value"
      :describe="personal.describe"
      :check="personal.check"
      :resend="personal.resend"
      :abandon="personal.abandon"
    />
    <SegmentedControl
      :segments="tabs"
      :label="t('activity.sections')"
      :model-value="tab === 'exercises' ? 'progress' : tab"
      @update:model-value="update({ tab: $event })"
    />
    <template v-if="tab === 'overview'">
      <DayNav :day="day" :today="today" @go="update({ day: $event })" />
      <ActivityDayOverview
        v-if="canPersonal"
        :date="day"
        :today="today"
        :timezone="zone"
        @correct-steps="correctSteps"
        @go="update({ day: $event })"
        @dynamics="update({ tab: 'progress', metric: 'steps', end: day })"
        @ready="dayReady = $event"
      />
      <p v-else class="text-muted-foreground text-sm">{{ t('activity.noPersonalAccess') }}</p>
    </template>
    <template v-else-if="tab !== 'exercises'">
      <RangeNav
        :anchor="anchor"
        :period="period"
        :today="today"
        @go="update({ end: $event })"
        @period="update({ period: String($event) })"
      />
      <SegmentedControl
        v-if="tab === 'progress'"
        :segments="subjects"
        :label="t('activityRedesign.chooseSubject')"
        :model-value="subject"
        @update:model-value="update({ metric: $event })"
      />
      <NativeSelect
        v-if="tab === 'journal' || (tab === 'progress' && subject === 'sessions')"
        id="activity-filter"
        :aria-label="t('activity.kind')"
        :model-value="kind ?? ''"
        @update:model-value="update({ kind: $event || undefined })"
      >
        <option value="">{{ t('activity.allKinds') }}</option>
        <option v-for="value in KINDS" :key="value" :value="value">
          {{ t(`activity.kinds.${value}`) }}
        </option>
      </NativeSelect>
    </template>
    <p v-if="canWrite && recordingDate !== today" class="text-muted-foreground text-xs">
      {{ t('activityRedesign.addForDate', { date: dayLabel(recordingDate, today) }) }}
    </p>
    <section v-if="listing" class="space-y-3" data-testid="activity-journal">
      <div class="flex flex-wrap items-baseline justify-between gap-2">
        <h2 class="text-lg font-semibold">
          {{ t(tab === 'overview' ? 'activityRedesign.recentEntries' : 'activity.tabs.journal') }}
        </h2>
        <Button
          v-if="tab === 'overview'"
          variant="ghost"
          class="min-h-11"
          @click="update({ tab: 'journal', end: day, kind: undefined })"
          >{{ t('activity.allSessions') }}<ChevronRight
        /></Button>
      </div>
      <p v-if="tab === 'overview'" class="text-muted-foreground text-xs">
        {{ t('activityRedesign.recentRange', { range: rangeLabel(listRange.from, listRange.to) }) }}
      </p>
      <RefreshFailed :query="sessions" /><LoadingState
        v-if="sessions.isPending.value && !sessionsError"
        :rows="1"
      />
      <ErrorState
        v-if="sessionsError"
        :error="sessionsError.error"
        :retrying="sessionsError.retrying"
        @retry="sessions.refetch()"
      />
      <template v-if="showDevice">
        <RefreshFailed :query="runningWorkouts" /><LoadingState
          v-if="runningWorkouts.isPending.value && !runningWorkoutsError"
          :rows="1"
        />
        <ErrorState
          v-if="runningWorkoutsError"
          data-testid="activity-device-error"
          :error="runningWorkoutsError.error"
          :retrying="runningWorkoutsError.retrying"
          @retry="runningWorkouts.refetch()"
        />
        <div v-if="!running.consistent.value" class="text-warning space-y-2 text-sm" role="status">
          <p>{{ t('resolution.unmatchedPages') }}</p>
          <Button variant="outline" class="min-h-11" @click="runningWorkouts.refetch()">{{
            t('resolution.refresh')
          }}</Button>
        </div>
      </template>
      <div
        v-if="
          !grouped.length &&
          !sessions.isPending.value &&
          !sessionsError &&
          (!showDevice || (!runningWorkouts.isPending.value && !runningWorkoutsError))
        "
        class="bg-card space-y-2 rounded-2xl border p-4"
        data-testid="activity-empty-journal"
      >
        <p class="text-muted-foreground text-sm">{{ t('activityRedesign.noEntries') }}</p>
        <Button v-if="canWrite" variant="outline" :disabled="disabled" @click="addSession()"
          ><Plus />{{ addLabel }}</Button
        >
      </div>
      <p v-if="sessions.data.value?.truncated" class="text-warning text-sm" role="status">
        {{ t('activity.truncated') }}
      </p>
      <section
        v-for="[date, items] in tab === 'overview' ? recent : grouped"
        :key="date"
        class="space-y-2"
      >
        <h3 class="text-muted-foreground text-sm font-medium">{{ dayLabel(date, today) }}</h3>
        <ActivityEntryRow
          v-for="item in items"
          :key="item.id"
          :entry="item"
          :timezone="zone"
          :return-query="returnQuery"
          @open="openDevice"
          @activate="captureActivityPosition(returnQuery, $event)"
        />
      </section>
      <Button
        v-if="
          tab === 'journal' &&
          showDevice &&
          runningWorkouts.hasNextPage.value &&
          running.consistent.value
        "
        variant="outline"
        :disabled="runningWorkouts.isFetchingNextPage.value"
        @click="runningWorkouts.fetchNextPage()"
        >{{ t('ring.loadMore') }}</Button
      >
      <div
        v-if="tab === 'journal' && showDevice && runningWorkouts.isFetchNextPageError.value"
        class="text-warning space-y-2 text-sm"
        role="status"
      >
        <p>{{ t('ring.nextPageFailed') }}</p>
        <Button variant="outline" @click="runningWorkouts.refetch()">{{
          t('resolution.refresh')
        }}</Button>
      </div>
    </section>
    <ActivityDynamics
      v-if="tab === 'progress' && subject !== 'strength'"
      :subject="subject"
      :range="analyticsRange"
      :today="today"
      :timezone="zone"
      @go="update({ tab: 'overview', day: $event })"
    />
    <template v-if="tab === 'exercises' || (tab === 'progress' && subject === 'strength')">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h2 class="text-lg font-semibold">
          {{
            t(tab === 'exercises' ? 'activity.exerciseCatalog' : 'activityRedesign.strengthHistory')
          }}
        </h2>
        <Button
          v-if="tab === 'exercises'"
          variant="ghost"
          @click="update({ tab: 'progress', metric: 'strength' })"
          >{{ t('activityRedesign.backToDynamics') }}</Button
        >
      </div>
      <RefreshFailed :query="catalog" /><LoadingState
        v-if="catalog.isPending.value"
        :rows="2"
      /><ErrorState
        v-else-if="catalog.isLoadingError.value"
        :error="catalog.error.value"
        @retry="catalog.refetch()"
      />
      <ExerciseCatalog
        v-else-if="tab === 'exercises'"
        :catalog="catalog.data.value?.exercises ?? []"
        :can-write="canWrite"
        :busy="disabled"
        @operation="operate"
        @progress="progress"
      />
      <ExerciseProgress
        v-else
        v-model:selected="selectedExercise"
        :catalog="catalog.data.value?.exercises ?? []"
        :range="analyticsRange"
        :can-write="canWrite"
        :return-query="returnQuery"
        @add-strength="addSession('strength')"
        @catalog="update({ tab: 'exercises' })"
        @activate="captureActivityAnchor(returnQuery, $event)"
        @ready="progressReady = $event"
      />
    </template>
    <div class="flex flex-wrap gap-2 border-t pt-3">
      <div id="activity-native-controls" class="empty:hidden" />
      <Button
        v-if="canPersonal"
        variant="ghost"
        data-testid="activity-data-details"
        @click="dataOpen = true"
        >{{ t('activityRedesign.movementData') }}<ChevronRight /></Button
      ><Button
        v-if="tab !== 'exercises' && !(tab === 'progress' && subject === 'strength')"
        variant="ghost"
        @click="update({ tab: 'exercises' })"
        >{{ t('activity.exerciseCatalog') }}<ChevronRight
      /></Button>
    </div>
    <Panel
      v-if="canPersonal && deviceReport"
      v-model:open="deviceOpen"
      :title="workoutTitle(t, deviceReport.data.sport_type)"
      :autofocus="false"
      ><p class="text-muted-foreground text-sm">{{ dayLabel(deviceReport.date, today) }}</p>
      <DeviceWorkoutCard :report="deviceReport" :timezone="zone" :today="today" :heading="false"
    /></Panel>
    <Panel
      v-if="canPersonal"
      v-model:open="dataOpen"
      :title="t('activityRedesign.movementData')"
      :autofocus="false"
    >
      <template v-if="dataOpen"
        ><p class="text-muted-foreground text-sm">{{ rangeLabel(listRange.from, listRange.to) }}</p>
        <RefreshFailed :query="[runningSteps, runningWorkouts]" />
        <RunningStepsCard
          :page="runningSteps.data.value"
          :loading="runningSteps.isPending.value"
          :error="runningSteps.isLoadingError.value ? runningSteps.error.value : undefined"
          :date="dataDate"
          :today="today"
          :timezone="zone"
          @retry="runningSteps.refetch()"
        />
        <RunningWorkoutsSummary
          :page="running.workoutPage.value"
          :loading="runningWorkouts.isPending.value"
          :error="runningWorkouts.isLoadingError.value ? runningWorkouts.error.value : undefined"
          :today="today"
          :timezone="zone"
          @retry="runningWorkouts.refetch()"
        />
        <ActivityReportHistory
          v-if="kind === null || kind === 'running'"
          :from="listRange.from"
          :to="listRange.to"
          :timezone="zone"
          :today="today"
        />
        <Button as-child variant="outline" class="min-h-11 w-full"
          ><RouterLink :to="{ name: 'personal-sources' }">{{
            t('personal.sources')
          }}</RouterLink></Button
        >
      </template>
    </Panel>
    <SessionForm
      v-if="canWrite"
      v-model:open="formOpen"
      :today="today"
      :timezone="zone"
      :initial-date="formDate"
      :initial-kind="formKind"
    />
    <StepsPanel v-if="canSteps" v-model:open="stepsOpen" :today="today" :initial-date="stepsDate" />
  </div>
</template>
