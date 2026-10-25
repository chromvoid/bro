<script setup lang="ts">
import { BellRing, ChevronDown, CircleCheck, Clock, Info, TriangleAlert } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import { Button } from '@/components/ui/button'
import { dayIn, momentLabel, momentPhrase, timeIn } from '@/core/dates'
import { errorText } from '@/core/errors'
import { cn } from '@/lib/utils'
import { useSession } from '@/stores/session'
import { appLink, nextMorning, type Notification } from './model'
import { useNotifications } from './useNotifications'

/**
 * One notification with what can be done about it; in the conversation it reads as the assistant's
 * line. A solved one is a grey line, in the list and in the conversation alike: its title, when it
 * was solved, its text one tap away and nothing left to do.
 */
const props = defineProps<{ notification: Notification; feed?: boolean }>()

const { t } = useI18n()
const router = useRouter()
const session = useSession()
const { read, snooze } = useNotifications()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const busy = ref(false)
const link = computed(() => appLink(props.notification.link))
const icon = computed(
  () => ({ info: Info, important: TriangleAlert, urgent: BellRing })[props.notification.severity],
)
const snoozedUntil = computed(() => {
  const until = props.notification.snoozed_until
  return until && new Date(until) > new Date() ? until : null
})
const active = computed(() => props.notification.state === 'active')
const collapsed = computed(() => !active.value)
const expanded = ref(false)
// «решено сегодня, 02:59»: the moment reads mid-sentence, so in lower case.
const resolvedWhen = computed(() => {
  const when = momentLabel(
    props.notification.resolved_at ?? props.notification.updated_at,
    zone.value,
    dayIn(zone.value),
  )
  return when.charAt(0).toLocaleLowerCase('ru-RU') + when.slice(1)
})

async function act(task: () => Promise<unknown>) {
  busy.value = true
  try {
    await task()
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

function open() {
  const target = link.value
  if (!target) return
  void act(async () => {
    if (props.notification.unread) await read([props.notification.id])
    await router.push(target)
  })
}

function later(minutes: number | null) {
  const until = minutes === null ? nextMorning(zone.value) : null
  void act(() => snooze(props.notification.id, minutes === null ? { until: until! } : { minutes }))
}
</script>

<template>
  <article
    v-if="collapsed"
    :class="cn('bg-card/60 border', feed ? 'rounded-2xl rounded-tl-md' : 'rounded-2xl')"
    :aria-label="t('notifications.one')"
    data-testid="notification"
    data-state="resolved"
  >
    <button
      type="button"
      class="text-muted-foreground hover:text-foreground flex min-h-11 w-full items-center gap-3 px-4 py-2 text-left text-sm"
      :aria-expanded="expanded"
      :aria-label="t('notifications.details', { title: notification.title })"
      @click="expanded = !expanded"
    >
      <CircleCheck class="text-success size-4 shrink-0" aria-hidden="true" />
      <!-- The title keeps the row's width; when it was solved goes under it. -->
      <span class="min-w-0 flex-1">
        <span class="text-foreground block truncate">{{ notification.title }}</span>
        <span class="block text-xs">{{
          t('notifications.resolvedAt', { when: resolvedWhen })
        }}</span>
      </span>
      <ChevronDown
        :class="cn('size-4 shrink-0 transition-transform', expanded && 'rotate-180')"
        aria-hidden="true"
      />
    </button>
    <div v-if="expanded" class="space-y-2 px-4 pb-3 text-sm">
      <p v-if="notification.body" class="break-words whitespace-pre-wrap">
        {{ notification.body }}
      </p>
      <p class="text-muted-foreground">
        {{ notification.resolution || t('notifications.resolved') }}
      </p>
    </div>
  </article>
  <article
    v-else
    :class="
      cn(
        'bg-card space-y-3 border p-4',
        feed ? 'rounded-2xl rounded-tl-md' : 'rounded-2xl',
        notification.unread && 'border-primary/40',
        notification.severity === 'urgent' && notification.unread && 'border-warning',
      )
    "
    :aria-label="t('notifications.one')"
    data-testid="notification"
  >
    <header class="flex items-start gap-3">
      <component
        :is="icon"
        :class="
          cn(
            'mt-0.5 size-5 shrink-0',
            notification.severity === 'info' ? 'text-muted-foreground' : 'text-warning',
          )
        "
        aria-hidden="true"
      />
      <div class="min-w-0 flex-1 space-y-1">
        <p v-if="feed" class="text-muted-foreground text-xs font-medium">
          {{ t('notifications.one') }}
        </p>
        <h3 class="leading-snug font-semibold break-words">
          {{ notification.title }}
          <span v-if="notification.count > 1" class="text-muted-foreground font-normal">
            · {{ t('notifications.times', { count: notification.count }) }}</span
          >
        </h3>
        <p v-if="notification.body" class="break-words whitespace-pre-wrap">
          {{ notification.body }}
        </p>
        <p class="text-muted-foreground text-xs tabular-nums">
          <time :datetime="notification.updated_at">{{
            feed
              ? timeIn(zone, notification.updated_at)
              : momentLabel(notification.updated_at, zone, dayIn(zone))
          }}</time>
          <span v-if="notification.unread" class="text-primary ms-2 font-medium">{{
            t('notifications.new')
          }}</span>
        </p>
      </div>
    </header>

    <p v-if="snoozedUntil" class="text-muted-foreground flex items-center gap-2 text-sm">
      <Clock class="size-4 shrink-0" />
      {{ t('notifications.snoozedUntil', { when: momentPhrase(snoozedUntil, zone, dayIn(zone)) }) }}
    </p>

    <!-- In the conversation a line that was read is just a line; the list keeps every action. -->
    <div
      v-if="feed ? notification.unread : link || notification.unread || active"
      class="flex flex-wrap gap-2"
    >
      <Button v-if="link" size="sm" variant="outline" :disabled="busy" @click="open">
        {{ t('notifications.open') }}
      </Button>
      <Button
        v-if="notification.unread"
        size="sm"
        variant="outline"
        :disabled="busy"
        data-testid="notification-read"
        @click="act(() => read([notification.id]))"
      >
        {{ t('notifications.markRead') }}
      </Button>
      <template v-if="active && !snoozedUntil">
        <Button size="sm" variant="ghost" :disabled="busy" @click="later(60)">
          {{ t('notifications.laterHour') }}
        </Button>
        <Button size="sm" variant="ghost" :disabled="busy" @click="later(null)">
          {{ t('notifications.laterMorning') }}
        </Button>
      </template>
    </div>
  </article>
</template>
