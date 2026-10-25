<script setup lang="ts">
import { Plus, Ruler, Trash2 } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { dayIn, momentLabel } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import type { Profile } from './api'
import {
  FOLD_SITES,
  caliperCompletion,
  missingCaliperKey,
  useCaliperHistory,
  type CaliperMeasurement,
} from './caliper'
import CaliperMethod from './CaliperMethod.vue'
import CaliperPanel from './CaliperPanel.vue'
import { usePersonal } from './usePersonal'
import { capturePersonalReturn, personalScreenLink } from './personalNavigation'

const props = defineProps<{
  profile: Profile
  day: string
  today: string
  canWrite: boolean
}>()
const { t } = useI18n()
const profileLinks = computed(() => ({
  birth: personalScreenLink('personal-profile', props.day, 'body', { field: 'birth' }),
  gender: personalScreenLink('personal-profile', props.day, 'body', { field: 'gender' }),
}))
const personal = usePersonal()
const history = useCaliperHistory(computed(() => props.day))
const records = computed(() =>
  [...(history.data.value?.records ?? [])].sort((a, b) =>
    b.measured_at.localeCompare(a.measured_at),
  ),
)
const expanded = ref(false)
const shown = computed(() => (expanded.value ? records.value : records.value.slice(0, 3)))
const open = ref(false)
const deleting = ref<CaliperMeasurement | null>(null)
const busy = ref(false)

async function complete(record: CaliperMeasurement) {
  if (!props.canWrite || busy.value || !caliperCompletion(record, props.profile).available) return
  busy.value = true
  try {
    await personal.operate(
      'caliper.complete',
      { measurement_id: record.id },
      {
        [record.id]: record.revision,
        [props.profile.user_id]: props.profile.revision,
      },
    )
    if (deleting.value?.id === record.id) deleting.value = null
    toast.success(t('personal.caliper.completed'))
  } catch (error) {
    personal.failed(error)
  } finally {
    busy.value = false
  }
}

function measuredLabel(record: CaliperMeasurement): string {
  return momentLabel(record.measured_at, record.timezone, dayIn(record.timezone))
}

function missing(record: CaliperMeasurement): string {
  return record.calculation.missing_fields
    .map((field) => missingCaliperKey(field))
    .filter((key): key is string => key !== null)
    .map((key) => t(key).toLowerCase())
    .join(', ')
}

function missingFolds(record: CaliperMeasurement): boolean {
  return (
    record.calculation.missing_fields.some((field) =>
      (FOLD_SITES as readonly string[]).includes(field),
    ) || caliperCompletion(record, props.profile).missingFolds.length > 0
  )
}

