<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { ChevronRight, Eye, MailOpen, MessageCircle, Plus, Users } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { Sharing, readScope } from '@/sections/settings/sharing'
import { useSession } from '@/stores/session'
import type { Invitation } from './api'
import { useChannels, useCircleActions, useSpaces } from './useCircle'

/**
 * G01/G15: people and shared spaces, kept apart from personal data. Unread messages and
 * invitations belong to their place, not to a common feed.
 */
const { t } = useI18n()
const router = useRouter()
const spaces = useSpaces()
const channels = useChannels()
const actions = useCircleActions()
// «Открыто мне»: sections other people opened to me, counted as the screen itself shows them.
const session = useSession()
const sharing = useQuery({
  queryKey: ['shared', 'mine'],
  queryFn: async ({ signal }) => Sharing.parse(await api.get('/v1/me/sharing', signal)),
})
const openedToMe = computed(
  () =>
    (sharing.data.value?.shared ?? []).filter((item) => session.can(readScope(item.name))).length,
)

const unreadOf = (space: string) =>
  channels.data.value?.channels
    .filter((channel) => channel.space_id === space)
    .reduce((sum, channel) => sum + channel.unread, 0) ?? 0
const directUnread = computed(
  () =>
    channels.data.value?.channels
      .filter((channel) => channel.kind === 'direct')
      .reduce((sum, channel) => sum + channel.unread, 0) ?? 0,
)
const active = computed(() => spaces.data.value?.spaces.filter((space) => !space.archived) ?? [])

// G05: a name and a clear audience first; nobody is added automatically.
const creating = ref(false)
const name = ref('')
async function create() {
  const value = name.value.trim()
  if (!value) return
  const created = await actions.act(
    () => actions.post<{ id: string }>('/v1/spaces', { name: value }),
    t('circle.created'),
  )
  if (!created) return
  creating.value = false
  name.value = ''
  void router.push({ name: 'circle-space', params: { space: created.id } })
}

// G06: joining is a conscious step; the whole shared history opens.
const invitation = ref<Invitation | null>(null)
const invitationOpen = computed({
  get: () => invitation.value !== null,
  set: (value: boolean) => {
    if (!value) invitation.value = null
  },
})
async function join() {
  const current = invitation.value
  if (!current) return
  const joined = await actions.act(
    () => actions.post(`/v1/spaces/${encodeURIComponent(current.space_id)}/join`, {}),
    t('circle.joined', { name: current.name }),
  )
  if (!joined) return
  invitation.value = null
  void router.push({ name: 'circle-space', params: { space: current.space_id } })
}
</script>

