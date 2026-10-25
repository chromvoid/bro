<script setup lang="ts">
import { reactive, shallowRef, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { isIsoDay, zonedInput, zonedInstant } from '@/core/dates'
import { parseAmount } from '@/core/numbers'
import { KINDS, type ActivityKind, type ActivitySession } from './api'
import { useActivity } from './useActivity'

const props = defineProps<{
  session?: ActivitySession
  today: string
  timezone: string
  initialDate?: string
  initialKind?: ActivityKind
}>()
const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{ saved: [] }>()
const { t } = useI18n()
const activity = useActivity()
const formId = useId()
const target = shallowRef<{ id: string; revision: number; timezone: string } | null>(null)
const form = reactive({
  kind: 'walking' as ActivityKind,
  title: '',
  date: '',
  time: '',
  duration: '',
  distance: '',
  note: '',
  error: '',
  busy: false,
})
watch(
  open,
  (shown) => {
    if (!shown) return
    const item = props.session
    target.value = item ? { id: item.id, revision: item.revision, timezone: item.timezone } : null
    Object.assign(form, {
      kind: item?.kind ?? props.initialKind ?? 'walking',
      title: item?.title ?? '',
      date:
        item?.date ??
        (isIsoDay(props.initialDate) && props.initialDate <= props.today
          ? props.initialDate
          : props.today),
      time: item?.started_at ? zonedInput(item.started_at, item.timezone).slice(11) : '',
      duration: item?.duration_minutes ?? '',
      distance: item?.distance_km ?? '',
      note: item?.note ?? '',
      error: '',
    })
  },
  { immediate: true },
)
let pendingSave = false
function completed() {
  if (!pendingSave) return
  pendingSave = false
  open.value = false
  emit('saved')
}
activity.onResolved(completed)
async function save() {
  if (form.busy || activity.unresolved.value.length) return
  const duration = form.duration.trim() ? parseAmount(form.duration) : null
  const distance = form.distance.trim() ? parseAmount(form.distance) : null
  const timezone = target.value?.timezone ?? props.timezone
  const startedAt = form.time ? zonedInstant(`${form.date}T${form.time}`, timezone) : null
  if (
    !isIsoDay(form.date) ||
    form.date > props.today ||
    (form.time && !startedAt) ||
    (form.duration.trim() && duration === null) ||
    (form.distance.trim() && distance === null)
  ) {
    form.error = t('activity.invalidSession')
    return
  }
  form.error = ''
  form.busy = true
  pendingSave = true
  try {
    const payload = {
      kind: form.kind,
      title: form.title.trim(),
      date: form.date,
      timezone,
      started_at: startedAt,
      duration_minutes: duration,
      distance_km: distance,
      note: form.note.trim(),
    }
    if (target.value)
      await activity.operate(
        'session.update',
        { ...payload, session_id: target.value.id },
        { [target.value.id]: target.value.revision },
      )
    else await activity.operate('session.add', payload)
    toast.success(t('activity.saved'))
    completed()
  } catch (error) {
    activity.failed(error)
  } finally {
    form.busy = false
  }
}
async function recover(key: string, resend = false) {
  const entry = await (resend ? activity.resend(key) : activity.check(key))
  if (entry?.state === 'resolved') {
    completed()
    if (open.value) {
      open.value = false
      emit('saved')
    }
  }
}
</script>

<template>
  <Panel v-model:open="open" :title="t(session ? 'activity.editSession' : 'activity.addSession')">
    <UnresolvedWrites
      :entries="activity.unresolved.value"
      :describe="activity.describe"
      :check="(key) => recover(key)"
      :resend="(key) => recover(key, true)"
      :abandon="activity.abandon"
    />
    <form :id="formId" class="space-y-4" novalidate @submit.prevent="save">
      <div class="space-y-2">
        <Label for="activity-kind">{{ t('activity.kind') }}</Label>
        <NativeSelect
          id="activity-kind"
          v-model="form.kind"
          :disabled="!!session?.exercises.length"
        >
          <option v-for="kind in KINDS" :key="kind" :value="kind">
            {{ t(`activity.kinds.${kind}`) }}
          </option>
        </NativeSelect>
      </div>
      <div class="space-y-2">
        <Label for="activity-title">{{
          t(form.kind === 'other' ? 'activity.customName' : 'activity.sessionTitle')
        }}</Label>
        <Input id="activity-title" v-model="form.title" maxlength="200" />
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div class="space-y-2">
          <Label for="activity-date">{{ t('activity.sessionDate') }}</Label
          ><Input id="activity-date" v-model="form.date" type="date" :max="today" required />
        </div>
        <div class="space-y-2">
          <Label for="activity-time">{{ t('activity.sessionTime') }}</Label
          ><Input id="activity-time" v-model="form.time" type="time" />
        </div>
      </div>
      <p class="text-muted-foreground text-xs">
        {{ t('personal.zoneHint', { zone: session?.timezone ?? timezone }) }}
      </p>
      <div class="grid grid-cols-2 gap-3">
        <div class="space-y-2">
          <Label for="activity-duration">{{ t('activity.duration') }}</Label
          ><Input
            id="activity-duration"
            v-model="form.duration"
            inputmode="decimal"
            :placeholder="t('activity.unknown')"
          />
        </div>
        <div class="space-y-2">
          <Label for="activity-distance">{{ t('activity.distance') }}</Label
          ><Input
            id="activity-distance"
            v-model="form.distance"
            inputmode="decimal"
            :placeholder="t('activity.unknown')"
          />
        </div>
      </div>
      <div class="space-y-2">
        <Label for="activity-note">{{ t('activity.note') }}</Label
        ><Textarea id="activity-note" v-model="form.note" maxlength="4000" />
      </div>
      <p v-if="form.error" class="text-destructive text-sm" role="alert">{{ form.error }}</p>
      <p v-if="activity.unresolved.value.length" class="text-warning text-sm" role="status">
        {{ t('activity.checkWriteFirst') }}
      </p>
    </form>
    <template #footer>
      <Button
        class="min-h-11 w-full"
        type="submit"
        :form="formId"
        :disabled="form.busy || !!activity.unresolved.value.length"
        >{{ t('common.save') }}</Button
      >
    </template>
  </Panel>
</template>
