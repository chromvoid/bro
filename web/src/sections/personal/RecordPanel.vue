<script setup lang="ts">
import { useDisplayUnits } from './displayUnits'
import { computed, nextTick, reactive, ref, shallowRef, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { momentLabel, zonedInput, zonedInstant } from '@/core/dates'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { parseAmount } from '@/core/numbers'
import messages from '@/locales/personal-forms-ru.json'
import { useSession } from '@/stores/session'
import { amountText, isInstant, observationOf, provenance, type Observation } from './api'
import { usePersonal } from './usePersonal'
import SleepSessions from './SleepSessions.vue'
import RecordDetails from './RecordDetails.vue'

/**
 * P07: a correction overrides the value and time but never hides where the record came from;
 * the original stays visible and the correction can be reset. Excluding keeps the record too.
 */
const displayUnits = useDisplayUnits()
const props = defineProps<{ record: Observation | null; today: string }>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: messages },
  missingWarn: false,
  fallbackWarn: false,
})
const session = useSession()
const personal = usePersonal()
const canWrite = computed(() => session.can('personal.write'))
const zone = computed(() => session.me?.timezone ?? 'UTC')
const formId = `record-form-${useId()}`
const fieldId = (field: string) => `${formId}-${field}`
const element = ref<HTMLFormElement | null>(null)
const editingRecord = shallowRef<Observation | null>(null)
const form = reactive({ value: '', start: '', end: '', busy: false, error: '' })
type Field = 'value' | 'start' | 'end'
const errors = reactive<Record<Field, string>>({ value: '', start: '', end: '' })
watch(
  [() => open.value, () => props.record?.id],
  ([visible]) => {
    if (!visible) return
    const record = props.record
    if (!record) {
      editingRecord.value = null
      return
    }
    // A refresh of this same record must not replace an unfinished correction or its revision.
    editingRecord.value = { ...record, data: { ...record.data } }
    Object.assign(form, {
      value: (record.data.value ?? '').replace('.', ','),
      start: zonedInput(record.start_at, zone.value),
      end: zonedInput(record.end_at, zone.value),
      error: '',
    })
    Object.assign(errors, { value: '', start: '', end: '' })
  },
  { immediate: true },
)
const instant = computed(() => !!editingRecord.value && isInstant(editingRecord.value.kind))
const sleepSessions = computed(() => {
  const record = editingRecord.value
  return record?.kind === 'sleep'
    ? [
        {
          ...record,
          contribution: record.data.value,
          asleep_minutes: record.data.value,
          stages: record.data.stages ?? [],
          position_changes: record.data.position_changes ?? [],
        },
      ]
    : []
})

async function correct(changes: { observation?: unknown; excluded?: boolean }, message: string) {
  const record = editingRecord.value
  if (!record || form.busy || !canWrite.value) return
  form.error = ''
  form.busy = true
  try {
    await personal.operate(
      'record.correct',
      {
        record_id: record.id,
        override_revision: record.override_revision ?? 0,
        // The override is replaced as a whole: keep what is not being changed.
        observation: record.corrected ? observationOf(record) : null,
        excluded: !!record.excluded,
        ...changes,
      },
      { [record.id]: record.revision },
    )
    toast.success(message)
    open.value = false
  } catch (error) {
    form.error =
      error instanceof ApiError && error.status === 409
        ? t('personal.conflict')
        : errorText(error, t)
    personal.failed(error)
  } finally {
    form.busy = false
  }
}

async function save() {
  const record = editingRecord.value
  if (!record || form.busy || !canWrite.value) return
  form.error = ''
  Object.assign(errors, { value: '', start: '', end: '' })
  // Only sleep may lack a value: a source can report the period without its length.
  const typed = form.value.trim()
  const value = typed ? parseAmount(typed) : null
  const start = zonedInstant(form.start, zone.value)
  const end = instant.value ? start : zonedInstant(form.end, zone.value)
  if ((typed && value === null) || (!typed && record.kind !== 'sleep')) {
    errors.value = t('personal.valueRequired')
  }
  if (!start) errors.start = t('personal.timeRequired')
  if (!instant.value && (!end || (start && Date.parse(end) < Date.parse(start)))) {
    errors.end = t('personal.timeRequired')
  }
  const invalid = (['value', 'start', 'end'] as const).find((field) => errors[field])
  if (invalid) {
    await nextTick()
    const target = element.value?.querySelector<HTMLElement>(`[data-testid="record-${invalid}"]`)
    target?.focus({ preventScroll: true })
    target?.scrollIntoView?.({ block: 'nearest' })
    return
  }
  await correct(
    { observation: observationOf(record, { value, start_at: start!, end_at: end! }) },
    t('personal.corrected'),
  )
}
</script>

