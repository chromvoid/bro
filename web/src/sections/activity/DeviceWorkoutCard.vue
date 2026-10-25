<script setup lang="ts">
import { useDisplayUnits, distanceText } from '@/sections/personal/displayUnits'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { dayIn, fullDayLabel, timeIn } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import ResolutionExplanation from '@/sections/personal/ResolutionExplanation.vue'
import { Button } from '@/components/ui/button'
import WorkoutEffort from './WorkoutEffort.vue'
import WorkoutHeartRateHistory from './WorkoutHeartRateHistory.vue'
import { PhoneWorkoutOrigin } from './workoutHeartRate'
import type { RunningEpisode } from './running'
import { workoutDuration, workoutTitle } from './sports'
import type { Resolution } from '@/sections/personal/resolution'
import RecordDetails from '@/sections/personal/RecordDetails.vue'
import WorkoutTrackHistory from './WorkoutTrackHistory.vue'
import { activityReportSnapshot, type ActivityReport } from './activityReports'
const displayUnits = useDisplayUnits()
const props = withDefaults(
  defineProps<{
    report: RunningEpisode & { date: string; resolution?: Resolution }
    originalReport?: ActivityReport
    timezone: string
    today: string
    /** False where the surrounding panel already names the workout. */
    heading?: boolean
  }>(),
  { heading: true },
)
const { t } = useI18n()
const displayZone = computed(() => props.report.timezone ?? props.timezone)
const startDay = computed(() => dayIn(displayZone.value, new Date(props.report.start_at)))
const endDay = computed(() => dayIn(displayZone.value, new Date(props.report.end_at)))
const phoneOrigin = computed(() => {
  const parsed = PhoneWorkoutOrigin.safeParse(props.report.origin)
  return parsed.success ? parsed.data : null
})
const detailsRecord = computed(() =>
  props.originalReport
    ? {
        ...props.originalReport,
        start_at: props.originalReport.start_at ?? undefined,
        end_at: props.originalReport.end_at ?? undefined,
      }
    : props.report,
)
// Found in a strap's history by heart rate and cadence, not recorded as a workout by the device.
const derived = computed(() => props.report.origin?.source === 'history_r18')
const showHeartRate = ref(false)
const showTrack = ref(false)
</script>

