<script setup lang="ts">
import { Check, Pencil } from '@lucide/vue'
import { nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import LinkedText from '@/components/LinkedText'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { imageFiles } from '@/core/clipboard'
import type { TaskImage } from './api'
import TaskImageButton from './TaskImageButton.vue'
import TaskImages from './TaskImages.vue'
import { useTaskImageUpload } from './useTaskImages'

const props = withDefaults(
  defineProps<{
    id: string
    label: string
    maxlength: number
    multiline?: boolean
    disabled?: boolean
    /** How many pictures the field holds; it takes pictures when v-model:images is bound. */
    imageLimit?: number
    imageSource?: (image: TaskImage, preview: boolean) => string
    /** Whether the task's other field already holds this picture. */
    imageElsewhere?: (id: string) => boolean
  }>(),
  { imageLimit: 0, imageSource: undefined, imageElsewhere: () => false },
)
const value = defineModel<string>({ required: true })
const images = defineModel<TaskImage[]>('images')
const uploading = defineModel<number>('uploading', { default: 0 })
const { t } = useI18n()
const editing = ref(false)
const pictures = useTaskImageUpload(
  images,
  uploading,
  () => props.imageLimit,
  (id) => props.imageElsewhere(id),
)

// A pasted picture goes under the field; pasted text stays the field's own.
function paste(event: ClipboardEvent) {
  if (images.value === undefined) return
  const files = imageFiles(event.clipboardData)
  if (!files.length) return
  event.preventDefault()
  void pictures.add(files)
}
function remove(id: string) {
  if (images.value) images.value = images.value.filter((image) => image.id !== id)
}

async function edit() {
  if (props.disabled) return
  editing.value = true
  await nextTick()
  document.getElementById(props.id)?.focus()
}
function finish() {
  editing.value = false
}
function close() {
  finish()
  void nextTick(() => document.getElementById(`${props.id}-edit`)?.focus())
}
function enter(event: KeyboardEvent) {
  if (event.isComposing || (props.multiline && !event.ctrlKey && !event.metaKey)) return
  event.preventDefault()
  close()
}
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) finish()
  },
)
defineExpose({ edit })
</script>

<template>
  <div class="space-y-2">
    <div class="flex items-center gap-2">
      <Label :id="`${id}-label`" class="me-auto" :for="editing ? id : undefined">{{ label }}</Label>
      <TaskImageButton
        v-if="!disabled && editing && images !== undefined"
        :label="label"
        @pick="pictures.add"
      />
      <Button
        v-if="!disabled && !editing"
        :id="`${id}-edit`"
        type="button"
        variant="ghost"
        size="icon-sm"
        :aria-label="`${t('common.edit')}: ${label}`"
        @click="edit"
        ><Pencil class="size-4" aria-hidden="true"
      /></Button>
      <Button
        v-else-if="!disabled"
        type="button"
        variant="ghost"
        size="icon-sm"
        :aria-label="`${t('common.done')}: ${label}`"
        @click="close"
        ><Check class="size-4" aria-hidden="true"
      /></Button>
    </div>
    <component
      :is="multiline ? Textarea : Input"
      v-if="editing"
      :id="id"
      v-model="value"
      :maxlength="maxlength"
      :disabled="disabled"
      @keydown.enter="enter"
      @keydown.esc.stop.prevent="close"
      @paste="paste"
    />
    <p
      v-else
      :aria-labelledby="`${id}-label`"
      class="min-h-9 text-sm break-words"
      :data-testid="`${id}-text`"
    >
      <LinkedText v-if="value" :text="value" />
      <span v-else-if="!images?.length" class="text-muted-foreground">—</span>
    </p>
    <TaskImages
      v-if="images !== undefined && imageSource"
      :images="images"
      :source="imageSource"
      :label="t('tasks.images.label', { field: label })"
      :removable="editing && !disabled"
      :pending="uploading"
      @remove="remove"
    />
  </div>
</template>
