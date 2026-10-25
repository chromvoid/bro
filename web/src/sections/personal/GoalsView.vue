<script setup lang="ts">
import {
  provideDisplayUnits,
  distanceInput,
  distanceTargetMeters,
  distanceUnit,
  type DisplayUnits,
} from './displayUnits'
import DisplayUnitsControl from './DisplayUnitsControl.vue'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { dayIn } from '@/core/dates'
import { parseAmount } from '@/core/numbers'
import { useSession } from '@/stores/session'
import { KINDS, hasTarget, amountText, unitOf, type Kind } from './api'
import { useOverview, usePersonal } from './usePersonal'
import { personalReturnLink } from './personalNavigation'

/** The person's own targets and the units the app shows them in. */
const { t } = useI18n()
const route = useRoute()
const session = useSession()
const personal = usePersonal()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const back = computed(() => personalReturnLink(route.query, today.value))
const overview = useOverview(today)
const displayUnits = computed(() => {
  const profile = overview.data.value?.profile
  return profile && profile.user_id === session.me?.id ? profile.display_units : 'metric'
})
provideDisplayUnits(displayUnits)
const targetKinds = KINDS.filter(hasTarget)
const data = computed(() => overview.data.value)
const canWrite = computed(() => session.can('personal.write'))

const editing = ref<Kind | null>(null)
const goal = reactive({
  target: '',
  initialDisplay: '',
  canonical: null as string | null,
  units: 'metric' as DisplayUnits,
  busy: false,
})
function edit(kind: Kind) {
  editing.value = kind
  goal.canonical = data.value?.preferences[kind]?.target ?? null
  goal.units = displayUnits.value
  goal.target =
    kind === 'distance' ? distanceInput(goal.canonical, goal.units) : (goal.canonical ?? '')
  goal.initialDisplay = goal.target
}
async function saveTarget(kind: Kind) {
  const target = goal.target.trim() ? parseAmount(goal.target) : null
  if ((goal.target.trim() && !target) || goal.busy) return
  const canonicalDistance =
    target === null
      ? null
      : parseAmount(goal.initialDisplay) === target
        ? goal.canonical
        : distanceTargetMeters(target, goal.units)
  if (kind === 'distance' && target !== null && canonicalDistance === null) return
  goal.busy = true
  try {
    await personal.operate('preference.set', {
      kind,
      target:
        target === null
          ? null
          : kind === 'distance'
            ? canonicalDistance
            : ['weight', 'active_energy'].includes(kind)
              ? target
              : Math.round(Number(target)),
      revision: data.value?.preferences[kind]?.revision ?? 0,
    })
    toast.success(t('personal.targetSaved'))
    editing.value = null
  } catch (error) {
    personal.failed(error)
  } finally {
    goal.busy = false
  }
}
</script>

<template>
  <PageHeader :title="t('sources.goals')" :back="back" />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <UnresolvedWrites
      :entries="personal.unresolved.value"
      :describe="personal.describe"
      :check="personal.check"
      :resend="personal.resend"
      :abandon="personal.abandon"
    />
    <RefreshFailed :query="overview" />
    <LoadingState v-if="overview.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="overview.isLoadingError.value"
      :error="overview.error.value"
      @retry="overview.refetch()"
    />
    <template v-else-if="data">
      <DisplayUnitsControl :profile="data.profile" :can-write="canWrite" />
      <section class="space-y-2" aria-labelledby="targets-title">
        <h2 id="targets-title" class="font-semibold">{{ t('resolution.targets') }}</h2>
        <ul class="bg-card divide-y rounded-3xl border">
          <li
            v-for="kind in targetKinds"
            :key="kind"
            class="space-y-3 px-4 py-3"
            :data-testid="`target-${kind}`"
          >
            <div class="flex items-center gap-3">
              <div class="min-w-0 flex-1">
                <p class="font-medium">{{ t(`personal.metric.${kind}`) }}</p>
                <p v-if="data.preferences[kind]?.target" class="text-muted-foreground text-sm">
                  {{
                    t('personal.target', {
                      value: amountText(kind, data.preferences[kind]!.target, displayUnits),
                    })
                  }}
                </p>
              </div>
              <Button
                v-if="canWrite && editing !== kind"
                size="sm"
                variant="ghost"
                @click="edit(kind)"
              >
                {{ t('common.edit') }}
              </Button>
            </div>
            <form
              v-if="editing === kind"
              class="grid gap-3 sm:grid-cols-2"
              novalidate
              @submit.prevent="saveTarget(kind)"
            >
              <div class="space-y-2">
                <Label :for="`pref-target-${kind}`">{{
                  t('personal.targetOf', {
                    unit: kind === 'distance' ? distanceUnit(goal.units) : unitOf(kind),
                  })
                }}</Label>
                <Input :id="`pref-target-${kind}`" v-model="goal.target" inputmode="decimal" />
              </div>
              <div class="flex gap-2 sm:col-span-2">
                <Button type="submit" size="sm" :disabled="goal.busy">{{
                  t('common.save')
                }}</Button>
                <Button type="button" size="sm" variant="ghost" @click="editing = null">{{
                  t('common.cancel')
                }}</Button>
              </div>
            </form>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>
