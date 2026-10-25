<script setup lang="ts">
import { ChevronRight, Plus, Trophy } from '@lucide/vue'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { dayIn, isIsoDay, shiftDay } from '@/core/dates'
import { useSession } from '@/stores/session'
import { challengeState, periodLabel } from './challenges'
import GoneState from './GoneState.vue'
import { isGone, useChallenges, useCircleActions, useSpaces } from './useCircle'

/**
 * K01/K06: challenges of a space. Everyone competes against their own goal; nobody's diet or
 * calorie target is shown to others.
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const spaceId = computed(() => String(route.params.space ?? ''))
const spaces = useSpaces()
const space = computed(
  () => spaces.data.value?.spaces.find((item) => item.id === spaceId.value) ?? null,
)
const challenges = useChallenges(spaceId)
const actions = useCircleActions()
const today = computed(() => dayIn(session.me?.timezone ?? 'UTC'))

const creating = ref(false)
const form = reactive({ name: '', start: '', end: '', tolerance: '10', error: '' })
function startCreating() {
  Object.assign(form, {
    name: '',
    start: shiftDay(today.value, 1),
    end: shiftDay(today.value, 14),
    tolerance: '10',
    error: '',
  })
  creating.value = true
}
async function create() {
  const tolerance = Number(form.tolerance.replace(',', '.'))
  form.error = !form.name.trim()
    ? t('circle.challengeNameRequired')
    : !isIsoDay(form.start) || !isIsoDay(form.end) || form.end < form.start
      ? t('circle.challengeDates')
      : form.start <= today.value
        ? t('circle.challengeFuture')
        : !Number.isFinite(tolerance) || tolerance < 0 || tolerance > 100
          ? t('circle.challengeTolerance')
          : ''
  if (form.error) return
  const created = await actions.act(
    () =>
      actions.post<{ id: string }>('/v1/challenges', {
        space_id: spaceId.value,
        name: form.name.trim(),
        start_date: form.start,
        end_date: form.end,
        tolerance,
      }),
    t('circle.challengeCreated'),
    ['challenges'],
  )
  if (!created) return
  creating.value = false
  void router.push({ name: 'circle-challenge', params: { challenge: created.id } })
}
</script>

<template>
  <PageHeader
    :title="t('circle.challenges')"
    :description="space?.name"
    :back="{ name: 'circle-space', params: { space: spaceId } }"
  >
    <template #actions>
      <Button
        v-if="session.can('challenges.write')"
        variant="ghost"
        size="icon"
        :aria-label="t('circle.newChallenge')"
        @click="startCreating"
      >
        <Plus class="size-5" />
      </Button>
    </template>
  </PageHeader>
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <GoneState v-if="isGone(challenges.error.value)" />
    <LoadingState v-else-if="challenges.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="challenges.isLoadingError.value"
      :error="challenges.error.value"
      @retry="challenges.refetch()"
    />
    <template v-else>
      <RefreshFailed :query="challenges" />
      <EmptyState
        v-if="!challenges.data.value?.challenges.length"
        :icon="Trophy"
        :title="t('circle.noChallenges')"
        :description="t('circle.noChallengesText')"
      >
        <Button v-if="session.can('challenges.write')" @click="startCreating">{{
          t('circle.newChallenge')
        }}</Button>
      </EmptyState>
      <ul v-else class="bg-card divide-y rounded-3xl border" data-testid="challenges">
        <li v-for="challenge in challenges.data.value.challenges" :key="challenge.id">
          <RouterLink
            :to="{ name: 'circle-challenge', params: { challenge: challenge.id } }"
            class="hover:bg-accent/60 flex items-center gap-3 px-4 py-3.5"
          >
            <Trophy class="text-muted-foreground size-5 shrink-0" />
            <span class="min-w-0 flex-1">
              <span class="block font-medium">{{ challenge.name }}</span>
              <span class="text-muted-foreground block text-sm">
                {{ periodLabel(challenge, today) }} ·
                {{ t(`circle.challengeState.${challengeState(challenge, today)}`) }}
              </span>
            </span>
            <ChevronRight class="text-muted-foreground size-4" />
          </RouterLink>
        </li>
      </ul>
      <Alert>
        <AlertTitle>{{ t('circle.rankingShows') }}</AlertTitle>
        <AlertDescription>{{ t('circle.rankingShowsText') }}</AlertDescription>
      </Alert>
    </template>
  </div>

  <Panel v-model:open="creating" :title="t('circle.newChallenge')">
    <form class="space-y-4" novalidate @submit.prevent="create">
      <div class="space-y-2">
        <Label for="challenge-name">{{ t('circle.challengeName') }}</Label>
        <Input
          id="challenge-name"
          v-model="form.name"
          maxlength="100"
          :placeholder="t('circle.challengeExample')"
        />
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div class="space-y-2">
          <Label for="challenge-start">{{ t('circle.challengeStart') }}</Label>
          <Input id="challenge-start" v-model="form.start" type="date" :min="shiftDay(today, 1)" />
        </div>
        <div class="space-y-2">
          <Label for="challenge-end">{{ t('circle.challengeEnd') }}</Label>
          <Input id="challenge-end" v-model="form.end" type="date" :min="form.start" />
        </div>
      </div>
      <div class="space-y-2">
        <Label for="challenge-tolerance">{{ t('circle.tolerance') }}</Label>
        <Input id="challenge-tolerance" v-model="form.tolerance" inputmode="decimal" />
      </div>
      <p v-if="form.error" class="text-destructive text-sm" role="alert">{{ form.error }}</p>
      <Alert>
        <AlertTitle>{{ t('circle.startFuture') }}</AlertTitle>
        <AlertDescription>{{ t('circle.startFutureText') }}</AlertDescription>
      </Alert>
      <Button type="submit" size="lg" class="w-full" :disabled="actions.busy.value">{{
        t('circle.createChallenge')
      }}</Button>
    </form>
  </Panel>
</template>
