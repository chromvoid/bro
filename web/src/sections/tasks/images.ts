/** Pictures under a task's title and description: their addresses and their upload. */
import { api } from '@/core/client'
import { normalizePhoto } from '@/core/photo'
import { TaskImage } from './api'

/** Matches PREVIEW_VERSION of server/conversation/images.py: a new preview gets a new address. */
const PREVIEW_VERSION = 1

/** A picture of a task, read through the task; the uploader also reads one not saved there yet. */
export function taskImageUrl(taskId: string, imageId: string, preview = true): string {
  const base = `/v1/tasks/items/${encodeURIComponent(taskId)}/images/${encodeURIComponent(imageId)}`
  return preview ? `${base}/preview?v=${PREVIEW_VERSION}` : base
}

/** A picture the person stored themselves, before any task holds it (a task being created). */
export function ownImageUrl(imageId: string, preview = true): string {
  const base = `/v1/conversation/images/${encodeURIComponent(imageId)}`
  return preview ? `${base}/preview?v=${PREVIEW_VERSION}` : base
}

/** Prepares a picked or pasted picture on the device and stores it as the person's own photo. */
export async function uploadTaskImage(file: Blob): Promise<TaskImage> {
  return TaskImage.parse((await api.upload('/v1/tasks/images', await normalizePhoto(file))).data)
}
