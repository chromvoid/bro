<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Button } from '@/components/ui/button'
import { dayIn } from '@/core/dates'
import { useSession } from '@/stores/session'
import SessionDetail from './SessionDetail.vue'
import SessionEffort from './SessionEffort.vue'
import SessionForm from './SessionForm.vue'
import { activityReturnQuery } from './activityNavigation'
import { useActivity, useActivitySession, useExercises } from './useActivity'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const account = useSession()
const activity = useActivity()
const id = computed(() => String(route.params.session))
const session = useActivitySession(id)
const catalog = useExercises()
const zone = computed(() => account.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const returnQuery = computed(() =>
  activityReturnQuery(route.query, today.value, session.data.value?.session.date),
)
const canWrite = computed(() => account.can('activity.write'))
const editing = ref(false)
const deleting = ref(false)
const busy = ref(false)
let pendingSaved: (() => void) | undefined
activity.onResolved(() => {
  pendingSaved?.()
  pendingSaved = undefined
})
const disabled = computed(() => busy.value || !!activity.unresolved.value.length)
async function operate(
  kind: string,
  payload: Record<string, unknown>,
  expected: Record<string, number>,
  saved?: () => void,
) {
  if (disabled.value) return
  busy.value = true
  try {
    pendingSaved = saved
    await activity.operate(kind, payload, expected)
    pendingSaved?.()
    pendingSaved = undefined
  } catch (error) {
    activity.failed(error)
  } finally {
    busy.value = false
  }
}
async function remove() {
  const item = session.data.value?.session
  if (!item || disabled.value) return
  const query = returnQuery.value
  await operate('session.delete', { session_id: item.id }, { [item.id]: item.revision }, () => {
    void router.replace({ name: 'activity', query })
  })
}

function progress(exercise: string) {
  void router.push({
    name: 'activity',
    query: {
      ...returnQuery.value,
      tab: 'progress',
      metric: 'strength',
      kind: 'strength',
      exercise,
    },
  })
}
async function recover(key: string, resend = false) {
  const entry = await (resend ? activity.resend(key) : activity.check(key))
  if (entry?.state === 'resolved') {
    pendingSaved?.()
    pendingSaved = undefined
  }
}
</script>

<template>
  <PageHeader
    :title="
      session.data.value?.session.title ||
      (session.data.value
        ? t(`activity.kinds.${session.data.value.session.kind}`)
        : t('activity.session'))
    "
    :back="{ name: 'activity', query: returnQuery }"
    width="5xl"
  />
  <div class="mx-auto max-w-5xl space-y-4 px-4 pb-8 md:px-8" data-testid="activity-session">
    <UnresolvedWrites
      :entries="activity.unresolved.value"
      :describe="activity.describe"
      :check="(key) => recover(key)"
      :resend="(key) => recover(key, true)"
      :abandon="activity.abandon"
    />
    <RefreshFailed :query="[session, catalog]" />
    <LoadingState v-if="session.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="session.isLoadingError.value"
      :error="session.error.value"
      @retry="session.refetch()"
    />
    <template v-else-if="session.data.value">
      <SessionDetail
        :session="session.data.value.session"
        :catalog="catalog.data.value?.exercises ?? []"
        :can-write="canWrite"
        :busy="disabled"
        @operation="operate"
        @edit="editing = true"
        @delete="deleting = true"
        @progress="progress"
      />
      <SessionEffort :session="session.data.value.session" />
      <ErrorState
        v-if="catalog.isLoadingError.value && session.data.value.session.kind === 'strength'"
        :error="catalog.error.value"
        @retry="catalog.refetch()"
      />
      <div v-if="deleting" class="bg-card space-y-3 rounded-2xl border p-4" role="alert">
        <p>{{ t('activity.confirmDeleteSession') }}</p>
        <div class="flex gap-2">
          <Button variant="destructive" :disabled="disabled" @click="remove">{{
            t('common.delete')
          }}</Button
          ><Button variant="outline" :disabled="busy" @click="deleting = false">{{
            t('common.cancel')
          }}</Button>
        </div>
      </div>
      <SessionForm
        v-if="canWrite"
        v-model:open="editing"
        :session="session.data.value.session"
        :today="today"
        :timezone="zone"
      />
    </template>
  </div>
</template>
