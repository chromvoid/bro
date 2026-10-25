<script setup lang="ts">
import { ChevronRight, UserPlus } from '@lucide/vue'
import { computed, reactive, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { useSession } from '@/stores/session'
import { Person, ROLES, abilities, mayRemove, soleOwner, type Member, type Role } from './api'
import GoneState from './GoneState.vue'
import { isGone, useCircleActions, useMembers, useSpaces } from './useCircle'

/**
 * G11/G12/G19/G14/G20: roles in a space, invitations by exact login, leaving and archiving.
 * A space role is not a right to anyone's personal data.
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const spaceId = computed(() => String(route.params.space ?? ''))
const spaces = useSpaces()
const members = useMembers(computed(() => spaceId.value || null))
const actions = useCircleActions()
const space = computed(
  () => spaces.data.value?.spaces.find((item) => item.id === spaceId.value) ?? null,
)
const people = computed(() => members.data.value?.members ?? [])
const me = computed(() => people.value.find((person) => person.user_id === session.me?.id) ?? null)
const can = computed(() => (me.value ? abilities(me.value.role) : null))
const gone = computed(() => isGone(members.error.value))
const path = (tail: string) => `/v1/spaces/${encodeURIComponent(spaceId.value)}/${tail}`

// G12: the recipient and what they will see are clear before anything is sent.
const inviting = ref(false)
const invite = reactive({
  login: '',
  person: null as { id: string; name: string; login: string } | null,
  error: '',
  busy: false,
})
async function lookup() {
  const login = invite.login.trim().replace(/^@/, '')
  if (!login || invite.busy) return
  invite.busy = true
  invite.error = ''
  try {
    const { user } = Person.parse(
      await api.get(`/v1/relationships?login=${encodeURIComponent(login)}`),
    )
    if (!user) invite.error = t('access.notFound')
    else if (people.value.some((person) => person.user_id === user.id))
      invite.error = t('circle.alreadyMember')
    else invite.person = user
  } catch (error) {
    invite.error = errorText(error, t)
  } finally {
    invite.busy = false
  }
}
async function sendInvite() {
  const person = invite.person
  if (!person) return
  const done = await actions.act(
    () => actions.post(path('invitations'), { user_id: person.id }),
    t('circle.inviteSent', { name: person.name }),
  )
  if (done) {
    inviting.value = false
    Object.assign(invite, { login: '', person: null, error: '' })
  }
}

// G19: a person's role; removing someone takes away every right they had in the space.
const selected = shallowRef<Member | null>(null)
const selectedOpen = computed({
  get: () => selected.value !== null,
  set: (value: boolean) => {
    if (!value) selected.value = null
  },
})
const role = ref<Role>('member')
function open(person: Member) {
  selected.value = person
  role.value = person.role
}
async function saveRole() {
  const person = selected.value
  if (!person) return
  const done = await actions.act(
    () => actions.post(path('members'), { user_id: person.user_id, role: role.value }),
    t('circle.roleSaved'),
  )
  if (done) selected.value = null
}
async function remove(person: Member) {
  const done = await actions.act(
    () => actions.post(path('members'), { user_id: person.user_id, role: null }),
    t('circle.removedMember', { name: person.name }),
  )
  if (done) selected.value = null
}

// G20/G14: leaving keeps personal data; the only owner hands over ownership or archives.
const leaving = ref(false)
const archiving = ref(false)
async function leave() {
  leaving.value = false
  const done = await actions.act(
    () => actions.post(path('members'), { user_id: session.me!.id, role: null }),
    t('circle.left', { name: space.value?.name ?? '' }),
  )
  if (done) void router.replace({ name: 'circle' })
}
async function archive() {
  archiving.value = false
  const current = space.value
  if (!current) return
  const done = await actions.act(
    () => actions.post(path('archive'), { revision: current.revision }),
    t('circle.archived', { name: current.name }),
  )
  if (done) void router.replace({ name: 'circle' })
}
</script>

<template>
  <PageHeader
    :title="t('circle.members')"
    :description="space?.name"
    :back="{ name: 'circle-space', params: { space: spaceId } }"
  />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <GoneState v-if="gone" />
    <LoadingState v-else-if="members.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="members.isLoadingError.value"
      :error="members.error.value"
      @retry="members.refetch()"
    />
    <template v-else>
      <RefreshFailed :query="members" />
      <ul class="bg-card divide-y rounded-3xl border" data-testid="members">
        <li v-for="person in people" :key="person.user_id">
          <button
            type="button"
            class="hover:bg-accent/60 flex w-full items-center gap-3 px-4 py-3.5 text-left disabled:cursor-default"
            :disabled="!me || (!can?.changeRoles && !mayRemove(me, person))"
            @click="open(person)"
          >
            <span class="min-w-0 flex-1">
              <span class="block truncate font-medium">
                {{ person.name }}
                <template v-if="person.user_id === session.me?.id">
                  · {{ t('circle.you').toLowerCase() }}</template
                >
              </span>
              <span v-if="person.login" class="text-muted-foreground block text-sm"
                >@{{ person.login }}</span
              >
            </span>
            <Badge :variant="person.role === 'member' ? 'secondary' : 'default'">{{
              t(`circle.role.${person.role}`)
            }}</Badge>
            <ChevronRight
              v-if="me && (can?.changeRoles || mayRemove(me, person))"
              class="text-muted-foreground size-4"
            />
          </button>
        </li>
      </ul>

      <Button v-if="can?.invite" @click="inviting = true"
        ><UserPlus />{{ t('circle.invite') }}</Button
      >
      <Alert>
        <AlertTitle>{{ t('circle.historyShared') }}</AlertTitle>
        <AlertDescription>{{ t('circle.historySharedText') }}</AlertDescription>
      </Alert>

      <div class="grid grid-cols-1 gap-2 border-t pt-4">
        <p
          v-if="me && soleOwner(people, me.user_id)"
          class="text-muted-foreground text-sm"
          data-testid="sole-owner"
        >
          {{ t('circle.soleOwner', { name: space?.name ?? '' }) }}
        </p>
        <Button v-else variant="outline" class="text-destructive" @click="leaving = true">
          {{ t('circle.leave') }}
        </Button>
        <Button
          v-if="can?.archive"
          variant="ghost"
          class="text-destructive"
          @click="archiving = true"
        >
          {{ t('circle.archive') }}
        </Button>
      </div>
    </template>
  </div>

  <Panel v-model:open="inviting" :title="t('circle.inviteTitle', { name: space?.name ?? '' })">
    <form v-if="!invite.person" class="space-y-3" novalidate @submit.prevent="lookup">
      <Label for="invite-login">{{ t('access.login') }}</Label>
      <Input
        id="invite-login"
        v-model="invite.login"
        autocomplete="off"
        autocapitalize="none"
        spellcheck="false"
        :placeholder="t('access.loginExample')"
      />
      <p v-if="invite.error" class="text-destructive text-sm" role="alert">{{ invite.error }}</p>
      <Button type="submit" class="w-full" :disabled="invite.busy">{{
        t('common.continue')
      }}</Button>
    </form>
    <div v-else class="space-y-3" data-testid="invite-check">
      <Alert>
        <AlertTitle>{{ invite.person.name }} · @{{ invite.person.login }}</AlertTitle>
        <AlertDescription>{{ t('circle.inviteText') }}</AlertDescription>
      </Alert>
      <div class="grid grid-cols-1 gap-2">
        <Button size="lg" :disabled="actions.busy.value" @click="sendInvite">{{
          t('circle.sendInvite')
        }}</Button>
        <Button variant="ghost" @click="invite.person = null">{{ t('common.back') }}</Button>
      </div>
    </div>
  </Panel>

  <Panel v-model:open="selectedOpen" :title="selected?.name ?? ''" :description="space?.name">
    <template v-if="selected && me">
      <fieldset v-if="can?.changeRoles" class="space-y-2">
        <legend class="mb-2 text-sm font-medium">{{ t('circle.roleTitle') }}</legend>
        <RadioGroup v-model="role" class="gap-2">
          <Label
            v-for="option in [...ROLES].reverse()"
            :key="option"
            class="has-[[data-state=checked]]:border-primary flex items-start gap-3 rounded-2xl border p-3 font-normal"
          >
            <RadioGroupItem :value="option" class="mt-0.5" />
            <span>
              <span class="block font-medium">{{ t(`circle.role.${option}`) }}</span>
              <span class="text-muted-foreground block text-sm">{{
                t(`circle.roleText.${option}`)
              }}</span>
            </span>
          </Label>
        </RadioGroup>
        <p v-if="role === 'owner' && selected.role !== 'owner'" class="text-warning text-sm">
          {{ t('circle.ownerWarning') }}
        </p>
        <Button
          class="w-full"
          :disabled="role === selected.role || actions.busy.value"
          @click="saveRole"
        >
          {{ t('circle.saveRole') }}
        </Button>
      </fieldset>
      <Button
        v-if="selected.user_id !== me.user_id && mayRemove(me, selected)"
        variant="ghost"
        class="text-destructive w-full"
        :disabled="actions.busy.value"
        @click="remove(selected)"
      >
        {{ t('circle.removeMember') }}
      </Button>
    </template>
  </Panel>

  <Dialog v-model:open="leaving">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('circle.leaveTitle') }}</DialogTitle>
        <DialogDescription>{{
          t('circle.leaveText', { name: space?.name ?? '' })
        }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="leaving = false">{{ t('circle.stay') }}</Button>
        <Button variant="destructive" @click="leave">{{ t('circle.leave') }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
  <Dialog v-model:open="archiving">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('circle.archiveTitle', { name: space?.name ?? '' }) }}</DialogTitle>
        <DialogDescription>{{ t('circle.archiveText') }}</DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="archiving = false">{{ t('circle.keep') }}</Button>
        <Button variant="destructive" @click="archive">{{ t('circle.archive') }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
