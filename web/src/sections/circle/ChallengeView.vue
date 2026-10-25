<script setup lang="ts">
import { CircleAlert, Trophy } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { dayIn } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import challengeMessages from '@/locales/circle-challenge-ru.json'
import { useSession } from '@/stores/session'
import { band, challengeState, joinOpen, periodLabel } from './challenges'
import GoneState from './GoneState.vue'
import { isGone, useCircleActions, useLeaderboard } from './useCircle'

/**
 * K02–K05/K07/K08: the rules before joining, the ranking of allowed aggregates only, and the
 * user's own participation. The goal and zone are fixed at the start and shown only to the user.
 */
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: challengeMessages },
  missingWarn: false,
  fallbackWarn: false,
})
const route = useRoute()
const session = useSession()
const challengeId = computed(() => String(route.params.challenge ?? ''))
const board = useLeaderboard(challengeId)
const actions = useCircleActions()
const today = computed(() => dayIn(session.me?.timezone ?? 'UTC'))
const data = computed(() => board.data.value)
const challenge = computed(() => data.value?.challenge ?? null)
const participation = computed(() => data.value?.participation ?? null)
const open = computed(() => (challenge.value ? joinOpen(challenge.value) : false))
const leaving = ref(false)

const post = (action: 'join' | 'leave', message: string) =>
  actions.act(
    () => actions.post(`/v1/challenges/${encodeURIComponent(challengeId.value)}/${action}`, {}),
    message,
    ['challenges'],
  )
async function leave() {
  leaving.value = false
  await post('leave', t('circle.leftChallenge'))
}
</script>

