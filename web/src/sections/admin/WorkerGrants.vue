<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import { executorLabel, type ExecutorGrant, type WorkersSnapshot } from './api'
import { useUsers } from './useAdmin'

const props = defineProps<{ snapshot: WorkersSnapshot; busy: boolean }>()
const emit = defineEmits<{
  grant: [payload: Record<string, unknown>, expected: Record<string, number>]
  revoke: [payload: Record<string, unknown>, expected: Record<string, number>]
}>()
const { t } = useI18n()
const users = useUsers()
const executor = ref('')
const user = ref('')
const selected = computed(() => props.snapshot.executors.find((item) => item.id === executor.value))
const profile = computed(() =>
  props.snapshot.profiles.find((item) => item.id === selected.value?.profile_id),
)
const activeUsers = computed(
  () => users.data.value?.users.filter((item) => item.status === 'active') ?? [],
)
const person = (id: string) => users.data.value?.users.find((item) => item.id === id)?.name ?? id
const executorName = (id: string) => {
  const item = props.snapshot.executors.find((candidate) => candidate.id === id)
  return item ? executorLabel(props.snapshot, item) : id
}
const current = (grant: ExecutorGrant) =>
  grant.enabled &&
  props.snapshot.profiles.some(
    (item) => item.id === grant.profile_id && item.revision === grant.profile_revision,
  )
function save() {
  if (!executor.value || !user.value) return
  const existing = props.snapshot.executor_grants.find(
    (item) => item.executor_id === executor.value && item.grantee_user_id === user.value,
  )
  emit(
    'grant',
    { executor_id: executor.value, user_id: user.value },
    existing ? { [existing.id]: existing.revision } : {},
  )
}
</script>

<template>
  <section class="space-y-3" aria-labelledby="worker-grants-title">
    <h2 id="worker-grants-title" class="font-semibold">{{ t('admin.workerGrantsTitle') }}</h2>
    <p class="text-muted-foreground text-sm">{{ t('admin.workerGrantsHelp') }}</p>
    <form class="grid gap-2 sm:grid-cols-2" @submit.prevent="save">
      <NativeSelect v-model="executor" :aria-label="t('admin.executors')">
        <option value="" disabled>{{ t('admin.executors') }}</option>
        <option v-for="item in snapshot.executors" :key="item.id" :value="item.id">
          {{ executorLabel(snapshot, item) }}
        </option>
      </NativeSelect>
      <NativeSelect v-model="user" :aria-label="t('admin.workerGrantUser')">
        <option value="" disabled>{{ t('admin.workerGrantUser') }}</option>
        <option v-for="item in activeUsers" :key="item.id" :value="item.id">{{ item.name }}</option>
      </NativeSelect>
      <p v-if="profile" class="text-muted-foreground text-sm sm:col-span-2">
        {{ profile.workspace }} · {{ t(`admin.permissions.${profile.permissions}`) }}
      </p>
      <Button
        type="submit"
        variant="outline"
        :disabled="busy || !executor || !user"
        class="sm:col-span-2"
      >
        {{ t('admin.workerGrantSave') }}
      </Button>
    </form>
    <ul v-if="snapshot.executor_grants.length" class="bg-card divide-y rounded-3xl border">
      <li
        v-for="grant in snapshot.executor_grants"
        :key="grant.id"
        class="flex items-center gap-3 px-4 py-3"
      >
        <div class="min-w-0 flex-1">
          <p class="font-medium">
            {{ person(grant.grantee_user_id) }} · {{ executorName(grant.executor_id) }}
          </p>
          <p class="text-muted-foreground text-sm">
            {{ grant.workspace }} · {{ t(`admin.permissions.${grant.permissions}`) }} ·
            {{ t(current(grant) ? 'admin.workerGrantActive' : 'admin.workerGrantInactive') }}
          </p>
        </div>
        <Button
          v-if="grant.enabled"
          size="sm"
          variant="ghost"
          :disabled="busy"
          @click="emit('revoke', { id: grant.id }, { [grant.id]: grant.revision })"
        >
          {{ t('admin.workerGrantRevoke') }}
        </Button>
      </li>
    </ul>
  </section>
</template>
