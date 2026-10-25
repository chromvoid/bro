<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { BellRing, X } from '@lucide/vue'
import { computed, nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { dayIn, momentLabel, zonedInput, zonedInstant } from '@/core/dates'
import { errorText } from '@/core/errors'
import { useLedger } from '@/core/operations'
import { useUndo } from '@/core/undo'
import { useSession } from '@/stores/session'
import { Reminders, TIMERS, type Reminder, type Task } from './api'
import { reminderPresets } from './reminders'

/**
 * A task's reminders, set right here. A reminder is the person's own (timers.write), so seeing
 * the task is enough; it stays silent while the task is done or in the trash and comes if the
 * task is reopened before its moment.
 */
const props = defineProps<{ task: Task }>()
const { t } = useI18n()
const session = useSession()
const ledger = useLedger(TIMERS)
const undoable = useUndo()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const canWrite = computed(() => session.can('timers.write'))
const closed = computed(() => props.task.status === 'done' || !!props.task.deleted_at)
const scheduled = useQuery({
  queryKey: ['timers', 'reminders'],
  enabled: computed(() => session.can('timers.read')),
  queryFn: async ({ signal }) => Reminders.parse(await api.get('/v1/timers/reminders', signal)),
})
const mine = computed(
  () => scheduled.data.value?.reminders.filter((item) => item.task_id === props.task.id) ?? [],
)
const when = (instant: string) => momentLabel(instant, zone.value, dayIn(zone.value))

const choosing = ref(false)
const presets = computed(() => (choosing.value ? reminderPresets(new Date(), zone.value) : []))
const other = ref('')
const busy = ref(false)
async function choose() {
  other.value = zonedInput(new Date(Date.now() + 2 * 60 * 60_000), zone.value)
  choosing.value = true
  await nextTick()
  document.querySelector<HTMLElement>('[data-reminder-preset]')?.focus()
}

async function create(at: string) {
  await ledger.operate({
    kind: 'reminder.create',
    payload: { at, text: props.task.title.slice(0, 200), task_id: props.task.id },
  })
}

async function set(at: string | null) {
  if (!at || busy.value) return
  if (Date.parse(at) <= Date.now()) return void toast.error(t('tasks.reminderPast'))
  busy.value = true
  try {
    await create(at)
    choosing.value = false
    toast.success(t('tasks.remindedAt', { when: when(at) }))
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

async function cancel(reminder: Reminder) {
  try {
    await ledger.operate({
      kind: 'reminder.cancel',
      payload: { reminder_ids: [reminder.id] },
      expected_revisions: { [reminder.id]: reminder.revision },
    })
    // Undo sets it again, still the task's, while its moment is ahead.
    undoable.confirm(t('tasks.reminderCancelled'), async () => {
      if (Date.parse(reminder.due_at) <= Date.now()) return false
      await create(reminder.due_at)
    })
  } catch (error) {
    toast.error(errorText(error, t))
  }
}
</script>

<template>
  <section
    v-if="canWrite || mine.length"
    class="space-y-2"
    :aria-labelledby="mine.length ? 'task-reminder-title' : undefined"
    data-testid="task-reminder"
  >
    <!-- A heading only over reminders that exist; otherwise the «Напомнить» button says it all. -->
    <h3 v-if="mine.length" id="task-reminder-title" class="flex items-center gap-2 font-medium">
      <BellRing class="size-4" />{{ t('tasks.reminder') }}
    </h3>
    <ul v-if="mine.length" class="divide-y rounded-2xl border">
      <li
        v-for="reminder in mine"
        :key="reminder.id"
        class="flex items-center gap-2 py-1 pr-1 pl-3"
      >
        <span class="min-w-0 flex-1 text-sm tabular-nums">{{ when(reminder.due_at) }}</span>
        <Button
          v-if="canWrite"
          variant="ghost"
          size="icon-sm"
          :aria-label="t('tasks.reminderCancel')"
          @click="cancel(reminder)"
          ><X
        /></Button>
      </li>
    </ul>
    <p v-if="closed && mine.length" class="text-muted-foreground text-sm">
      {{ t('tasks.remindSilent') }}
    </p>
    <template v-if="canWrite && !closed">
      <Button v-if="!choosing" variant="outline" data-testid="task-remind" @click="choose"
        ><BellRing />{{ t('tasks.remindAbout') }}</Button
      >
      <div v-else class="space-y-2 rounded-2xl border p-3">
        <div class="flex flex-wrap gap-2">
          <Button
            v-for="preset in presets"
            :key="preset.key"
            size="sm"
            variant="secondary"
            data-reminder-preset
            :disabled="busy"
            @click="set(preset.at)"
            >{{ t(`tasks.remindPreset.${preset.key}`) }}</Button
          >
        </div>
        <form
          class="flex flex-wrap items-end gap-2"
          @submit.prevent="set(zonedInstant(other, zone))"
        >
          <div class="min-w-44 flex-1 space-y-1">
            <Label for="task-reminder-at">{{ t('tasks.remindOther') }}</Label>
            <Input id="task-reminder-at" v-model="other" type="datetime-local" />
          </div>
          <Button type="submit" size="sm" :disabled="busy || !other">{{
            t('tasks.remindSet')
          }}</Button>
          <Button type="button" size="sm" variant="ghost" @click="choosing = false">{{
            t('common.cancel')
          }}</Button>
        </form>
      </div>
    </template>
  </section>
</template>
