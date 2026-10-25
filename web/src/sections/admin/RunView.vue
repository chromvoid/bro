<script setup lang="ts">
import { CircleAlert, CircleCheck, FileText } from '@lucide/vue'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { dayIn, momentLabel, momentPhrase } from '@/core/dates'
import { useSession } from '@/stores/session'
import { FINISHED } from './api'
import { notInstalled, useRun } from './useAdmin'
import { useWorkerOps } from './useWorkerOps'

/**
 * M10–M13/M18–M20: one run. Real progress messages, not an invented percentage; a stop request
 * is not a confirmed stop; an interrupted run continues only after the user reviewed its effects.
 */
const { t, te } = useI18n()
const route = useRoute()
const session = useSession()
const runId = computed(() => String(route.params.run ?? ''))
const detail = useRun(runId)
const ops = useWorkerOps()
const run = computed(() => detail.data.value)
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const label = (group: string, value: string | null | undefined) =>
  value && te(`admin.${group}.${value}`) ? t(`admin.${group}.${value}`) : (value ?? '')
const progress = computed(() =>
  (run.value?.events ?? [])
    .filter((event) => event.kind === 'progress' && typeof event.payload.text === 'string')
    .reverse(),
)
const artifacts = computed(() =>
  (run.value?.events ?? [])
    .filter((event) => event.kind === 'artifact' && typeof event.payload.path === 'string')
    .map((event) => String(event.payload.path)),
)
const expected = () => ({ [run.value!.id]: run.value!.revision })

// Follow-ups are addressed to this managed session and use the same delivery journal as a stop.
const followup = ref('')
const canSend = computed(() => run.value?.can_send === true)
watch(runId, () => {
  followup.value = ''
})
async function sendFollowup() {
  const text = followup.value.trim()
  if (!text || !canSend.value) return
  if (
    await ops.send('run.send', { run_id: run.value!.id, text }, expected(), t('admin.followupSent'))
  )
    followup.value = ''
}

// M11: answers go to the existing run.
const answers = reactive<Record<string, string>>({})
async function answer(requestId: string, questions: Array<{ id: string }>) {
  if (questions.some((question) => !answers[question.id]?.trim())) return
  const payload = Object.fromEntries(
    questions.map((question) => [question.id, { answers: [answers[question.id]!.trim()] }]),
  )
  await ops.send(
    'input.answer',
    { request_id: requestId, answer: { answers: payload } },
    expected(),
    t('admin.answered'),
  )
}

// M13: effects are reviewed before any continuation.
const reviewed = ref(false)
const recover = (resolution: 'cancel' | 'pause') =>
  ops.send('run.recover', { run_id: run.value!.id, effects_reviewed: true, resolution }, expected())
</script>

