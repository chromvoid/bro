<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Button } from '@/components/ui/button'
import { dayIn, momentPhrase } from '@/core/dates'
import { useInitialLoadError } from '@/core/useInitialLoadError'
import { provideDisplayUnits } from './displayUnits'
import { useSession } from '@/stores/session'
import { amountText, provenance, type Observation } from './api'
import { profileField } from './profile'
import ProfileForm from './ProfileForm.vue'
import { useOverview, usePersonal } from './usePersonal'
import WeightPanel from './WeightPanel.vue'
import { personalReturnLink, personalReturnQuery } from './personalNavigation'

const { t } = useI18n()
const route = useRoute()
const session = useSession()
const personal = usePersonal()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const back = computed(() => personalReturnLink(route.query, today.value))
const historyLink = computed(() => ({
  name: 'personal-history',
  query: { ...personalReturnQuery(route.query), kind: 'weight', end: today.value },
}))
const overview = useOverview(today)
const loadError = useInitialLoadError(overview)
const data = computed(() => overview.data.value)
const displayUnits = computed(() => {
  const profile = data.value?.profile
  return profile && profile.user_id === session.me?.id ? profile.display_units : 'metric'
})
provideDisplayUnits(displayUnits)
const canWrite = computed(() => session.can('personal.write'))
const focusField = computed(() => profileField(route.query.field))
const weightOpen = ref(false)
const weight = computed(() => data.value?.metrics.weight)
const weighed = computed(() =>
  (weight.value?.records ?? []).reduce<Observation | null>(
    (latest, record) =>
      !latest || Date.parse(record.start_at) > Date.parse(latest.start_at) ? record : latest,
    null,
  ),
)
</script>

<template>
  <PageHeader
    :title="t('personal.bodyProfileTitle')"
    :description="t('personal.bodyProfileHint')"
    :back="back"
  />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <UnresolvedWrites
      :entries="personal.unresolved.value"
      :describe="personal.describe"
      :check="personal.check"
      :resend="personal.resend"
      :abandon="personal.abandon"
    />
    <RefreshFailed :query="overview" />
    <LoadingState v-if="overview.isPending.value && !loadError" :rows="3" />
    <ErrorState
      v-else-if="loadError"
      :error="loadError.error"
      :retrying="loadError.retrying"
      @retry="overview.refetch()"
    />
    <template v-else-if="data">
      <section class="bg-card space-y-4 rounded-3xl border p-5" data-testid="body-profile-form">
        <h2 class="font-semibold">{{ t('personal.bodyProfileTitle') }}</h2>
        <p class="text-muted-foreground text-sm">{{ t('personal.profileHint') }}</p>
        <ProfileForm
          :key="data.profile.user_id"
          :profile="data.profile"
          :today="today"
          :read-only="!canWrite"
          :focus-field="focusField"
        />
      </section>
      <section
        v-if="weight"
        class="bg-card space-y-3 rounded-3xl border p-5"
        data-testid="profile-weight"
      >
        <h2 class="font-semibold">{{ t('personal.metric.weight') }}</h2>
        <p v-if="weight.status === 'conflict'" class="text-warning text-lg font-medium">
          {{ t('personal.disagree') }}
        </p>
        <p v-else-if="weight.status === 'unavailable'" class="text-muted-foreground">
          {{ t('personal.unavailable') }}
        </p>
        <p v-else-if="weight.value !== null" class="text-3xl font-semibold tabular-nums">
          {{ amountText('weight', weight.value, displayUnits) }}
        </p>
        <p v-else class="text-muted-foreground">{{ t('personal.noWeight') }}</p>
        <p v-if="weighed && weight.status === 'available'" class="text-muted-foreground text-sm">
          {{ t('personal.lastWeight', { when: momentPhrase(weighed.start_at, zone, today) }) }} ·
          {{ provenance(weighed, (origin) => t(`personal.origin.${origin}`)) }}
        </p>
        <div class="flex flex-wrap gap-2">
          <Button v-if="canWrite" @click="weightOpen = true"
            ><Plus />{{ t('personal.addWeight') }}</Button
          >
          <Button variant="outline" as-child>
            <RouterLink :to="historyLink">{{ t('personal.history') }}</RouterLink>
          </Button>
        </div>
      </section>
      <RouterLink
        :to="back"
        class="text-primary block text-sm underline-offset-4 hover:underline"
        >{{ t('personal.profileReturn') }}</RouterLink
      >
    </template>
  </div>
  <WeightPanel v-if="canWrite" v-model:open="weightOpen" />
</template>
