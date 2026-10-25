<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { useSession } from '@/stores/session'
import { useAccess, useUsers } from './useAdmin'

const props = defineProps<{ accountId: string; label: string }>()
const { t } = useI18n()
const session = useSession()
const queries = useQueryClient()
const access = useAccess()
const people = useUsers()
const open = ref(false)
const selected = ref<string[]>([])
const busy = ref(false)
const problem = ref('')
const assigned = computed(
  () =>
    new Set(
      (access.data.value?.grants ?? [])
        .filter((grant) => grant.accounts.includes(props.accountId))
        .map((grant) => grant.user_id),
    ),
)
// Active people who do not have this subscription yet; nobody loses it here.
const candidates = computed(() =>
  (people.data.value?.users ?? []).filter(
    (person) => person.status === 'active' && !assigned.value.has(person.id),
  ),
)
const names = computed(() =>
  (people.data.value?.users ?? [])
    .filter((person) => assigned.value.has(person.id))
    .map((person) => person.name)
    .join(', '),
)
function start() {
  selected.value = assigned.value.size ? [] : session.me ? [session.me.id] : []
  problem.value = ''
  open.value = true
}
async function save() {
  if (!selected.value.length || busy.value) return
  busy.value = true
  problem.value = ''
  try {
    // Adding is idempotent: a retry after a lost response grants nothing twice.
    await api.post(`/v1/admin/accounts/${encodeURIComponent(props.accountId)}/users`, {
      users: selected.value,
    })
    await queries.invalidateQueries({ queryKey: ['accounts'] })
    await queries.invalidateQueries({ queryKey: ['assistant-availability'] })
    await queries.invalidateQueries({ queryKey: ['speech'] })
    open.value = false
  } catch (error) {
    problem.value = errorText(error, t)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="space-y-2 text-sm" data-testid="account-access">
    <p v-if="access.isPending.value">{{ t('admin.accessChecking') }}</p>
    <p v-else-if="access.isLoadingError.value">{{ t('admin.accessUnknown') }}</p>
    <template v-else-if="assigned.size">
      <p>{{ t('admin.accessPeople', { names: names || t('admin.selectedPeople') }) }}</p>
      <Button v-if="candidates.length" size="sm" variant="outline" @click="start">{{
        t('admin.shareAccount')
      }}</Button>
      <RouterLink :to="{ name: 'admin-users' }" class="block underline">{{
        t('admin.manageAccess')
      }}</RouterLink>
    </template>
    <template v-else>
      <p>{{ t('admin.accessNobody') }}</p>
      <Button size="sm" @click="start">{{ t('admin.finishAccess') }}</Button>
    </template>
  </div>
  <Panel v-model:open="open" :title="t('admin.accessWho')" :description="label">
    <form class="space-y-4" @submit.prevent="save">
      <p>{{ t('admin.accessExplicit') }}</p>
      <Button
        v-if="!assigned.size"
        type="button"
        variant="outline"
        @click="selected = session.me ? [session.me.id] : []"
        >{{ t('admin.onlyMe') }}</Button
      >
      <fieldset class="space-y-3">
        <legend class="mb-2">{{ t('admin.selectedPeople') }}</legend>
        <Label v-for="person in candidates" :key="person.id" class="flex items-center gap-2">
          <Checkbox
            :model-value="selected.includes(person.id)"
            @update:model-value="
              (on) =>
                (selected =
                  on === true
                    ? [...new Set([...selected, person.id])]
                    : selected.filter((id) => id !== person.id))
            "
          />
          {{ person.name }}
        </Label>
      </fieldset>
      <p v-if="problem" role="alert">{{ problem }}</p>
      <Button type="submit" :disabled="busy || !selected.length || people.isLoadingError.value">{{
        t(assigned.size ? 'admin.shareAccount' : 'admin.finishAccess')
      }}</Button>
    </form>
  </Panel>
</template>