async function remove() {
  const record = deleting.value
  if (!record || !props.canWrite || busy.value) return
  busy.value = true
  try {
    await personal.operate(
      'caliper.delete',
      { measurement_id: record.id },
      { [record.id]: record.revision },
    )
    toast.success(t('personal.caliper.deleted'))
    deleting.value = null
  } catch (error) {
    personal.failed(error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section
    class="bg-card space-y-4 rounded-3xl border p-5"
    aria-labelledby="caliper-title"
    data-testid="caliper-card"
  >
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 id="caliper-title" class="flex items-center gap-2 font-semibold">
          <Ruler class="size-4" />{{ t('personal.caliper.title') }}
        </h2>
        <p class="text-muted-foreground text-sm">{{ t('personal.caliper.intro') }}</p>
      </div>
      <Button v-if="canWrite" variant="outline" size="sm" @click="open = true">
        <Plus />{{ t('personal.caliper.add') }}
      </Button>
    </div>
    <RefreshFailed :query="history" />
    <LoadingState v-if="history.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="history.isLoadingError.value"
      :error="history.error.value"
      @retry="history.refetch()"
    />
    <template v-else-if="history.data.value">
      <p v-if="!records.length" class="text-muted-foreground text-sm">
        {{ t('personal.caliper.empty') }}
      </p>
      <div v-else class="space-y-3">
        <h3 class="text-sm font-medium">{{ t('personal.caliper.history') }}</h3>
        <ul class="divide-y">
          <li
            v-for="record in shown"
            :key="record.id"
            class="space-y-2 py-3"
            data-testid="caliper-record"
          >
            <div class="flex items-start justify-between gap-2">
              <time :datetime="record.measured_at" class="text-sm font-medium">
                {{ measuredLabel(record) }}
              </time>
              <Button
                v-if="canWrite"
                :disabled="busy"
                variant="ghost"
                size="icon-sm"
                :aria-label="t('personal.caliper.deleteAt', { when: measuredLabel(record) })"
                @click="deleting = record"
                ><Trash2 class="size-4"
              /></Button>
            </div>
            <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-3">
              <template v-for="site in FOLD_SITES" :key="site">
                <div v-if="record.folds_mm[site] !== undefined">
                  <dt class="text-muted-foreground">{{ t(`personal.caliper.sites.${site}`) }}</dt>
                  <dd class="tabular-nums">
                    {{ formatAmount(record.folds_mm[site], 2) }} {{ t('personal.caliper.mm') }}
                  </dd>
                </div>
              </template>
            </dl>
            <p class="text-muted-foreground text-xs">
              {{
                t('personal.caliper.snapshot', {
                  gender: record.gender
                    ? t(`personal.gender${record.gender === 'male' ? 'Male' : 'Female'}`)
                    : t('personal.genderUnset'),
                  age: record.age_years === null ? '—' : record.age_years,
                })
              }}
              · {{ record.timezone }}
            </p>
            <p v-if="record.body_fat_percent !== null" class="text-sm font-medium">
              {{
                t('personal.caliper.result', { value: formatAmount(record.body_fat_percent, 1) })
              }}
            </p>
            <Alert
              v-else-if="record.calculation.status === 'incomplete'"
              variant="warning"
              data-testid="caliper-incomplete"
            >
              <AlertTitle>{{ t('personal.caliper.missingTitle') }}</AlertTitle>
              <AlertDescription class="space-y-2">
                <p>{{ t('personal.caliper.missingNotice', { fields: missing(record) }) }}</p>
                <template v-if="caliperCompletion(record, profile).metadata.length">
                  <p>
                    {{
                      t(
                        caliperCompletion(record, profile).available
                          ? 'personal.caliper.readyProfileHint'
                          : 'personal.caliper.missingProfileHint',
                      )
                    }}
                  </p>
                  <div class="flex flex-wrap gap-x-4 gap-y-2">
                    <RouterLink
                      v-for="field in caliperCompletion(record, profile).metadata"
                      :key="field"
                      :to="profileLinks[field === 'age_years' ? 'birth' : 'gender']"
                      class="text-primary underline underline-offset-4"
                      @click="
                        capturePersonalReturn(
                          profileLinks[field === 'age_years' ? 'birth' : 'gender'],
                        )
                      "
                      >{{
                        t(
                          field === 'age_years'
                            ? 'personal.caliper.profileBirthAction'
                            : 'personal.caliper.profileGenderAction',
                        )
                      }}</RouterLink
                    >
                  </div>
                  <template v-if="canWrite && caliperCompletion(record, profile).available">
                    <p>{{ t('personal.caliper.completionHint') }}</p>
                    <Button size="sm" :disabled="busy" @click="complete(record)">
                      {{
                        t(
                          caliperCompletion(record, profile).missingFolds.length
                            ? 'personal.caliper.completeMetadata'
                            : 'personal.caliper.complete',
                        )
                      }}
                    </Button>
                  </template>
                </template>
                <template v-if="missingFolds(record)">
                  <p>{{ t('personal.caliper.missingFoldsHint') }}</p>
                  <Button v-if="canWrite" variant="outline" size="sm" @click="open = true">
                    {{ t('personal.caliper.addFull') }}
                  </Button>
                </template>
              </AlertDescription>
            </Alert>
            <p v-else class="text-muted-foreground text-sm">
              {{
                record.calculation.reason === 'age_outside_study'
                  ? t('personal.caliper.ageUnsupported')
                  : t('personal.caliper.unsupported')
              }}
            </p>
            <p v-if="record.note" class="text-muted-foreground text-sm break-words">
              {{ record.note }}
            </p>
          </li>
        </ul>
        <Button v-if="records.length > 3" variant="ghost" size="sm" @click="expanded = !expanded">
          {{
            expanded
              ? t('personal.caliper.less')
              : t('personal.caliper.more', { count: records.length })
          }}
        </Button>
        <p v-if="history.data.value.truncated" class="text-muted-foreground text-sm">
          {{ t('personal.caliper.truncated', { count: history.data.value.limit }) }}
        </p>
      </div>
    </template>
    <div
      v-if="deleting"
      class="border-destructive/30 space-y-3 rounded-2xl border p-4"
      role="alert"
      data-testid="caliper-delete-confirm"
    >
      <p class="text-sm">
        {{ t('personal.caliper.deleteConfirm', { when: measuredLabel(deleting) }) }}
      </p>
      <div class="flex gap-2">
        <Button variant="destructive" :disabled="busy" @click="remove">{{
          t('personal.caliper.delete')
        }}</Button>
        <Button variant="ghost" :disabled="busy" @click="deleting = null">{{
          t('common.cancel')
        }}</Button>
      </div>
    </div>
    <div class="border-t pt-3"><CaliperMethod :gender="profile.gender" /></div>
    <CaliperPanel v-model:open="open" :profile="profile" :day="day" :today="today" />
  </section>
</template>
