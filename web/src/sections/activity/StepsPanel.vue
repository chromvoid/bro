<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed, nextTick, ref, shallowRef, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { isIsoDay } from '@/core/dates'
import { usePersonal } from '@/sections/personal/usePersonal'
import { StepsReply, type StepsOverride } from './api'

const props = defineProps<{ today: string; initialDate?: string }>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const personal = usePersonal()
const formId = useId()
function startingDate() {
  return isIsoDay(props.initialDate) && props.initialDate <= props.today
    ? props.initialDate
    : props.today
}
const date = ref(startingDate())
const value = ref('')
const error = ref('')
const busy = ref(false)
const loadedDate = ref('')
const previous = shallowRef<StepsOverride | null>(null)
const query = useQuery({
  queryKey: computed(() => ['personal', 'steps-override', date.value]),
  enabled: computed(() => open.value && isIsoDay(date.value)),
  queryFn: async ({ signal }) =>
    StepsReply.parse(
      await api.get(`/v1/personal/steps?date=${encodeURIComponent(date.value)}`, signal),
    ),
})
let loadEpoch = 0
async function refreshDay() {
  const epoch = ++loadEpoch
  loadedDate.value = ''
  error.value = ''
  if (!open.value || !isIsoDay(date.value)) return
  const selectedDate = date.value
  await nextTick()
  const result = await query.refetch()
  if (
    epoch !== loadEpoch ||
    !open.value ||
    selectedDate !== date.value ||
    result.isError ||
    result.data?.date !== selectedDate
  )
    return
  const override = result.data.override
  previous.value = override ? { ...override } : null
  value.value = override?.active ? String(override.value ?? '') : ''
  loadedDate.value = selectedDate
}
watch(open, (shown) => {
  if (shown) date.value = startingDate()
})
watch(
  [open, date],
  () => {
    void refreshDay()
  },
  { immediate: true },
)

let pendingWrite = false
function completed() {
  if (!pendingWrite) return
  pendingWrite = false
  open.value = false
}
personal.onResolved(completed)
async function write(clear = false) {
  if (
    busy.value ||
    personal.unresolved.value.length ||
    !query.data.value ||
    loadedDate.value !== date.value
  )
    return
  if (
    !isIsoDay(date.value) ||
    date.value > props.today ||
    (!clear && (!/^\d+$/.test(value.value) || Number(value.value) > 1_000_000))
  ) {
    error.value = t('activity.stepsRange')
    return
  }
  busy.value = true
  pendingWrite = true
  error.value = ''
  try {
    await personal.operate(
      clear ? 'steps.clear' : 'steps.set',
      { date: date.value, ...(clear ? {} : { value: Number(value.value) }) },
      previous.value ? { [previous.value.id]: previous.value.revision } : {},
    )
    toast.success(t(clear ? 'activity.stepsCleared' : 'activity.stepsSaved'))
    completed()
  } catch (failure) {
    personal.failed(failure)
  } finally {
    busy.value = false
  }
}
async function recover(key: string, resend = false) {
  const entry = await (resend ? personal.resend(key) : personal.check(key))
  if (entry?.state === 'resolved') open.value = false
}
</script>

<template>
  <Panel v-model:open="open" :title="t('activity.manualSteps')">
    <UnresolvedWrites
      :entries="personal.unresolved.value"
      :describe="personal.describe"
      :check="(key) => recover(key)"
      :resend="(key) => recover(key, true)"
      :abandon="personal.abandon"
    />
    <form :id="formId" class="space-y-4" novalidate @submit.prevent="write()">
      <div class="space-y-2">
        <Label for="steps-date">{{ t('activity.sessionDate') }}</Label
        ><Input id="steps-date" v-model="date" type="date" :max="today" />
      </div>
      <RefreshFailed :query="query" />
      <ErrorState
        v-if="query.isLoadingError.value || (loadedDate !== date && query.isRefetchError.value)"
        :error="query.error.value"
        @retry="refreshDay()"
      />
      <LoadingState v-else-if="query.isPending.value || loadedDate !== date" :rows="1" />
      <template v-else-if="query.data.value">
        <div class="space-y-2">
          <Label for="steps-value">{{ t('activity.stepsTotal') }}</Label
          ><Input id="steps-value" v-model="value" inputmode="numeric" autocomplete="off" />
        </div>
        <p class="text-muted-foreground text-sm">{{ t('activity.stepsHint') }}</p>
        <p v-if="error" class="text-destructive text-sm" role="alert">{{ error }}</p>
        <p v-if="personal.unresolved.value.length" class="text-warning text-sm">
          {{ t('activity.checkWriteFirst') }}
        </p>
      </template>
    </form>
    <template #footer>
      <div class="grid w-full gap-2">
        <Button
          type="submit"
          :form="formId"
          class="min-h-11 w-full"
          :disabled="busy || !!personal.unresolved.value.length || loadedDate !== date"
          >{{ t('common.save') }}</Button
        >
        <Button
          v-if="previous?.active && loadedDate === date"
          variant="outline"
          class="min-h-11 w-full"
          :disabled="busy || !!personal.unresolved.value.length"
          @click="write(true)"
          >{{ t('activity.clearSteps') }}</Button
        >
      </div>
    </template>
  </Panel>
</template>