<template>
  <PageHeader :title="t('circle.title')" />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <RefreshFailed :query="spaces" />
    <LoadingState v-if="spaces.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="spaces.isLoadingError.value"
      :error="spaces.error.value"
      @retry="spaces.refetch()"
    />
    <template v-else-if="spaces.data.value">
      <section
        v-if="spaces.data.value.invitations.length"
        class="space-y-2"
        aria-labelledby="invitations-title"
      >
        <h2 id="invitations-title" class="font-semibold">{{ t('circle.invitations') }}</h2>
        <ul class="bg-card divide-y rounded-3xl border" data-testid="invitations">
          <li v-for="item in spaces.data.value.invitations" :key="item.space_id">
            <button
              type="button"
              class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3.5 text-left"
              @click="invitation = item"
            >
              <MailOpen class="text-primary size-5 shrink-0" />
              <span class="min-w-0 flex-1">
                <span class="block font-medium">{{ item.name }}</span>
                <span class="text-muted-foreground block text-sm">{{
                  item.inviter_name
                    ? t('circle.invitedBy', { name: item.inviter_name })
                    : t('circle.invited')
                }}</span>
              </span>
              <ChevronRight class="text-muted-foreground size-4" />
            </button>
          </li>
        </ul>
      </section>

      <RouterLink
        :to="{ name: 'circle-friends' }"
        class="bg-card hover:bg-accent/40 flex items-center gap-3 rounded-3xl border px-4 py-3.5"
      >
        <MessageCircle class="text-muted-foreground size-5 shrink-0" />
        <span class="min-w-0 flex-1">
          <span class="block font-medium">{{ t('circle.friends') }}</span>
          <span class="text-muted-foreground block text-sm">{{ t('circle.friendsText') }}</span>
        </span>
        <Badge v-if="directUnread">{{ directUnread }}</Badge>
        <ChevronRight class="text-muted-foreground size-4" />
      </RouterLink>
      <RouterLink
        :to="{ name: 'circle-shared' }"
        class="bg-card hover:bg-accent/40 flex items-center gap-3 rounded-3xl border px-4 py-3.5"
        data-testid="circle-shared"
      >
        <Eye class="text-muted-foreground size-5 shrink-0" />
        <span class="min-w-0 flex-1">
          <span class="block font-medium">{{ t('shared.title') }}</span>
          <span class="text-muted-foreground block text-sm">{{ t('circle.sharedText') }}</span>
        </span>
        <Badge v-if="openedToMe" variant="secondary">{{ openedToMe }}</Badge>
        <ChevronRight class="text-muted-foreground size-4" />
      </RouterLink>

      <section class="space-y-2" aria-labelledby="spaces-title">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <h2 id="spaces-title" class="font-semibold">{{ t('circle.spaces') }}</h2>
          <Button variant="ghost" size="sm" @click="creating = true">
            <Plus />{{ t('circle.create') }}
          </Button>
        </div>
        <p v-if="!active.length" class="text-muted-foreground px-1 text-sm">
          {{ t('circle.noSpaces') }}
        </p>
        <ul v-else class="bg-card divide-y rounded-3xl border" data-testid="spaces">
          <li v-for="space in active" :key="space.id">
            <RouterLink
              :to="{ name: 'circle-space', params: { space: space.id } }"
              class="hover:bg-accent/60 flex items-center gap-3 px-4 py-3.5"
            >
              <Users class="text-muted-foreground size-5 shrink-0" />
              <span class="min-w-0 flex-1">
                <span class="block truncate font-medium">{{ space.name }}</span>
                <span class="text-muted-foreground block text-sm">{{
                  t(`circle.role.${space.role}`)
                }}</span>
              </span>
              <Badge v-if="unreadOf(space.id)" :aria-label="t('circle.unread')">{{
                unreadOf(space.id)
              }}</Badge>
              <ChevronRight class="text-muted-foreground size-4" />
            </RouterLink>
          </li>
        </ul>
      </section>
    </template>
  </div>

  <Panel v-model:open="creating" :title="t('circle.newSpace')">
    <form class="space-y-4" novalidate @submit.prevent="create">
      <div class="space-y-2">
        <Label for="space-name">{{ t('circle.spaceName') }}</Label>
        <Input
          id="space-name"
          v-model="name"
          maxlength="100"
          :placeholder="t('circle.spaceExample')"
        />
      </div>
      <Alert>
        <AlertTitle>{{ t('circle.historyShared') }}</AlertTitle>
        <AlertDescription>{{ t('circle.historySharedText') }}</AlertDescription>
      </Alert>
      <Button type="submit" size="lg" class="w-full" :disabled="!name.trim() || actions.busy.value">
        {{ t('circle.create') }}
      </Button>
    </form>
  </Panel>

  <Panel
    v-model:open="invitationOpen"
    :title="invitation?.name ?? ''"
    :description="
      invitation?.inviter_name
        ? t('circle.invitedBy', { name: invitation.inviter_name })
        : undefined
    "
  >
    <Alert>
      <AlertTitle>{{ t('circle.afterJoin') }}</AlertTitle>
      <AlertDescription>{{ t('circle.afterJoinText') }}</AlertDescription>
    </Alert>
    <div class="grid grid-cols-1 gap-2">
      <Button size="lg" :disabled="actions.busy.value" @click="join">{{ t('circle.join') }}</Button>
      <Button variant="ghost" @click="invitation = null">{{ t('circle.later') }}</Button>
    </div>
  </Panel>
</template>