<template>
  <PageHeader
    :title="run?.goal ?? t('admin.run')"
    :description="run ? label('runState', run.status) : undefined"
    :back="{ name: 'workers' }"
  />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <UnresolvedWrites
      :entries="ops.unresolved.value"
      :describe="ops.describe"
      :check="ops.check"
      :resend="ops.resend"
      :abandon="ops.abandon"
    />
    <RefreshFailed :query="detail" />
    <LoadingState v-if="detail.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="detail.isLoadingError.value && !notInstalled(detail.error.value)"
      :error="detail.error.value"
      @retry="detail.refetch()"
    />
    <p v-else-if="detail.isLoadingError.value" class="text-muted-foreground">
      {{ t('admin.workersUnavailableText') }}
    </p>
    <template v-else-if="run">
      <div class="bg-card space-y-1 rounded-3xl border p-5 text-sm" data-testid="run-status">
        <p class="flex flex-wrap items-center gap-2">
          <Badge>{{ label('runState', run.status) }}</Badge>
          <span v-if="run.reason" class="text-muted-foreground">{{
            label('reason', run.reason)
          }}</span>
        </p>
        <p class="text-muted-foreground">
          {{ t('admin.deadline', { when: momentPhrase(run.deadline, zone, today) }) }}
        </p>
        <p v-if="run.workspace" class="text-muted-foreground">
          {{ t('admin.workspaceIs', { workspace: run.workspace }) }}
        </p>
      </div>

      <!-- M18: a requested stop is not a finished one. -->
      <Alert
        v-if="run.stop_requested && !FINISHED.has(run.status)"
        variant="warning"
        data-testid="stop-requested"
      >
        <CircleAlert />
        <AlertTitle>{{ t('admin.stopWaitTitle') }}</AlertTitle>
        <AlertDescription>{{ t('admin.stopWaitText') }}</AlertDescription>
      </Alert>

      <!-- M11 -->
      <section
        v-for="request in run.requests"
        :key="request.id"
        class="bg-warning-surface space-y-3 rounded-3xl p-5"
        data-testid="run-question"
      >
        <h2 class="font-semibold">{{ t('admin.needAnswer') }}</h2>
        <form
          class="space-y-3"
          novalidate
          @submit.prevent="answer(request.id, request.payload.questions)"
        >
          <div v-for="question in request.payload.questions" :key="question.id" class="space-y-2">
            <Label :for="`answer-${question.id}`">{{ question.question }}</Label>
            <div v-if="question.options?.length" class="flex flex-wrap gap-2">
              <Button
                v-for="option in question.options"
                :key="option.label"
                type="button"
                size="sm"
                :variant="answers[question.id] === option.label ? 'default' : 'outline'"
                @click="answers[question.id] = option.label"
              >
                {{ option.label }}
              </Button>
            </div>
            <Input :id="`answer-${question.id}`" v-model="answers[question.id]" maxlength="2000" />
          </div>
          <Button type="submit" :disabled="ops.busy.value">{{ t('admin.answerContinue') }}</Button>
        </form>
      </section>

      <!-- M13 -->
      <section
        v-if="run.reason === 'outcome_unknown'"
        class="space-y-3 rounded-3xl border p-5"
        data-testid="run-recover"
      >
        <Alert variant="warning">
          <CircleAlert />
          <AlertTitle>{{ t('admin.unknownEffects') }}</AlertTitle>
          <AlertDescription>{{ t('admin.unknownEffectsText') }}</AlertDescription>
        </Alert>
        <Label class="flex items-center gap-2 font-normal">
          <Checkbox
            :model-value="reviewed"
            @update:model-value="(value) => (reviewed = value === true)"
          />{{ t('admin.effectsReviewed') }}
        </Label>
        <div class="flex flex-wrap gap-2">
          <Button :disabled="!reviewed || ops.busy.value" @click="recover('pause')">{{
            t('admin.preparePause')
          }}</Button>
          <Button
            variant="ghost"
            class="text-destructive"
            :disabled="!reviewed || ops.busy.value"
            @click="recover('cancel')"
            >{{ t('admin.stopAndCancel') }}</Button
          >
        </div>
      </section>

      <!-- M12/M20 -->
      <Alert v-if="run.status === 'ready'" variant="success" data-testid="run-ready">
        <CircleCheck />
        <AlertTitle>{{ t('admin.readyTitle') }}</AlertTitle>
        <AlertDescription>{{ t('admin.readyText') }}</AlertDescription>
      </Alert>
      <section v-if="artifacts.length" class="space-y-2">
        <h2 class="font-semibold">{{ t('admin.artifacts') }}</h2>
        <ul class="bg-card divide-y rounded-3xl border text-sm">
          <li v-for="path in artifacts" :key="path" class="flex items-center gap-2 px-4 py-2.5">
            <FileText class="text-muted-foreground size-4" /><code class="break-all">{{
              path
            }}</code>
          </li>
        </ul>
      </section>

      <!-- M10: what the executor reported, newest last. -->
      <section class="space-y-2">
        <h2 class="font-semibold">{{ t('admin.progress') }}</h2>
        <p v-if="!progress.length" class="text-muted-foreground text-sm">
          {{ t('admin.noProgress') }}
        </p>
        <ol v-else class="space-y-1.5 text-sm" data-testid="run-progress">
          <li v-for="(event, index) in progress" :key="index" class="flex gap-2">
            <span class="text-muted-foreground shrink-0 tabular-nums">{{
              momentLabel(event.created_at, zone, today)
            }}</span>
            <span>{{ event.payload.text }}</span>
          </li>
        </ol>
      </section>

      <section v-if="canSend" class="space-y-3 rounded-3xl border p-5" data-testid="run-followup">
        <h2 class="font-semibold">{{ t('admin.followupTitle') }}</h2>
        <p class="text-muted-foreground text-sm">{{ t('admin.followupText') }}</p>
        <form class="space-y-3" novalidate @submit.prevent="sendFollowup">
          <Label for="run-followup">{{ t('admin.followupLabel') }}</Label>
          <Textarea
            id="run-followup"
            v-model="followup"
            maxlength="10000"
            rows="3"
            :disabled="ops.busy.value"
          />
          <Button type="submit" :disabled="!followup.trim() || ops.busy.value">{{
            t('admin.sendFollowup')
          }}</Button>
        </form>
      </section>

      <div class="flex flex-wrap gap-2">
        <!-- M19 -->
        <Button
          v-if="run.status === 'paused'"
          :disabled="ops.busy.value"
          @click="ops.send('run.resume', { run_id: run.id }, expected(), t('admin.resumed'))"
        >
          {{ t('admin.continueRun') }}
        </Button>
        <Button
          v-if="!FINISHED.has(run.status) && !run.stop_requested"
          variant="ghost"
          class="text-destructive"
          :disabled="ops.busy.value"
          @click="ops.send('run.cancel', { run_id: run.id }, expected(), t('admin.stopAsked'))"
        >
          {{ t('admin.requestStop') }}
        </Button>
      </div>
    </template>
  </div>
</template>
