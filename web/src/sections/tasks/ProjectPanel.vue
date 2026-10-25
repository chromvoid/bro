<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { errorText } from '@/core/errors'
import { useLedger } from '@/core/operations'
import { TaskLists, type TaskList } from '@/sections/circle/api'
import { useSpaces } from '@/sections/circle/useCircle'
import { TASKS } from './api'
const props = defineProps<{ project: TaskList | null }>()
const emit = defineEmits<{ created: [id: string] }>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const ledger = useLedger(TASKS)
const spaces = useSpaces()
const writableSpaces = computed(
  () => spaces.data.value?.spaces.filter((space) => !space.archived) ?? [],
)
const busy = ref(false)
const baseRevision = ref(0)
const form = reactive({ name: '', outcome: '', state: 'active', space: '' })
watch([open, () => props.project?.id], () => {
  if (open.value) {
    baseRevision.value = props.project?.revision ?? 0
    Object.assign(form, {
      name: props.project?.name ?? '',
      outcome: props.project?.outcome ?? '',
      state: props.project?.project_state ?? 'active',
      space: props.project?.space_id ?? '',
    })
  }
})
async function save() {
  if (busy.value || !form.name.trim()) return
  busy.value = true
  try {
    if (props.project)
      await ledger.operate({
        kind: 'list.update',
        payload: {
          list_id: props.project.id,
          changes: {
            name: form.name.trim(),
            list_kind: 'project',
            outcome: form.outcome.trim() || null,
            project_state: form.state,
          },
        },
        expected_revisions: { [props.project.id]: baseRevision.value },
      })
    else {
      const receipt = await ledger.operate({
        kind: 'list.create',
        payload: {
          name: form.name.trim(),
          list_kind: 'project',
          outcome: form.outcome.trim() || null,
          ...(form.space ? { space_id: form.space } : {}),
        },
      })
      const id = TaskLists.parse(receipt).lists[0]?.id
      if (id) emit('created', id)
    }
    toast.success(t('tasks.saved'))
    open.value = false
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <Panel v-model:open="open" :title="project ? t('tasks.projectSettings') : t('tasks.newProject')">
    <form class="space-y-4" @submit.prevent="save">
      <div class="space-y-2">
        <Label for="project-name">{{ t('tasks.projectName') }}</Label
        ><Input id="project-name" v-model="form.name" maxlength="120" />
      </div>
      <div class="space-y-2">
        <Label for="project-outcome"
          >{{ t('tasks.projectOutcome') }}
          <span class="text-muted-foreground font-normal">{{ t('tasks.optional') }}</span></Label
        ><Textarea id="project-outcome" v-model="form.outcome" maxlength="10000" rows="2" />
      </div>
      <div v-if="!project && writableSpaces.length" class="space-y-2">
        <Label for="project-space">{{ t('tasks.projectAudience') }}</Label
        ><NativeSelect id="project-space" v-model="form.space"
          ><option value="">{{ t('tasks.onlyMe') }}</option>
          <option v-for="space in writableSpaces" :key="space.id" :value="space.id">
            {{ space.name }}
          </option></NativeSelect
        >
      </div>
      <div v-else class="space-y-2">
        <Label for="project-state">{{ t('tasks.state') }}</Label
        ><NativeSelect id="project-state" v-model="form.state"
          ><option v-for="state in ['active', 'done', 'archived']" :key="state" :value="state">
            {{ t(`tasks.projectStates.${state}`) }}
          </option></NativeSelect
        >
      </div>
      <Button v-if="project?.space_id" variant="outline" as-child
        ><RouterLink :to="{ name: 'circle-list', params: { list: project.id } }">{{
          t('circle.listAccess')
        }}</RouterLink></Button
      >
      <Button type="submit" :disabled="busy || !form.name.trim()">{{
        project ? t('common.save') : t('tasks.createProject')
      }}</Button>
    </form>
  </Panel>
</template>
