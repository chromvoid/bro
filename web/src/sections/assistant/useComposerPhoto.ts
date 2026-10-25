/**
 * The composer's photo: a chosen one is reduced first, its place shown at once, and it uploads
 * while its caption is written; the send reuses the upload (photoUploads.ts).
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { api } from '@/core/client'
import { normalizePhoto, photoProblemKey } from '@/core/photo'
import { useSession } from '@/stores/session'
import { dropUpload, uploading, uploadPhoto } from './photoUploads'
import type { useComposerDraft } from './useComposerDraft'

export function useComposerPhoto(composerDraft: ReturnType<typeof useComposerDraft>) {
  const { t } = useI18n()
  const session = useSession()
  let disposed = false
  let photoSelection = 0
  onBeforeUnmount(() => {
    disposed = true
    photoSelection++
  })

  // A chosen photo is being reduced: the composer shows its place at once.
  const preparingPhoto = ref(false)
  // A chosen (or handed-over) photo uploads while its caption is written; the send reuses it.
  watch(
    () => composerDraft.photo.value,
    (photo) => {
      const account = session.account
      const blob = photo?.blob
      if (!blob || photo.imageId || !account) return
      const beforeSend = () => {
        if (disposed || session.account !== account) throw new Error('Conversation account changed')
      }
      void uploadPhoto(api, blob, { expectedUser: account.store.userId, beforeSend, early: true })
        .then((id) => {
          if (!disposed && session.account === account) composerDraft.photoUploaded(blob, id)
        })
        // A failed early upload is the send's to try again and report.
        .catch(() => undefined)
    },
    { immediate: true },
  )
  const uploadingPhoto = computed(() => {
    const blob = composerDraft.photo.value?.blob
    return !!blob && uploading.has(blob)
  })

  async function attach(file: File) {
    const account = session.account
    const selection = ++photoSelection
    preparingPhoto.value = true
    try {
      const blob = await normalizePhoto(file)
      if (disposed || account !== session.account || selection !== photoSelection) return
      const replaced = composerDraft.photo.value?.blob
      if (replaced) dropUpload(replaced)
      composerDraft.setPhoto(blob)
    } catch (error) {
      if (disposed || account !== session.account || selection !== photoSelection) return
      toast.error(t(photoProblemKey(error)))
    } finally {
      if (selection === photoSelection) preparingPhoto.value = false
    }
  }

  function removePhoto() {
    photoSelection++
    preparingPhoto.value = false
    const removed = composerDraft.photo.value?.blob
    if (removed) dropUpload(removed)
    composerDraft.setPhoto(null)
  }

  return { preparingPhoto, uploadingPhoto, attach, removePhoto }
}
