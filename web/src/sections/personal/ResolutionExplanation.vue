<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { momentLabel, momentPhrase } from '@/core/dates'
import { duration } from './api'
import { groupFacts, type Resolution } from './resolution'
import SleepSessions from './SleepSessions.vue'
import { sleepConflictKey, sleepReportConflict } from './sleep'

const props = defineProps<{ resolution: Resolution; timezone: string; today: string }>()
const { t, te } = useI18n()
const label = (scope: string, key: string) =>
  te(`resolution.${scope}.${key}`) ? t(`resolution.${scope}.${key}`) : key
const covered = computed(() =>
  props.resolution.coverage?.point_count && !props.resolution.coverage?.intervals?.length
    ? undefined
    : (props.resolution.coverage?.reported_seconds ?? props.resolution.coverage?.covered_seconds),
)
const sleepConflict = computed(() =>
  props.resolution.sessions ? sleepReportConflict(props.resolution) : null,
)
const sleepQuality = computed(() =>
  props.resolution.sessions?.length
    ? (props.resolution.quality?.flags ?? []).filter((flag) =>
        ['unknown_sleep_stage', 'duration_mismatch', 'ambiguous_time'].includes(flag),
      )
    : [],
)
</script>

<template>
  <details class="text-sm" data-testid="resolution-explanation">
    <summary class="text-primary cursor-pointer">{{ t('resolution.why') }}</summary>
    <div class="text-muted-foreground mt-2 space-y-2">
      <p>
        {{
          sleepConflict ? t(sleepConflictKey(sleepConflict)) : label('status', resolution.status)
        }}
      </p>
      <p
        v-if="resolution.protocol?.verified === false && resolution.selected.length"
        class="text-warning"
      >
        {{ t('resolution.protocolUnverified') }}
      </p>
      <p v-if="covered !== undefined">
        {{ t('resolution.coverage', { duration: duration(covered / 60) }) }}
      </p>
      <p v-else>{{ t('resolution.coverageUnknown') }}</p>
      <p v-if="resolution.freshness?.measured_at">
        {{
          t('resolution.measuredAt', {
            when: momentPhrase(resolution.freshness.measured_at, timezone, today),
          })
        }}
      </p>
      <p v-if="resolution.explanation_truncated">{{ t('resolution.explanationTruncated') }}</p>
      <p class="font-medium">
        {{ t('resolution.selected')
        }}<template v-if="resolution.selected_count !== undefined"
          >: {{ resolution.selected_count }}</template
        >
      </p>
      <SleepSessions
        v-if="resolution.sessions?.length && !resolution.sleep_analysis"
        :sessions="resolution.sessions"
        :timezone="timezone"
        :today="today"
        detailed
      />
      <ul v-else-if="resolution.selected.length" class="space-y-1">
        <li v-for="fact in resolution.selected" :key="fact.id">
          {{
            fact.source_name ??
            (fact.source_id ? t('personal.unnamedSource') : t('resolution.manual'))
          }}<span v-if="fact.contribution !== null"> · {{ fact.contribution }}</span>
          <span v-if="fact.start_at">
            · {{ momentLabel(fact.start_at, timezone, today)
            }}<template v-if="fact.end_at && fact.end_at !== fact.start_at">
              — {{ momentLabel(fact.end_at, timezone, today) }}</template
            ></span
          >
        </li>
      </ul>
      <p v-else>{{ t('resolution.noSelected') }}</p>
      <p v-for="flag in sleepQuality" :key="flag">{{ t(`personal.sleepQuality.${flag}`) }}</p>
      <template v-if="resolution.alternatives.length || resolution.alternatives_count">
        <p class="font-medium">
          {{ t('resolution.alternatives')
          }}<template v-if="resolution.alternatives_count !== undefined"
            >: {{ resolution.alternatives_count }}</template
          >
        </p>
        <ul class="space-y-1">
          <li
            v-for="{ fact, count } in groupFacts(resolution.alternatives)"
            :key="`${fact.id}-${fact.reason}`"
          >
            {{
              fact.source_name ??
              (fact.source_id ? t('personal.unnamedSource') : t('resolution.manual'))
            }}
            · {{ label('reason', fact.reason ?? 'not_selected')
            }}<template v-if="count > 1"> · {{ t('resolution.factsCount', count) }}</template>
          </li>
        </ul>
      </template>
    </div>
  </details>
</template>
