<script setup lang="ts">
import { ArrowUp, CircleAlert, Clock3, CornerDownRight, Pencil, Trash2 } from '@lucide/vue'
import { computed, inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { timeIn } from '@/core/dates'
import { cn } from '@/lib/utils'
import type { Exchange, Reply } from './model'
import { sentPhoto } from './photoUploads'
import ReplyBlock from './ReplyBlock.vue'
import { conversationBusy, exchangeActions } from './busy'
import ActivityPanel from './ActivityPanel.vue'
import ClarificationStatus from './ClarificationStatus.vue'
import type { ClarificationItem } from './clarifications'

const props = defineProps<{
  exchange: Exchange
  zone: string
  /** Text of the reply this message answered, when it is loaded. */
  answered?: string | null
  highlighted?: boolean
  highlightedReplyId?: string | null
  admin?: boolean
  recovered?: boolean
  clarificationChecking?: boolean
  /** The turn of the newest answer in the conversation, which alone shows its actions. */
  latestReply?: string | null
  /** A queued message that «Отправить сейчас» can move into the running request. */
  movable?: boolean
}>()
const emit = defineEmits<{
  answer: [reply: Reply]
  confirm: [reply: Reply, accept: boolean]
  check: [reply: Reply]
  restore: [exchange: Exchange]
  activitySettled: [messageId: string]
  /** Taking a queued message back; the running request is stopped from the composer. */
  control: [exchange: Exchange, action: 'withdraw']
  editQueued: [exchange: Exchange]
  sendNow: [exchange: Exchange]
  checkClarification: [item: ClarificationItem]
  open: [messageId: string]
}>()
const { t, te } = useI18n()
// Read only by the actions an exchange shows, so a send re-renders just those exchanges.
const sending = inject(conversationBusy, ref(false))
const actions = inject(exchangeActions, {
  busy: ref(false),
  controlling: ref(new Set<string>()),
})
const busy = computed(() => actions.busy.value)
// Waiting behind the running request: it looks unsent and has its own actions.
const waiting = computed(() => props.exchange.status === 'pending' && props.exchange.queued)
const controlBusy = computed(
  () => sending.value || actions.controlling.value.has(props.exchange.id),
)
// A photo takes its final box before it loads, at most 240px tall and never wider than the
// bubble: one that grows on loading would push the conversation while the user scrolls up.
function photoSize(image: { width: number; height: number }) {
  const scale = Math.min(1, 240 / image.height)
  return { width: Math.round(image.width * scale), height: Math.round(image.height * scale) }
}
const failure = computed(() => {
  const code = props.exchange.error
  if (code && te(`assistant.failure.${code}`)) return t(`assistant.failure.${code}`)
  if (props.exchange.failureKind === 'response') return t('assistant.responseFailedText')
  if (props.exchange.failureKind === 'unknown') return t('assistant.unknownTurn')
  return t('assistant.failure.default')
})
const effects = computed(() => {
  const unknown =
    props.exchange.failureKind === 'unknown' ||
    props.exchange.replies.some((reply) => reply.unknownOutcome)
  const confirmed = props.exchange.replies.some((reply) => reply.committed)
  return {
    unknown,
    confirmed,
    partial: props.exchange.replies.some((reply) => reply.execution?.completion === 'partial'),
    none:
      !unknown &&
      !confirmed &&
      props.exchange.replies.length > 0 &&
      props.exchange.replies.every((reply) => reply.execution?.effects === 'none'),
  }
})
const unresolved = computed(() => props.exchange.replies.filter((reply) => reply.unknownOutcome))
const repair = computed(() => {
  const code = props.exchange.error
  if (code === 'model_no_account_access') return { name: 'admin-users' }
  if (
    [
      'model_auth_required',
      'model_auth_unavailable',
      'model_identity_changed',
      'model_no_compatible_connection',
      'model_access_denied',
      'model_unsupported_model',
    ].includes(code ?? '')
  )
    return { name: 'accounts' }
  return null
})
</script>

<template>
  <article
    :id="`message-${exchange.id}`"
    :class="['space-y-2 rounded-2xl transition-colors', highlighted && 'bg-accent/50 -m-2 p-2']"
    :aria-label="t('assistant.you')"
    data-testid="exchange"
  >
    <div class="flex flex-col items-end gap-1">
      <p v-if="answered" class="text-muted-foreground flex max-w-[85%] items-center gap-1 text-xs">
        <CornerDownRight class="size-3.5 shrink-0" />
        <span class="truncate">{{ answered }}</span>
      </p>
      <div
        :class="
          cn(
            'max-w-[85%] space-y-2 rounded-2xl rounded-tr-md px-3.5 py-2',
            waiting
              ? 'border-input text-foreground border border-dashed'
              : 'bg-accent text-accent-foreground',
          )
        "
        :data-queued="waiting || undefined"
      >
        <!-- The photo as sent from this page, else the small preview kept by the browser; bg-muted
             holds the box until it paints. -->
        <img
          v-for="image in exchange.images"
          :key="image.id"
          :src="sentPhoto(image.id) ?? image.previewUrl ?? image.url"
          v-bind="photoSize(image)"
          :alt="t('assistant.photoPreview')"
          loading="lazy"
          decoding="async"
          class="bg-muted rounded-xl"
        />
        <!-- The time follows the last line where it fits there, and goes under it otherwise. -->
        <p class="flex flex-wrap items-end justify-end gap-x-2.5">
          <span v-if="exchange.text" class="min-w-0 flex-1 break-words whitespace-pre-wrap">{{
            exchange.text
          }}</span>
          <!-- A queued message says so where the time goes, as messengers mark an unsent one. -->
          <span
            v-if="waiting"
            class="text-muted-foreground inline-flex items-center gap-1 text-[0.6875rem] leading-5"
            ><Clock3 class="size-3" aria-hidden="true" />{{ t('assistant.controls.queued') }}</span
          >
          <time
            v-else
            class="text-muted-foreground text-[0.6875rem] leading-5 tabular-nums"
            :datetime="exchange.createdAt"
            >{{ timeIn(zone, exchange.createdAt) }}</time
          >
        </p>
      </div>
      <div
        v-if="waiting && exchange.generation"
        class="flex flex-wrap items-center justify-end gap-0.5"
        data-testid="queued-actions"
      >
        <Button
          v-if="movable"
          variant="outline"
          size="sm"
          class="min-h-11 rounded-full"
          :disabled="controlBusy"
          data-testid="send-now"
          @click="emit('sendNow', exchange)"
          ><ArrowUp />{{ t('assistant.controls.sendNow') }}</Button
        >
        <Button
          variant="ghost"
          size="icon"
          class="text-muted-foreground"
          :title="t('assistant.controls.editQueued')"
          :aria-label="t('assistant.controls.editQueued')"
          :disabled="controlBusy"
          @click="emit('editQueued', exchange)"
        >
          <Pencil class="size-4.5" />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          class="text-muted-foreground"
          :title="t('assistant.controls.withdraw')"
          :aria-label="t('assistant.controls.withdraw')"
          :disabled="controlBusy"
          @click="emit('control', exchange, 'withdraw')"
        >
          <Trash2 class="size-4.5" />
        </Button>
      </div>
    </div>

    <ActivityPanel
      v-if="
        exchange.status === 'pending' &&
        !exchange.queued &&
        (!exchange.clarification || exchange.clarification.state === 'queued')
      "
      :message-id="exchange.id"
      :queued="exchange.queued"
      @settled="emit('activitySettled', exchange.id)"
    />

    <ClarificationStatus
      v-if="exchange.clarification"
      :item="exchange.clarification"
      :checking="clarificationChecking"
      @check="emit('checkClarification', exchange.clarification)"
      @open="emit('open', $event)"
    />

    <ReplyBlock
      v-for="reply in exchange.replies"
      :key="reply.turnId"
      :reply="reply"
      :highlighted="!!reply.messageId && reply.messageId === highlightedReplyId"
      :admin="admin"
      :collapse-failed-text="exchange.status === 'failed' && reply.responseFailed"
      :compact-outcome="exchange.status === 'failed'"
      :latest="reply.turnId === latestReply"
      class="max-w-[min(92%,40rem)]"
      @answer="emit('answer', $event)"
      @confirm="(item, accept) => emit('confirm', item, accept)"
      @check="emit('check', $event)"
    />

    <div
      v-if="exchange.controlState === 'settled'"
      class="text-muted-foreground max-w-[92%] space-y-1 text-sm"
      role="status"
      data-testid="controlled"
    >
      <p>
        {{
          t(
            exchange.controlAction === 'withdraw'
              ? 'assistant.controls.withdrawn'
              : 'assistant.controls.stopped',
          )
        }}
      </p>
      <p v-if="exchange.controlExecution?.effects === 'confirmed'">
        {{ t('assistant.controls.keptChanges') }}
      </p>
      <p v-else-if="exchange.controlExecution?.effects === 'unknown'">
        {{ t('assistant.controls.unknownChanges') }}
      </p>
      <Button
        v-for="reply in unresolved"
        :key="reply.turnId"
        size="sm"
        class="min-h-11"
        :disabled="busy"
        @click="emit('check', reply)"
      >
        {{ t('assistant.pendingCheck') }}
      </Button>
      <Button
        v-if="exchange.canRestore && !recovered"
        variant="ghost"
        size="sm"
        class="min-h-11"
        :disabled="busy"
        @click="emit('restore', exchange)"
      >
        {{ t('assistant.editAgain') }}
      </Button>
    </div>

    <p
      v-else-if="exchange.status === 'failed' && recovered"
      class="text-muted-foreground flex max-w-[92%] items-start gap-2 text-sm"
      data-testid="recovered-failure"
    >
      <CircleAlert class="mt-0.5 size-4 shrink-0" />{{ t('assistant.recoveredFailure') }}
    </p>
    <div
      v-else-if="exchange.status === 'failed'"
      class="text-muted-foreground max-w-[92%] space-y-1 text-sm"
      role="status"
      data-testid="failed"
    >
      <p class="flex items-start gap-2 font-medium">
        <CircleAlert class="mt-0.5 size-4 shrink-0" />{{
          t(
            exchange.failureKind === 'response'
              ? 'assistant.answerInterrupted'
              : exchange.failureKind === 'unknown'
                ? 'assistant.outcomeUnknown'
                : 'assistant.failed',
          )
        }}
      </p>
      <p>{{ failure }}</p>
      <p v-if="effects.none || effects.partial || effects.confirmed || effects.unknown">
        <span v-if="effects.none">{{ t('assistant.noChanges') }}</span>
        <span v-if="effects.partial">{{ t('assistant.partialResult') }} </span>
        <span v-if="effects.confirmed">{{ t('assistant.confirmedChanges') }} </span>
        <span v-if="effects.unknown">{{ t('assistant.resultUnknown') }}</span>
      </p>
      <div class="flex flex-wrap gap-2">
        <Button
          v-for="reply in unresolved"
          :key="reply.turnId"
          size="sm"
          class="min-h-11"
          :disabled="busy"
          @click="emit('check', reply)"
        >
          {{ t('assistant.pendingCheck') }}
        </Button>
        <Button v-if="repair && admin" size="sm" class="min-h-11" as-child
          ><RouterLink :to="repair">{{ t('assistant.fixAccess') }}</RouterLink></Button
        >
        <p v-else-if="repair" class="self-center">{{ t('assistant.contactAdmin') }}</p>
        <Button
          v-if="(exchange.text || exchange.images.length) && exchange.canRestore && !recovered"
          size="sm"
          variant="outline"
          class="min-h-11"
          :disabled="busy"
          @click="emit('restore', exchange)"
        >
          {{ t('assistant.editAgain') }}
        </Button>
      </div>
    </div>
  </article>
</template>
