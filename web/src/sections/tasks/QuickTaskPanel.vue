<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import Panel from '@/components/Panel.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { imageFiles } from '@/core/clipboard'
import { shiftDay } from '@/core/dates'
import { errorText } from '@/core/errors'
import { useLedger } from '@/core/operations'
import { IMAGE_LIMITS, TASKS, type TaskImage } from './api'
import { ownImageUrl } from './images'
import TaskImageButton from './TaskImageButton.vue'
import TaskImages from './TaskImages.vue'
import { useTaskImageUpload } from './useTaskImages'

/** A new task in one sheet: what to do and when to do it; details go in the task itself. */
type When = 'today' | 'tomorrow' | 'none' | 'date'
const props = defineProps<{ today: string; plannedFor?: Exclude<When, 'date'> }>()
const open = defineModel<boolean>('open', { required: true })
const { t } = useI18n()
const ledger = useLedger(TASKS)
const title = ref('')
const when = ref<When>('none')
const date = ref('')
const busy = ref(false)
const images = ref<TaskImage[]>([])
const uploading = ref(0)
const pictures = useTaskImageUpload(images, uploading, () => IMAGE_LIMITS.title_images)
watch(open, (value) => {
  if (!value) return
  title.value = ''
  when.value = props.plannedFor ?? 'none'
  date.value = ''
  images.value = []
})
function paste(event: ClipboardEvent) {
  const files = imageFiles(event.clipboardData)
  if (!files.length) return
  event.preventDefault()
  void pictures.add(files)
}
const imageSource = (image: TaskImage, preview: boolean) => ownImageUrl(image.id, preview)
const segments = computed(() => [
  { value: 'today', label: t('tasks.dateToday') },
  { value: 'tomorrow', label: t('tasks.dateTomorrow') },
  { value: 'none', label: t('tasks.dateNone') },
  { value: 'date', label: t('tasks.datePick') },
])
const plannedOn = computed(() => {
  if (when.value === 'today') return props.today
  if (when.value === 'tomorrow') return shiftDay(props.today, 1)
  return when.value === 'date' ? date.value || null : null
})
const ready = computed(
  () => !!title.value.trim() && (when.value !== 'date' || !!date.value) && !uploading.value,
)

async function add() {
  if (!ready.value || busy.value) return
  busy.value = true
  try {
    const payload: Record<string, unknown> = { title: title.value.trim() }
    if (plannedOn.value) payload.planned_on = plannedOn.value
    if (images.value.length) payload.title_images = images.value.map((image) => image.id)
    await ledger.operate({ kind: 'task.create', payload })
    toast.success(t('tasks.added'))
    open.value = false
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Panel v-model:open="open" :title="t('tasks.quickAdd')">
    <form class="space-y-4" novalidate @submit.prevent="add">
      <div class="space-y-2">
        <div class="flex items-center gap-2">
          <Label for="quick-task-title" class="me-auto">{{ t('tasks.name') }}</Label>
          <TaskImageButton :label="t('tasks.name')" @pick="pictures.add" />
        </div>
        <Input
          id="quick-task-title"
          v-model="title"
          maxlength="300"
          enterkeyhint="done"
          @paste="paste"
        />
        <TaskImages
          :images="images"
          :source="imageSource"
          :label="t('tasks.images.label', { field: t('tasks.name') })"
          removable
          :pending="uploading"
          @remove="(id) => (images = images.filter((image) => image.id !== id))"
        />
      </div>
      <div class="space-y-2">
        <p class="text-sm font-medium">{{ t('tasks.plannedOn') }}</p>
        <SegmentedControl
          v-model="when"
          :segments="segments"
          :label="t('tasks.plannedOn')"
          stretch
        />
        <Input
          v-if="when === 'date'"
          v-model="date"
          type="date"
          :min="today"
          :aria-label="t('tasks.datePick')"
        />
      </div>
      <Button type="submit" size="lg" class="w-full" :disabled="!ready || busy">
        {{ t('tasks.add') }}
      </Button>
    </form>
  </Panel>
</template>