<template>
  <PageHeader
    :title="challenge?.name ?? t('circle.challenge')"
    :description="
      challenge
        ? `${periodLabel(challenge, today)} · ${t(`circle.challengeState.${challengeState(challenge, today)}`)}`
        : undefined
    "
    :back="{ name: 'circle' }"
  />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <GoneState v-if="isGone(board.error.value)" />
    <LoadingState v-else-if="board.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="board.isLoadingError.value"
      :error="board.error.value"
      @retry="board.refetch()"
    />
    <template v-else-if="data && challenge">
      <RefreshFailed :query="board" />
      <!-- K02: the rules, before anyone joins. -->
      <section class="bg-card space-y-2 rounded-3xl border p-5" data-testid="challenge-rules">
        <p>{{ t('circle.rulesText') }}</p>
        <dl class="divide-y text-sm">
          <div class="flex flex-wrap justify-between gap-3 py-1.5">
            <dt class="text-muted-foreground">{{ t('circle.tolerance') }}</dt>
            <dd>{{ t('circle.toleranceValue', { value: formatAmount(challenge.tolerance) }) }}</dd>
          </div>
          <div class="flex flex-wrap justify-between gap-3 py-1.5">
            <dt class="text-muted-foreground">{{ t('circle.othersSee') }}</dt>
            <dd>{{ t('circle.daysAndStreak') }}</dd>
          </div>
        </dl>
      </section>

      <!-- The user's own participation: K05 pending, K08 active, K04 no goal. -->
      <section class="space-y-3" data-testid="participation">
        <Alert v-if="participation === 'no_goal'" variant="warning">
          <CircleAlert />
          <AlertTitle>{{ t('circle.noGoalTitle') }}</AlertTitle>
          <AlertDescription>
            <p>{{ t('circle.noGoalText') }}</p>
            <RouterLink
              :to="{ name: 'food' }"
              class="text-primary underline-offset-4 hover:underline"
            >
              {{ t('circle.goalForLater') }}
            </RouterLink>
          </AlertDescription>
        </Alert>
        <Alert v-else-if="participation === 'pending'">
          <AlertTitle>{{ t('circle.pendingTitle') }}</AlertTitle>
          <AlertDescription>{{ t('circle.pendingText') }}</AlertDescription>
        </Alert>
        <div
          v-else-if="participation === 'active' && data.terms"
          class="bg-card space-y-2 rounded-3xl border p-5"
        >
          <p class="font-medium">{{ t('circle.activeTitle') }}</p>
          <dl class="divide-y text-sm">
            <div class="flex flex-wrap justify-between gap-3 py-1.5">
              <dt class="text-muted-foreground">{{ t('circle.goalAtStart') }}</dt>
              <dd>{{ formatAmount(data.terms.calorie_target, 0) }} {{ t('food.kcal') }}</dd>
            </div>
            <div class="flex flex-wrap justify-between gap-3 py-1.5">
              <dt class="text-muted-foreground">
                {{ t('circle.range', { tolerance: formatAmount(challenge.tolerance) }) }}
              </dt>
              <dd>{{ band(data.terms.calorie_target, challenge.tolerance) }}</dd>
            </div>
            <div class="flex flex-wrap justify-between gap-3 py-1.5">
              <dt class="text-muted-foreground">{{ t('settings.timezone') }}</dt>
              <dd>{{ data.terms.timezone }}</dd>
            </div>
          </dl>
          <p class="text-muted-foreground text-sm">{{ t('circle.termsFixed') }}</p>
        </div>
        <p v-else-if="!open" class="text-muted-foreground text-sm">{{ t('circle.joinClosed') }}</p>

        <Button
          v-if="!participation && open"
          :disabled="actions.busy.value"
          @click="post('join', t('circle.joinedChallenge'))"
        >
          {{ t('circle.participate') }}
        </Button>
        <Button
          v-if="participation"
          variant="ghost"
          class="text-destructive"
          @click="leaving = true"
        >
          {{ t('circle.leaveRanking') }}
        </Button>
      </section>

      <!-- K03: places, names, successful days and the current streak only. -->
      <section class="space-y-2" aria-labelledby="ranking-title">
        <h2 id="ranking-title" class="font-semibold">{{ t('circle.ranking') }}</h2>
        <p v-if="!data.ranking.length" class="text-muted-foreground text-sm">
          {{ t('circle.rankingEmpty') }}
        </p>
        <table
          v-else
          class="bg-card w-full overflow-hidden rounded-3xl border text-sm"
          data-testid="ranking"
        >
          <thead class="text-muted-foreground text-left">
            <tr>
              <th class="px-4 py-2 font-medium">{{ t('circle.rankingPlace') }}</th>
              <th class="px-2 py-2 font-medium">{{ t('circle.participant') }}</th>
              <th class="px-2 py-2 text-right font-medium">{{ t('circle.days') }}</th>
              <th class="px-4 py-2 text-right font-medium">{{ t('circle.streak') }}</th>
            </tr>
          </thead>
          <tbody class="divide-y">
            <tr
              v-for="row in data.ranking"
              :key="row.user_id"
              :class="row.user_id === session.me?.id ? 'bg-accent/50 font-medium' : ''"
            >
              <td class="px-4 py-2 tabular-nums">{{ row.place }}</td>
              <td class="px-2 py-2">
                {{ row.user_id === session.me?.id ? t('circle.you') : row.name }}
              </td>
              <td class="px-2 py-2 text-right tabular-nums">{{ row.days }}</td>
              <td class="px-4 py-2 text-right tabular-nums">{{ row.streak }}</td>
            </tr>
          </tbody>
        </table>
        <p class="text-muted-foreground flex items-center gap-2 text-sm">
          <Trophy class="size-4" />{{ t('circle.todayNotCounted') }}
        </p>
      </section>
    </template>
  </div>

  <Dialog v-model:open="leaving">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('circle.leaveRankingTitle') }}</DialogTitle>
        <DialogDescription>
          {{ t('circle.leaveRankingText') }}
          <template v-if="!open"> {{ t('circle.noRejoin') }}</template>
        </DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="leaving = false">{{ t('circle.stay') }}</Button>
        <Button variant="destructive" @click="leave">{{ t('circle.leaveRanking') }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
