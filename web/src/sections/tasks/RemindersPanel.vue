<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { BellRing, X } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import ErrorState from '@/components/state/ErrorState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { dayIn, momentLabel, zonedInput, zonedInstant } from '@/core/dates'
import { errorText } from '@/core/errors'
import type { useLedger } from '@/core/operations'
import { useUndo } from '@/core/undo'
import { useSession } from '@/stores/session'
import { Reminders, type Reminder } from './api'

/**
 * Reminders at a moment: the server tells the person when it comes (docs/notifications-spec.md).
 * One set from a task is that task's and leads back to it.
 */
const props = defineProps<{ ledger: ReturnType<typeof useLedger> }>()

const { t } = useI18n()
const session = useSession()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const canWrite = computed(() => session.can('timers.write'))
const scheduled = useQuery({
  queryKey: ['timers', 'reminders'],
  queryFn: async ({ signal }) => Reminders.parse(await api.get('/v1/timers/reminders', signal)),
})
const reminders = computed(() => scheduled.data.value?.reminders ?? [])

const text = ref('')
const at = ref(zonedInput(new Date(Date.now() + 60 * 60_000), zone.value))
const busy = ref(false)
const undoable = useUndo()

async function remind() {
  const moment = zonedInstant(at.value, zone.value)
  if (!text.value.trim() || !moment || busy.value) return
  if (Date.parse(moment) <= Date.now()) return void toast.error(t('tasks.reminderPast'))
  busy.value = true
  try {
    await props.ledger.operate({
      kind: 'reminder.create',
      payload: { at: moment, text: text.value.trim() },
    })
    text.value = ''
    toast.success(t('tasks.reminded'))
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

/** Undo sets the same reminder again while its moment is still ahead. */
async function restore(reminder: Reminder) {
  if (Date.parse(reminder.due_at) <= Date.now()) return false
  await props.ledger.operate({
    kind: 'reminder.create',
    payload: {
      at: reminder.due_at,
      text: reminder.text,
      ...(reminder.task_id ? { task_id: reminder.task_id } : {}),
    },
  })
}

async function cancel(reminder: Reminder) {
  try {
    await props.ledger.operate({
      kind: 'reminder.cancel',
      payload: { reminder_ids: [reminder.id] },
      expected_revisions: { [reminder.id]: reminder.revision },
    })
    undoable.confirm(t('tasks.reminderCancelled'), () => restore(reminder))
  } catch (error) {
    toast.error(errorText(error, t))
  }
}
</script>

<template>
  <section class="space-y-3" aria-labelledby="reminders-title">
    <h2 id="reminders-title" class="flex items-center gap-2 font-semibold">
      <BellRing class="size-5" />{{ t('tasks.reminders') }}
    </h2>
    <RefreshFailed :query="scheduled" />
    <ErrorState
      v-if="scheduled.isLoadingError.value"
      :error="scheduled.error.value"
      @retry="scheduled.refetch()"
    />
    <p v-else-if="scheduled.data.value && !reminders.length" class="text-muted-foreground">
      {{ t('tasks.remindersEmpty') }}
    </p>
    <ul v-else class="bg-card divide-y rounded-2xl border" data-testid="reminders">
      <li
        v-for="reminder in reminders"
        :key="reminder.id"
        class="flex items-center gap-3 px-4 py-3"
      >
        <div class="min-w-0 flex-1">
          <p class="font-medium break-words">{{ reminder.text }}</p>
          <p class="text-muted-foreground text-sm tabular-nums">
            {{ momentLabel(reminder.due_at, zone, dayIn(zone)) }}
            <RouterLink
              v-if="reminder.task_id"
              :to="{ name: 'tasks', query: { view: 'open', task: reminder.task_id } }"
              class="text-primary ms-1 underline-offset-4 hover:underline"
              >{{ t('tasks.reminderOfTask') }}</RouterLink
            >
          </p>
        </div>
        <Button
          v-if="canWrite"
          variant="ghost"
          size="icon"
          :aria-label="t('tasks.reminderCancel')"
          @click="cancel(reminder)"
        >
          <X class="size-5" />
        </Button>
      </li>
    </ul>
    <!-- At large text the date field is wider than a phone: the column keeps the card's width
         (grid-cols-1, not an auto track sized by the field) and the field clips its own value. -->
    <form
      v-if="canWrite"
      class="bg-card grid grid-cols-1 gap-3 rounded-3xl border p-5 sm:grid-cols-[1fr_14rem]"
      @submit.prevent="remind"
    >
      <div class="space-y-2">
        <Label for="reminder-text">{{ t('tasks.reminderText') }}</Label>
        <Input id="reminder-text" v-model="text" maxlength="200" />
      </div>
      <div class="space-y-2">
        <Label for="reminder-at">{{ t('tasks.reminderAt') }}</Label>
        <Input id="reminder-at" v-model="at" type="datetime-local" />
      </div>
      <Button type="submit" class="sm:col-span-2" :disabled="busy || !text.trim()">
        {{ t('tasks.remind') }}
      </Button>
      <p class="text-muted-foreground text-sm sm:col-span-2">{{ t('tasks.remindersText') }}</p>
    </form>
  </section>
</template>