<template>
  <Panel v-model:open="open" :title="canWrite ? t('personal.correctTitle') : 'Подробности записи'">
    <template v-if="editingRecord">
      <div class="flex flex-wrap items-center gap-2">
        <span class="text-muted-foreground text-sm">
          {{ momentLabel(editingRecord.start_at, zone, today) }} ·
          {{ provenance(editingRecord, (origin) => t(`personal.origin.${origin}`)) }}
        </span>
        <Badge v-if="editingRecord.excluded" variant="outline">{{ t('personal.excluded') }}</Badge>
      </div>
      <p
        v-if="editingRecord.corrected && editingRecord.original"
        class="text-muted-foreground text-sm"
      >
        {{
          t('personal.originalValue', {
            value:
              amountText(editingRecord.kind, editingRecord.original.data.value, displayUnits) ??
              t('personal.noValue'),
          })
        }}
      </p>

      <RecordDetails :record="editingRecord" :timezone="zone" :today="today" />
      <form
        v-if="canWrite"
        :id="formId"
        ref="element"
        class="space-y-4"
        novalidate
        @submit.prevent="save"
      >
        <fieldset class="space-y-4" :disabled="form.busy">
          <div class="space-y-2">
            <Label :for="fieldId('value')">{{ t(`personal.valueOf.${editingRecord.kind}`) }}</Label>
            <Input
              :id="fieldId('value')"
              v-model="form.value"
              data-testid="record-value"
              inputmode="decimal"
              autocomplete="off"
              :aria-invalid="!!errors.value"
              :aria-describedby="errors.value ? fieldId('value-error') : undefined"
            />
            <p
              v-if="errors.value"
              :id="fieldId('value-error')"
              class="text-destructive text-sm"
              role="alert"
            >
              {{ errors.value }}
            </p>
          </div>
          <div
            data-testid="record-times"
            :class="instant ? '' : 'grid grid-cols-1 gap-3 sm:grid-cols-2'"
          >
            <div class="space-y-2">
              <Label :for="fieldId('start')">{{
                instant ? t('personal.measuredAt') : t('personal.startedAt')
              }}</Label>
              <Input
                :id="fieldId('start')"
                v-model="form.start"
                data-testid="record-start"
                type="datetime-local"
                :aria-invalid="!!errors.start"
                :aria-describedby="
                  [fieldId('time-hint'), errors.start ? fieldId('start-error') : '']
                    .filter(Boolean)
                    .join(' ')
                "
              />
              <p
                v-if="errors.start"
                :id="fieldId('start-error')"
                class="text-destructive text-sm"
                role="alert"
              >
                {{ errors.start }}
              </p>
            </div>
            <div v-if="!instant" class="space-y-2">
              <Label :for="fieldId('end')">{{ t('personal.endedAt') }}</Label>
              <Input
                :id="fieldId('end')"
                v-model="form.end"
                data-testid="record-end"
                type="datetime-local"
                :aria-invalid="!!errors.end"
                :aria-describedby="
                  [fieldId('time-hint'), errors.end ? fieldId('end-error') : '']
                    .filter(Boolean)
                    .join(' ')
                "
              />
              <p
                v-if="errors.end"
                :id="fieldId('end-error')"
                class="text-destructive text-sm"
                role="alert"
              >
                {{ errors.end }}
              </p>
            </div>
          </div>
          <p :id="fieldId('time-hint')" class="text-muted-foreground text-sm">
            {{ t('personal.correctHint', { zone }) }}
          </p>
        </fieldset>
        <div v-if="form.error" data-testid="record-server-error" class="space-y-1">
          <p class="text-destructive text-sm" role="alert">{{ form.error }}</p>
          <p class="text-muted-foreground text-sm">{{ t('personalForms.draftKept') }}</p>
        </div>
      </form>

      <SleepSessions
        v-if="sleepSessions.length"
        :sessions="sleepSessions"
        :timezone="zone"
        :today="today"
        detailed
      />

      <div v-if="canWrite" class="grid grid-cols-1 gap-2">
        <Button
          type="button"
          variant="outline"
          :disabled="form.busy"
          @click="
            correct(
              { excluded: !editingRecord.excluded },
              editingRecord.excluded ? t('personal.included') : t('personal.excludedDone'),
            )
          "
        >
          {{ editingRecord.excluded ? t('personal.include') : t('personal.exclude') }}
        </Button>
        <Button
          v-if="editingRecord.corrected"
          type="button"
          variant="ghost"
          :disabled="form.busy"
          @click="correct({ observation: null }, t('personal.resetDone'))"
        >
          {{ t('personal.reset') }}
        </Button>
      </div>
    </template>
    <template v-if="editingRecord && canWrite" #footer>
      <Button
        type="submit"
        :form="formId"
        size="lg"
        class="w-full whitespace-normal"
        :disabled="form.busy"
      >
        {{ form.busy ? t('personalForms.saving') : t('personal.saveCorrection') }}
      </Button>
    </template>
  </Panel>
</template>
