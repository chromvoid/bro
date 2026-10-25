/**
 * A chosen photo starts uploading at once, while the person writes its caption; sending reuses that
 * upload instead of starting another. The upload only stores the photo for its owner, it does not
 * run the conversation (server/conversation/images.py), and the server keeps one copy per content.
 */
import { shallowReactive } from 'vue'
import type { Api } from '@/core/http'
import { UploadedImage } from './model'

interface Upload {
  user: string
  id: Promise<string>
  controller: AbortController
  /** A send waits for it: taking the photo out of the draft no longer stops it. */
  claimed: boolean
}

const started = new WeakMap<Blob, Upload>()
/** Photos whose upload is still running: the composer shows it on the photo. */
export const uploading = shallowReactive(new Set<Blob>())
/** The photo as sent, by its image id: the conversation shows it without fetching it back. */
const sent = new Map<string, string>()
const SENT_KEPT = 20

export interface UploadOptions {
  expectedUser?: string
  beforeSend?: () => void
  /** Started when the photo was chosen, before anyone sends it. */
  early?: boolean
}

/** The image id of [photo]: the upload already started for this account, or a new one. */
export function uploadPhoto(
  api: Pick<Api, 'upload'>,
  photo: Blob,
  options: UploadOptions = {},
): Promise<string> {
  const early = started.get(photo)
  if (early && early.user === (options.expectedUser ?? '')) {
    early.claimed ||= !options.early
    // A failed early upload is tried again by the send, which reports its own failure.
    return early.id.catch(() => {
      if (started.get(photo) === early) started.delete(photo)
      return uploadPhoto(api, photo, options)
    })
  }
  const controller = new AbortController()
  const upload: Upload = {
    user: options.expectedUser ?? '',
    controller,
    claimed: !options.early,
    id: api
      .upload('/v1/conversation/images', photo, {
        expectedUser: options.expectedUser,
        beforeSend: options.beforeSend,
        signal: controller.signal,
      })
      .then((reply) => UploadedImage.parse(reply.data).id),
  }
  started.set(photo, upload)
  uploading.add(photo)
  upload.id.then(
    (id) => remember(id, photo),
    () => undefined,
  )
  void upload.id
    .catch(() => undefined)
    .finally(() => {
      if (started.get(photo) === upload || !started.has(photo)) uploading.delete(photo)
    })
  return upload.id
}

/** The photo left the draft unsent: its early upload stops, unless a send already waits for it. */
export function dropUpload(photo: Blob) {
  const upload = started.get(photo)
  if (!upload || upload.claimed) return
  upload.controller.abort()
  started.delete(photo)
  uploading.delete(photo)
}

/** The local copy of a sent photo, while this page keeps it. */
export function sentPhoto(id: string): string | undefined {
  return sent.get(id)
}

function remember(id: string, photo: Blob) {
  if (sent.has(id)) return
  sent.set(id, URL.createObjectURL(photo))
  for (const [oldest, url] of sent) {
    if (sent.size <= SENT_KEPT) break
    URL.revokeObjectURL(url)
    sent.delete(oldest)
  }
}
