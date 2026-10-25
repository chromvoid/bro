<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ChevronRight, Eye, ListTodo, Lock, UserPlus, Users, UsersRound } from '@lucide/vue'
import { computed, reactive, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
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
import { useRelationships, useSpaces, useTaskLists } from '@/sections/circle/useCircle'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { useSession } from '@/stores/session'
import {
  PERMISSIONS,
  Person,
  Sharing,
  grantsOf,
  type Grant,
  type Permission,
  type Section,
  type SectionName,
} from './sharing'

/**
 * «Доступ и обмен»: everything shared, in one place. My data — who can see which of my sections
 * (S03–S05, S07): opening access is checked before it is granted and says exactly what the
 * person will see; revoking asks once. Then where I share: spaces, task lists and people, each
 * managed on its own screen. What others opened to me is in Circle («Открыто мне»).
 */
const { t } = useI18n()
const session = useSession()
const queries = useQueryClient()
const sharing = useQuery({
  queryKey: ['shared', 'mine'],
  queryFn: async ({ signal }) => Sharing.parse(await api.get('/v1/me/sharing', signal)),
})
const sections = computed(() => sharing.data.value?.sections ?? [])
const refresh = () => queries.invalidateQueries({ queryKey: ['shared'] })

const spaces = useSpaces()
const mySpaces = computed(() => spaces.data.value?.spaces.filter((space) => !space.archived) ?? [])
const lists = useTaskLists()
// A list is shared when it belongs to a space; personal lists are mine alone.
const sharedLists = computed(() => lists.data.value?.lists.filter((list) => list.space_id) ?? [])
const relationships = useRelationships()
const people = computed(() => {
  const all = relationships.data.value?.relationships ?? []
  const me = session.me?.id
  return {
    friends: all.filter((item) => item.state === 'accepted').length,
    incoming: all.filter((item) => item.state === 'pending' && item.recipient_id === me).length,
    blocked: all.filter((item) => item.state === 'blocked').length,
  }
})

// S04 → S05: choose the person and the level, then confirm what they will see.
const opening = ref(false)
const form = reactive({
  section: 'nutrition' as SectionName,
  login: '',
  permission: 'summary' as Permission,
  busy: false,
  error: '',
})
const person = shallowRef<{ id: string; name: string; login: string } | null>(null)
function startOpening(section: Section) {
  Object.assign(form, {
    section: section.name as SectionName,
    login: '',
    permission: 'summary',
    error: '',
  })
  person.value = null
  opening.value = true
}
async function lookup() {
  const login = form.login.trim().replace(/^@/, '')
  form.error = login ? '' : t('access.loginRequired')
  if (form.error || form.busy) return
  form.busy = true
  try {
    const { user } = Person.parse(
      await api.get(`/v1/relationships?login=${encodeURIComponent(login)}`),
    )
    if (!user) form.error = t('access.notFound')
    else if (user.id === session.me?.id) form.error = t('access.notYourself')
    else person.value = user
  } catch (error) {
    form.error = errorText(error, t)
  } finally {
    form.busy = false
  }
}
const target = computed(() => sections.value.find((section) => section.name === form.section))
async function grant() {
  if (!person.value || !target.value || form.busy) return
  form.busy = true
  try {
    await api.post(`/v1/resources/${encodeURIComponent(target.value.id)}/grants`, {
      user_id: person.value.id,
      permission: form.permission,
      revision: target.value.revision,
    })
    toast.success(t('access.opened', { name: person.value.name }))
    opening.value = false
  } catch (error) {
    // Someone changed this section's access meanwhile: re-read before trying again.
    form.error =
      error instanceof ApiError && error.status === 409 ? t('access.changed') : errorText(error, t)
    person.value = null
  } finally {
    form.busy = false
    await refresh()
  }
}

// S07: revoking takes effect at once; the person keeps nothing they had not already seen.
const revoking = shallowRef<{ grant: Grant; section: Section } | null>(null)
const revokeOpen = computed({
  get: () => revoking.value !== null,
  set: (value: boolean) => {
    if (!value) revoking.value = null
  },
})
const revokeBusy = ref(false)
async function revoke() {
  const current = revoking.value
  if (!current || revokeBusy.value) return
  revokeBusy.value = true
  try {
    await api.post(`/v1/resources/${encodeURIComponent(current.section.id)}/grants`, {
      user_id: current.grant.user_id,
      permission: null,
      revision: current.section.revision,
    })
    toast.success(t('access.revoked', { name: current.grant.name }))
    revoking.value = null
  } catch (error) {
    toast.error(
      error instanceof ApiError && error.status === 409 ? t('access.changed') : errorText(error, t),
    )
  } finally {
    revokeBusy.value = false
    await refresh()
  }
}
</script>

<template>
  <PageHeader :title="t('sharing.title')" :back="{ name: 'settings' }" />
  <div class="mx-auto max-w-3xl space-y-6 px-4 pb-8 md:px-8">
    <!-- One notice for the page: a failed refresh usually fails for all of its parts at once. -->
    <RefreshFailed :query="[sharing, spaces, lists, relationships]" />
    <section class="space-y-3" aria-labelledby="sharing-mine">
      <div>
        <h2 id="sharing-mine" class="text-lg font-semibold">{{ t('sharing.mine') }}</h2>
        <p class="text-muted-foreground text-sm">{{ t('sharing.mineText') }}</p>
      </div>
      <Alert>
        <Lock />
        <AlertTitle>{{ t('access.readOnly') }}</AlertTitle>
        <AlertDescription>{{ t('access.readOnlyText') }}</AlertDescription>
      </Alert>
      <LoadingState v-if="sharing.isPending.value" :rows="2" />
      <ErrorState
        v-else-if="sharing.isLoadingError.value"
        :error="sharing.error.value"
        @retry="sharing.refetch()"
      />
      <template v-else-if="sharing.data.value">
        <section
          v-for="section in sections"
          :key="section.id"
          class="bg-card space-y-3 rounded-3xl border p-5"
          :data-testid="`access-${section.name}`"
        >
          <div>
            <h2 class="font-semibold">{{ t(`access.section.${section.name}`) }}</h2>
            <p class="text-muted-foreground text-sm">
              {{ t(`access.sectionText.${section.name}`) }}
            </p>
          </div>
          <ul v-if="grantsOf(sharing.data.value, section).length" class="divide-y border-y">
            <li
              v-for="item in grantsOf(sharing.data.value, section)"
              :key="item.user_id"
              class="flex items-center gap-3 py-2.5"
            >
              <div class="min-w-0 flex-1">
                <p class="truncate font-medium">
                  {{ item.name }}
                  <span v-if="item.login" class="text-muted-foreground font-normal"
                    >@{{ item.login }}</span
                  >
                </p>
                <Badge variant="secondary">{{ t(`access.permission.${item.permission}`) }}</Badge>
              </div>
              <Button
                size="sm"
                variant="ghost"
                class="text-destructive"
                @click="revoking = { grant: item, section }"
              >
                {{ t('access.revoke') }}
              </Button>
            </li>
          </ul>
          <p v-else class="text-muted-foreground text-sm">{{ t('access.nobody') }}</p>
          <Button variant="outline" size="sm" @click="startOpening(section)">
            <UserPlus />{{ t('access.open') }}
          </Button>
        </section>
      </template>
    </section>

    <section class="space-y-3" aria-labelledby="sharing-spaces" data-testid="sharing-spaces">
      <div>
        <h2 id="sharing-spaces" class="text-lg font-semibold">{{ t('sharing.spaces') }}</h2>
        <p class="text-muted-foreground text-sm">{{ t('sharing.spacesText') }}</p>
      </div>
      <ErrorState
        v-if="spaces.isLoadingError.value"
        :error="spaces.error.value"
        @retry="spaces.refetch()"
      />
      <ul v-else-if="mySpaces.length" class="bg-card divide-y rounded-3xl border">
        <li v-for="space in mySpaces" :key="space.id">
          <RouterLink
            :to="{ name: 'circle-members', params: { space: space.id } }"
            class="hover:bg-accent/40 flex items-center gap-3 px-4 py-3"
          >
            <Users class="text-muted-foreground size-5 shrink-0" />
            <span class="min-w-0 flex-1">
              <span class="block truncate font-medium">{{ space.name }}</span>
              <span class="text-muted-foreground block text-sm">{{
                t(`circle.role.${space.role}`)
              }}</span>
            </span>
            <span class="text-muted-foreground text-sm">{{ t('sharing.membersLink') }}</span>
            <ChevronRight class="text-muted-foreground size-4" />
          </RouterLink>
        </li>
      </ul>
      <p v-else-if="!spaces.isPending.value" class="text-muted-foreground text-sm">
        {{ t('sharing.spacesEmpty') }}
      </p>
    </section>

    <section
      v-if="session.can('tasks.read')"
      class="space-y-3"
      aria-labelledby="sharing-lists"
      data-testid="sharing-lists"
    >
      <div>
        <h2 id="sharing-lists" class="text-lg font-semibold">{{ t('sharing.lists') }}</h2>
        <p class="text-muted-foreground text-sm">{{ t('sharing.listsText') }}</p>
      </div>
      <ErrorState
        v-if="lists.isLoadingError.value"
        :error="lists.error.value"
        @retry="lists.refetch()"
      />
      <ul v-else-if="sharedLists.length" class="bg-card divide-y rounded-3xl border">
        <li v-for="list in sharedLists" :key="list.id">
          <RouterLink
            :to="{ name: 'circle-list', params: { list: list.id } }"
            class="hover:bg-accent/40 flex items-center gap-3 px-4 py-3"
          >
            <ListTodo class="text-muted-foreground size-5 shrink-0" />
            <span class="min-w-0 flex-1">
              <span class="block truncate font-medium">{{ list.name }}</span>
              <span class="text-muted-foreground block text-sm">{{
                t(`circle.effective.${list.effective}`)
              }}</span>
            </span>
            <ChevronRight class="text-muted-foreground size-4" />
          </RouterLink>
        </li>
      </ul>
      <p v-else-if="!lists.isPending.value" class="text-muted-foreground text-sm">
        {{ t('sharing.listsEmpty') }}
      </p>
    </section>

    <section class="space-y-3" aria-labelledby="sharing-people" data-testid="sharing-people">
      <h2 id="sharing-people" class="text-lg font-semibold">{{ t('sharing.people') }}</h2>
      <RouterLink
        :to="{ name: 'circle-friends' }"
        class="bg-card hover:bg-accent/40 flex items-center gap-3 rounded-3xl border px-4 py-3.5"
      >
        <UsersRound class="text-muted-foreground size-5 shrink-0" />
        <span class="min-w-0 flex-1">
          <span class="block font-medium">{{ t('circle.friends') }}</span>
          <span class="text-muted-foreground block text-sm">{{
            t('sharing.peopleCount', people)
          }}</span>
        </span>
        <ChevronRight class="text-muted-foreground size-4" />
      </RouterLink>
      <p class="text-muted-foreground text-sm">{{ t('sharing.blockNote') }}</p>
    </section>
  </div>

  <Panel v-model:open="opening" :title="person ? t('access.checkTitle') : t('access.openTitle')">
    <form v-if="!person" class="space-y-4" novalidate @submit.prevent="lookup">
      <div class="space-y-2">
        <Label for="access-login">{{ t('access.login') }}</Label>
        <Input
          id="access-login"
          v-model="form.login"
          autocomplete="off"
          autocapitalize="none"
          spellcheck="false"
          :placeholder="t('access.loginExample')"
        />
        <p class="text-muted-foreground text-sm">{{ t('access.loginHint') }}</p>
      </div>
      <fieldset class="space-y-2">
        <legend class="mb-2 text-sm font-medium">{{ t('access.what') }}</legend>
        <RadioGroup v-model="form.permission" class="gap-2">
          <Label
            v-for="permission in PERMISSIONS"
            :key="permission"
            class="has-[[data-state=checked]]:border-primary flex items-start gap-3 rounded-2xl border p-3 font-normal"
          >
            <RadioGroupItem :value="permission" class="mt-0.5" />
            <span>
              <span class="block font-medium">{{ t(`access.permission.${permission}`) }}</span>
              <span class="text-muted-foreground block text-sm">{{
                t(`access.preview.${form.section}.${permission}`)
              }}</span>
            </span>
          </Label>
        </RadioGroup>
      </fieldset>
      <p v-if="form.error" class="text-destructive text-sm" role="alert">{{ form.error }}</p>
      <Button type="submit" size="lg" class="w-full" :disabled="form.busy">{{
        t('common.continue')
      }}</Button>
    </form>

    <div v-else class="space-y-4" data-testid="access-check">
      <div class="bg-muted/60 space-y-2 rounded-2xl p-4">
        <p class="flex items-center gap-2 font-medium">
          <Eye class="size-4" />
          {{
            t('access.willSee', {
              name: person.name,
              login: person.login,
              section: t(`access.section.${form.section}`).toLowerCase(),
            })
          }}
        </p>
        <p class="text-sm">{{ t(`access.preview.${form.section}.${form.permission}`) }}</p>
        <p class="text-muted-foreground text-sm">{{ t('access.cannot') }}</p>
      </div>
      <p v-if="form.error" class="text-destructive text-sm" role="alert">{{ form.error }}</p>
      <div class="grid grid-cols-1 gap-2">
        <Button size="lg" :disabled="form.busy" @click="grant">{{ t('access.confirm') }}</Button>
        <Button variant="ghost" :disabled="form.busy" @click="person = null">{{
          t('common.back')
        }}</Button>
      </div>
    </div>
  </Panel>

  <Dialog v-model:open="revokeOpen">
    <DialogContent v-if="revoking">
      <DialogHeader>
        <DialogTitle>{{ t('access.revokeTitle') }}</DialogTitle>
        <DialogDescription>
          {{
            t('access.revokeText', {
              name: revoking.grant.name,
              section: t(`access.section.${revoking.section.name}`).toLowerCase(),
            })
          }}
        </DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" @click="revoking = null">{{ t('common.cancel') }}</Button>
        <Button variant="destructive" :disabled="revokeBusy" @click="revoke">{{
          t('access.revoke')
        }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