<template>
  <article class="bg-card space-y-2 rounded-2xl border p-4" data-testid="device-workout-card">
    <p v-if="heading" class="font-medium">
      {{ workoutTitle(t, report.data.sport_type) }}
    </p>
    <p class="text-muted-foreground text-sm">
      {{
        t(
          phoneOrigin
            ? 'ring.workout.phoneSource'
            : derived
              ? 'activity.running.detectedSource'
              : 'activity.running.ringSource',
        )
      }}<span v-if="report.source_name"> · {{ report.source_name }}</span> ·
      <template v-if="startDay !== report.date">{{ fullDayLabel(startDay) }}, </template
      >{{ timeIn(displayZone, report.start_at) }} —
      <template v-if="endDay !== report.date">{{ fullDayLabel(endDay) }}, </template
      >{{ timeIn(displayZone, report.end_at) }}
      <span v-if="displayZone !== timezone" data-testid="device-workout-timezone">
        · {{ displayZone }}</span
      >
    </p>
    <p v-if="phoneOrigin" class="text-muted-foreground text-sm" data-testid="phone-workout-status">
      {{ t(`ring.workout.status.${phoneOrigin.details.status}`) }} ·
      {{ t(`ring.workout.trigger.${phoneOrigin.details.trigger}`) }}
    </p>
    <Button
      v-if="phoneOrigin && report.source_id"
      size="sm"
      variant="outline"
      :aria-expanded="showHeartRate"
      @click="showHeartRate = !showHeartRate"
      >{{ t(showHeartRate ? 'ring.workout.hideHeartRate' : 'ring.workout.showHeartRate') }}</Button
    >
    <WorkoutHeartRateHistory
      v-if="showHeartRate && phoneOrigin && report.source_id"
      :workout-id="phoneOrigin.record_id"
      :source-id="report.source_id"
      :timezone="displayZone"
    />
    <Button
      v-if="phoneOrigin && report.source_id"
      size="sm"
      variant="outline"
      :aria-expanded="showTrack"
      @click="showTrack = !showTrack"
      >{{ t(showTrack ? 'workoutDevice.hideTrack' : 'workoutDevice.showTrack') }}</Button
    >
    <WorkoutTrackHistory
      v-if="showTrack && phoneOrigin && report.source_id"
      :workout-id="phoneOrigin.record_id"
      :source-id="report.source_id"
      :timezone="displayZone"
    />
    <p class="text-sm" data-testid="device-workout-values">
      <span v-if="report.data.duration_seconds !== null">{{
        workoutDuration(report.data.duration_seconds, false)
      }}</span>
      <span v-else>{{ t('activity.running.unknownDuration') }}</span>
      <span v-if="report.data.steps !== null">
        · {{ formatAmount(report.data.steps, 0) }} {{ t('activity.running.stepUnit') }}</span
      >
      <span v-if="report.data.distance_meters !== null">
        · {{ distanceText(report.data.distance_meters, displayUnits) }}</span
      >
    </p>
    <WorkoutEffort
      v-if="report.data.heart_rate"
      :heart-rate="report.data.heart_rate"
      :zone-seconds="report.data.zone_seconds"
      :trimp="report.data.trimp"
      :effort="report.data.effort"
    />
    <p v-if="report.data.active_duration_seconds != null" class="text-muted-foreground text-sm">
      {{
        t('workoutDevice.activeDuration', {
          duration: workoutDuration(report.data.active_duration_seconds, false),
        })
      }}
    </p>
    <details
      v-if="report.data.sport_plan"
      class="text-muted-foreground text-xs"
      data-testid="reported-sport-plan"
    >
      <summary class="min-h-11 cursor-pointer py-2">{{ t('workoutDevice.sportPlan') }}</summary>
      <p>{{ report.data.sport_plan.name }} · {{ report.data.sport_plan.family }}</p>
      <p>
        {{
          t('workoutDevice.requested', {
            id: report.data.sport_plan.requested_id,
            candidate: report.data.sport_plan.candidate_wire_mode ?? t('workoutDevice.noCandidate'),
            actual: report.data.sport_plan.actual_wire_mode ?? t('workoutDevice.unconfirmedMode'),
          })
        }}
      </p>
      <p>{{ report.data.sport_plan.qualification }} · {{ report.data.sport_plan.evidence_ref }}</p>
    </details>
    <details
      v-if="report.data.pauses?.length"
      class="text-muted-foreground text-sm"
      data-testid="reported-workout-pauses"
    >
      <summary class="min-h-11 cursor-pointer py-2">
        {{ t('workoutDevice.pauses', { count: report.data.pauses.length }) }}
      </summary>
      <ul class="space-y-1">
        <li v-for="pause in report.data.pauses" :key="pause.start_at">
          {{ timeIn(displayZone, pause.start_at) }} — {{ timeIn(displayZone, pause.end_at) }}
        </li>
      </ul>
    </details>
    <RecordDetails :record="detailsRecord" :timezone="displayZone" :today="today" />
    <details
      v-if="originalReport"
      class="text-muted-foreground text-xs"
      data-testid="saved-activity-report-fields"
    >
      <summary class="min-h-11 cursor-pointer py-2">{{ t('workoutDevice.savedFields') }}</summary>
      <p>{{ t('workoutDevice.savedFieldsDescription') }}</p>
      <pre class="max-h-80 overflow-auto whitespace-pre-wrap break-all">{{
        JSON.stringify(activityReportSnapshot(originalReport), null, 2)
      }}</pre>
    </details>
    <ResolutionExplanation
      v-if="report.resolution"
      :resolution="report.resolution"
      :timezone="timezone"
      :today="today"
    />
  </article>
</template>
