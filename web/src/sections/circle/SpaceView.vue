<script setup lang="ts">
import { ChevronRight, ListTodo, MessagesSquare, Plus, Trophy, UsersRound } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useSession } from '@/stores/session'
import GoneState from './GoneState.vue'
import {
  isGone,
  useChannels,
  useCircleActions,
  useMembers,
  useSpaces,
  useTaskLists,
} from './useCircle'

/** G04: a space has its own home: its chat, lists, challenges and people. Personal data stays apart. */
const { t } = useI18n()
const route = useRoute()
const session = useSession()
const spaceId = computed(() => String(route.params.space ?? ''))
const spaces = useSpaces()
const members = useMembers(computed(() => spaceId.value || null))
const channels = useChannels()
const lists = useTaskLists()
const actions = useCircleActions()
const space = computed(
  () => spaces.data.value?.spaces.find((item) => item.id === spaceId.value) ?? null,
)
const gone = computed(
  () =>
    isGone(members.error.value) ||
    (!!spaces.data.value && (!space.value || !!space.value.archived)),
)
const chat = computed(
  () =>
    channels.data.value?.channels.find(
      (channel) => channel.space_id === spaceId.value && channel.kind === 'space',
    ) ?? null,
)
const spaceLists = computed(
  () => lists.data.value?.lists.filter((list) => list.space_id === spaceId.value) ?? [],
)
const people = computed(() => members.data.value?.members ?? [])
const initials = (name: string) =>
  name
    .split(/\s+/)
    .map((part) => part[0] ?? '')
    .join('')
    .slice(0, 2)
    .toUpperCase()

const creating = ref(false)
const listName = ref('')
async function createList() {
  const name = listName.value.trim()
  if (!name) return
  const created = await actions.act(
    () => actions.post('/v1/tasks/lists', { name, space_id: spaceId.value }),
    t('circle.listCreated'),
    ['tasks'],
  )
  if (created) {
    creating.value = false
    listName.value = ''
  }
}
</script>

<template>
  <PageHeader
    :title="space?.name ?? t('circle.space')"
    :description="
      people.length ? t('circle.membersCount', { count: people.length }, people.length) : undefined
    "
    :back="{ name: 'circle' }"
  />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <GoneState v-if="gone" />
    <LoadingState v-else-if="spaces.isPending.value || members.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="members.isLoadingError.value"
      :error="members.error.value"
      @retry="members.refetch()"
    />
    <template v-else-if="space">
      <RefreshFailed :query="[spaces, members]" />
      <div class="flex -space-x-2" :aria-label="t('circle.members')">
        <Avatar
          v-for="person in people.slice(0, 6)"
          :key="person.user_id"
          class="ring-background size-10 ring-2"
          :title="person.name"
        >
          <AvatarFallback>{{ initials(person.name) }}</AvatarFallback>
        </Avatar>
      </div>

      <nav class="bg-card divide-y rounded-3xl border" data-testid="space-home">
        <RouterLink
          v-if="chat"
          :to="{ name: 'circle-chat', params: { channel: chat.id } }"
          class="hover:bg-accent/60 flex items-center gap-3 px-4 py-3.5"
        >
          <MessagesSquare class="text-muted-foreground size-5 shrink-0" />
          <span class="min-w-0 flex-1">
            <span class="block font-medium">{{ t('circle.sharedChat') }}</span>
            <span class="text-muted-foreground block text-sm">{{
              chat.unread
                ? t('circle.newMessages', { count: chat.unread }, chat.unread)
                : t('circle.everyoneSees')
            }}</span>
          </span>
          <Badge v-if="chat.unread">{{ chat.unread }}</Badge>
          <ChevronRight class="text-muted-foreground size-4" />
        </RouterLink>
        <RouterLink
          v-for="list in spaceLists"
          :key="list.id"
          :to="{ name: 'circle-list', params: { list: list.id } }"
          class="hover:bg-accent/60 flex items-center gap-3 px-4 py-3.5"
        >
          <ListTodo class="text-muted-foreground size-5 shrink-0" />
          <span class="min-w-0 flex-1">
            <span class="block font-medium">{{ list.name }}</span>
            <span class="text-muted-foreground block text-sm">{{
              t(`circle.effective.${list.effective}`)
            }}</span>
          </span>
          <ChevronRight class="text-muted-foreground size-4" />
        </RouterLink>
        <RouterLink
          v-if="session.can('challenges.read')"
          :to="{ name: 'circle-challenges', params: { space: space.id } }"
          class="hover:bg-accent/60 flex items-center gap-3 px-4 py-3.5"
        >
          <Trophy class="text-muted-foreground size-5 shrink-0" />
          <span class="min-w-0 flex-1">
            <span class="block font-medium">{{ t('circle.challenges') }}</span>
            <span class="text-muted-foreground block text-sm">{{
              t('circle.challengesText')
            }}</span>
          </span>
          <ChevronRight class="text-muted-foreground size-4" />
        </RouterLink>
        <RouterLink
          :to="{ name: 'circle-members', params: { space: space.id } }"
          class="hover:bg-accent/60 flex items-center gap-3 px-4 py-3.5"
        >
          <UsersRound class="text-muted-foreground size-5 shrink-0" />
          <span class="min-w-0 flex-1">
            <span class="block font-medium">{{ t('circle.membersAndAccess') }}</span>
            <span class="text-muted-foreground block text-sm">{{
              t('circle.youAre', { role: t(`circle.role.${space.role}`).toLowerCase() })
            }}</span>
          </span>
          <ChevronRight class="text-muted-foreground size-4" />
        </RouterLink>
      </nav>

      <Button v-if="session.can('tasks.write')" variant="outline" @click="creating = true">
        <Plus />{{ t('circle.newList') }}
      </Button>
    </template>
  </div>

  <Panel v-model:open="creating" :title="t('circle.newList')">
    <form class="space-y-4" novalidate @submit.prevent="createList">
      <div class="space-y-2">
        <Label for="list-name">{{ t('circle.listName') }}</Label>
        <Input
          id="list-name"
          v-model="listName"
          maxlength="100"
          :placeholder="t('circle.listExample')"
        />
      </div>
      <Button
        type="submit"
        size="lg"
        class="w-full"
        :disabled="!listName.trim() || actions.busy.value"
      >
        {{ t('circle.createList') }}
      </Button>
    </form>
  </Panel>
</template>
