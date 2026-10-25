<script setup lang="ts">
import { ChevronRight, Search, UserRound } from '@lucide/vue'
import { computed, reactive, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { useSession } from '@/stores/session'
import { Person, relationshipGroups, type Relationship } from './api'
import { useCircleActions, useRelationships } from './useCircle'
import ReportButton from './ReportButton.vue'

/**
 * G02/G03/G16: people are found by exact login only. A label such as «брат» is a note for the
 * user; it grants nothing. Talking and sharing personal data stay separate decisions.
 */
const { t } = useI18n()
const router = useRouter()
const session = useSession()
const relationships = useRelationships()
const actions = useCircleActions()
const groups = computed(() =>
  relationshipGroups(relationships.data.value?.relationships ?? [], session.me?.id ?? ''),
)

// A person opened from search or the list.
interface Card {
  id: string
  name: string
  login: string | null
}
const person = shallowRef<Card | null>(null)
const personOpen = computed({
  get: () => person.value !== null,
  set: (value: boolean) => {
    if (!value) person.value = null
  },
})
const relation = computed<Relationship | null>(
  () =>
    relationships.data.value?.relationships.find((item) => item.user_id === person.value?.id) ??
    null,
)
const label = ref('')
function open(card: Card) {
  person.value = card
  label.value = relation.value?.label ?? ''
}

const search = reactive({ login: '', busy: false, error: '' })
async function find() {
  const login = search.login.trim().replace(/^@/, '')
  if (!login || search.busy) return
  search.busy = true
  search.error = ''
  try {
    const { user } = Person.parse(
      await api.get(`/v1/relationships?login=${encodeURIComponent(login)}`),
    )
    if (!user) search.error = t('access.notFound')
    else if (user.id === session.me?.id) search.error = t('access.notYourself')
    else open({ id: user.id, name: user.name, login: user.login })
  } catch (error) {
    search.error = errorText(error, t)
  } finally {
    search.busy = false
  }
}

const change = (action: string, message: string, extra: Record<string, unknown> = {}) =>
  actions.act(
    () => actions.post('/v1/relationships', { user_id: person.value!.id, action, ...extra }),
    message,
  )

async function write() {
  const channel = await actions.act(
    () => actions.post<{ id: string }>('/v1/channels/direct', { user_id: person.value!.id }),
    null,
  )
  if (channel) void router.push({ name: 'circle-chat', params: { channel: channel.id } })
}

const state = (item: Relationship) =>
  item.state === 'pending'
    ? item.recipient_id === session.me?.id
      ? t('circle.requestIn')
      : t('circle.requestOut')
    : item.state === 'blocked'
      ? t('circle.blocked')
      : item.label || t('circle.friend')
</script>

<template>
  <PageHeader :title="t('circle.friends')" :back="{ name: 'circle' }" />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <form class="flex gap-2" novalidate @submit.prevent="find">
      <div class="relative min-w-0 flex-1">
        <Search class="text-muted-foreground absolute top-1/2 left-3.5 size-4 -translate-y-1/2" />
        <Input
          v-model="search.login"
          class="pl-10"
          autocomplete="off"
          autocapitalize="none"
          spellcheck="false"
          :aria-label="t('circle.exactLogin')"
          :placeholder="t('circle.exactLogin')"
        />
      </div>
      <Button type="submit" variant="outline" :disabled="search.busy">{{
        t('circle.find')
      }}</Button>
    </form>
    <p v-if="search.error" class="text-destructive -mt-4 text-sm" role="alert">
      {{ search.error }}
    </p>

    <RefreshFailed :query="relationships" />
    <LoadingState v-if="relationships.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="relationships.isLoadingError.value"
      :error="relationships.error.value"
      @retry="relationships.refetch()"
    />
    <template v-else>
      <EmptyState
        v-if="!relationships.data.value?.relationships.length"
        :icon="UserRound"
        :title="t('circle.noFriends')"
        :description="t('circle.noFriendsText')"
      />
      <section
        v-for="group in (['incoming', 'friends', 'outgoing', 'blocked'] as const).filter(
          (name) => groups[name].length,
        )"
        :key="group"
        class="space-y-2"
      >
        <h2 class="font-semibold">{{ t(`circle.group.${group}`) }}</h2>
        <ul class="bg-card divide-y rounded-3xl border" :data-testid="`people-${group}`">
          <li v-for="item in groups[group]" :key="item.user_id">
            <button
              type="button"
              class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3.5 text-left"
              @click="open({ id: item.user_id, name: item.name, login: item.login ?? null })"
            >
              <span class="min-w-0 flex-1">
                <span class="block truncate font-medium">
                  {{ item.name }}
                  <span v-if="item.login" class="text-muted-foreground font-normal"
                    >@{{ item.login }}</span
                  >
                </span>
                <span class="text-muted-foreground block text-sm">{{ state(item) }}</span>
              </span>
              <Badge v-if="group === 'incoming'" variant="warning">{{ t('circle.new') }}</Badge>
              <ChevronRight class="text-muted-foreground size-4" />
            </button>
          </li>
        </ul>
      </section>
    </template>
  </div>

  <Panel
    v-model:open="personOpen"
    :title="person?.name ?? ''"
    :description="person?.login ? `@${person.login}` : undefined"
  >
    <template v-if="person">
      <p class="text-muted-foreground text-sm" data-testid="relation-state">
        {{ relation ? state(relation) : t('circle.notConnected') }}
      </p>

      <!-- G16: blocking is final for talking; data rights are managed separately. -->
      <Alert v-if="relation?.state === 'blocked'" variant="warning" data-testid="blocked">
        <AlertTitle>{{ t('circle.blockedTitle', { name: person.name }) }}</AlertTitle>
        <AlertDescription>
          <p>{{ t('circle.blockedText') }}</p>
          <RouterLink
            :to="{ name: 'settings-sharing' }"
            class="text-primary underline-offset-4 hover:underline"
          >
            {{ t('circle.checkAccess') }}
          </RouterLink>
        </AlertDescription>
      </Alert>

      <div class="grid grid-cols-1 gap-2">
        <Button
          v-if="!relation"
          :disabled="actions.busy.value"
          @click="change('request', t('circle.requested'))"
        >
          {{ t('circle.addFriend') }}
        </Button>
        <Button
          v-if="relation?.state === 'pending' && relation.recipient_id === session.me?.id"
          :disabled="actions.busy.value"
          @click="change('accept', t('circle.accepted'))"
        >
          {{ t('circle.accept') }}
        </Button>
        <Button v-if="relation?.state === 'accepted'" :disabled="actions.busy.value" @click="write">
          {{ t('circle.write') }}
        </Button>
      </div>

      <form
        v-if="relation?.state === 'accepted'"
        class="space-y-2"
        novalidate
        @submit.prevent="change('label', t('circle.labelSaved'), { label: label.trim() })"
      >
        <Label for="relation-label">{{ t('circle.label') }}</Label>
        <div class="flex gap-2">
          <Input
            id="relation-label"
            v-model="label"
            maxlength="80"
            :placeholder="t('circle.labelExample')"
          />
          <Button type="submit" variant="outline" :disabled="actions.busy.value">{{
            t('common.save')
          }}</Button>
        </div>
      </form>

      <div class="grid grid-cols-1 gap-2 border-t pt-4">
        <ReportButton target-type="user" :target-id="person.id" />
        <Button
          v-if="relation?.state === 'blocked'"
          variant="outline"
          :disabled="actions.busy.value"
          @click="change('unblock', t('circle.unblocked'))"
        >
          {{ t('circle.unblock') }}
        </Button>
        <Button
          v-else
          variant="ghost"
          class="text-destructive"
          :disabled="actions.busy.value"
          @click="change('block', t('circle.blockedDone', { name: person.name }))"
        >
          {{ t('circle.block') }}
        </Button>
        <Button
          v-if="relation && relation.state !== 'blocked'"
          variant="ghost"
          :disabled="actions.busy.value"
          @click="change('remove', t('circle.removed'))"
        >
          {{
            relation.state !== 'pending'
              ? t('circle.remove')
              : relation.recipient_id === session.me?.id
                ? t('circle.decline')
                : t('circle.cancelRequest')
          }}
        </Button>
      </div>
    </template>
  </Panel>
</template>
