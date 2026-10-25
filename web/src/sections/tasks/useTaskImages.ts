import type { Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { errorText } from '@/core/errors'
import { PhotoError, photoProblemKey } from '@/core/photo'
import type { TaskImage } from './api'
import { uploadTaskImage } from './images'

/**
 * Adds pasted or picked pictures to a field once each is stored, in the order given and within
 * the field's limit. [uploading] counts those under way, so the card can wait before saving.
 * [elsewhere] tells whether the task's other field already holds a picture: a task keeps each once.
 */
export function useTaskImageUpload(
  images: Ref<TaskImage[] | undefined>,
  uploading: Ref<number>,
  limit: () => number,
  elsewhere: (id: string) => boolean = () => false,
  upload: (file: Blob) => Promise<TaskImage> = uploadTaskImage,
) {
  const { t } = useI18n()

  async function add(files: File[]) {
    if (!files.length || images.value === undefined) return
    const room = Math.max(0, limit() - images.value.length - uploading.value)
    if (files.length > room) toast.error(t('tasks.images.limit', { count: limit() }, limit()))
    const taken = files.slice(0, room)
    if (!taken.length) return
    uploading.value += taken.length
    const settled = await Promise.allSettled(taken.map((file) => upload(file)))
    uploading.value -= taken.length
    const failure = settled.find((result) => result.status === 'rejected')
    if (failure) {
      const error: unknown = failure.reason
      toast.error(error instanceof PhotoError ? t(photoProblemKey(error)) : errorText(error, t))
    }
    // The same picture twice is one stored photo (its id comes from its bytes): it is kept once.
    const next = [...(images.value ?? [])]
    let repeated = false
    for (const result of settled) {
      if (result.status !== 'fulfilled') continue
      if (elsewhere(result.value.id)) repeated = true
      else if (!next.some((image) => image.id === result.value.id)) next.push(result.value)
    }
    if (repeated) toast.error(t('tasks.images.duplicate'))
    images.value = next
  }

  return { add }
}
