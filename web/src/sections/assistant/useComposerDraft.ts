/** The complete, account-scoped input survives upload errors, reloads and late responses. */
import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import type { AccountStore } from '@/core/storage'
import { isKnownUnadmitted, type SubmissionJournal } from '@/core/journal'
import { joinDraft } from '@/dictation/draft'
import type { MessageBody } from './delivery'

const STORAGE = 'assistant.composer'
type Store = Pick<AccountStore, 'value' | 'setValue' | 'draft' | 'deleteDraft'>
// A route remount can reuse the account store while the previous composer is still saving.
// Captured writes drain after disposal; the new composer waits before reading that store.
const pendingWrites = new WeakMap<Store, Promise<void>>()
interface StoredPhoto {
  type: string
  bytes: ArrayBuffer
}
type SavedPhoto = StoredPhoto | Blob | null
// WebKit can display and upload a Blob while failing to store that Blob in IndexedDB.
// Keep immutable bytes on disk, and convert each in-memory image only once.
const encodedPhotos = new WeakMap<Blob, Promise<StoredPhoto>>()
function encodePhoto(photo: Blob | null): Promise<StoredPhoto | null> {
  if (!photo) return Promise.resolve(null)
  let encoded = encodedPhotos.get(photo)
  if (!encoded) {
    encoded = photo.arrayBuffer().then((bytes) => ({ type: photo.type, bytes }))
    encodedPhotos.set(photo, encoded)
  }
  return encoded
}
function decodePhoto(photo: SavedPhoto): Blob | null {
  // Existing drafts saved by browsers that support IndexedDB Blobs remain readable.
  if (!photo || photo instanceof Blob) return photo
  return new Blob([photo.bytes], { type: photo.type })
}
export interface ReplyTarget {
  messageId: string
  preview: string
}
export interface DraftPhoto {
  blob: Blob | null
  url: string
  imageId?: string
}
interface SavedDraft {
  text: string
  reply: ReplyTarget | null
  photo: SavedPhoto
  imageId: string | null
  transcriptionId: string | null
  submissionKey: string | null
  attempts?: Array<Omit<UnsentDraft, 'photo'> & { photo: SavedPhoto }>
  /** Historical failed messages already transferred into a durably saved editor. */
  recoveredExchangeIds?: string[]
}
export interface UnsentDraft {
  key: string
  body: MessageBody
  photo: Blob | null
  preview: string
  state: 'sending' | 'failed'
}
export interface DraftSubmission {
  owner: Store
  revision: number
  key: string
  body: MessageBody
  photo: Blob | null
}

export function useComposerDraft(
  store: Ref<Store | null>,
  journal: Ref<Pick<SubmissionJournal, 'pending'> | null>,
) {
  const draft = ref('')
  const replyTo = shallowRef<ReplyTarget | null>(null)
  const photo = shallowRef<DraftPhoto | null>(null)
  const ready = ref(false)
  const storageError = shallowRef<unknown>(null)
  const persistedVersion = ref(0)
  const reloadVersion = ref(0)
  const attempts = shallowRef<UnsentDraft[]>([])
  const recoveredExchanges = shallowRef<ReadonlySet<string>>(new Set())
  const unsent = computed(() => attempts.value.filter((attempt) => attempt.state === 'failed'))
  // Each hydration owns its set, including writes that finish after an account switch.
  let recovery = { ids: new Set<string>() }
  let revision = 0
  let changing = false
  let disposed = false
  let transcriptionId: string | null = null
  let submissionKey: string | null = null

  function persist(recoveryId: string | null = null): Promise<void> {
    const target = store.value
    if (!target || disposed) return Promise.resolve()
    const capturedRecovery = recovery
    const saved = {
      text: draft.value,
      reply: replyTo.value,
      photo: photo.value?.blob ?? null,
      imageId: photo.value?.imageId ?? null,
      transcriptionId,
      submissionKey,
      attempts: attempts.value,
    }
    // Serialize snapshots so a slow earlier IndexedDB write cannot replace a newer draft.
    const write = (pendingWrites.get(target) ?? Promise.resolve())
      .catch(() => undefined)
      .then(async () => {
        const encoded: SavedDraft = {
          ...saved,
          // Read the committed set when this queued write starts. A typing snapshot queued
          // during recovery must retain the mark only if the recovery snapshot was saved.
          recoveredExchangeIds: [
            ...new Set([...capturedRecovery.ids, ...(recoveryId ? [recoveryId] : [])]),
          ],
          photo: await encodePhoto(saved.photo),
          attempts: await Promise.all(
            saved.attempts.map(async (attempt) => ({
              ...attempt,
              photo: await encodePhoto(attempt.photo),
            })),
          ),
        }
        await target.setValue(STORAGE, encoded)
        capturedRecovery.ids = new Set(encoded.recoveredExchangeIds)
        if (!disposed && store.value === target && recovery === capturedRecovery) {
          recoveredExchanges.value = new Set(capturedRecovery.ids)
          storageError.value = null
          persistedVersion.value++
        }
      })
      .catch((error: unknown) => {
        if (!disposed && store.value === target && recovery === capturedRecovery)
          storageError.value = error
        throw error
      })
    pendingWrites.set(target, write)
    return write
  }

  watch(
    [draft, replyTo, photo],
    () => {
      if (changing) return
      revision++
      submissionKey = null
      if (!draft.value) transcriptionId = null
      if (ready.value) void persist().catch(() => undefined)
    },
    { flush: 'sync' },
  )

  watch(
    [store, reloadVersion],
    async ([current]) => {
      ready.value = false
      storageError.value = null
      revision++
      const start = revision
      const currentRecovery = (recovery = { ids: new Set<string>() })
      const currentJournal = journal.value
      recoveredExchanges.value = new Set()
      changing = true
      draft.value = ''
      replyTo.value = null
      releasePhoto()
      photo.value = null
      attempts.value = []
      transcriptionId = null
      submissionKey = null
      changing = false
      if (!current) return
      let loaded = false
      try {
        await pendingWrites.get(current)?.catch(() => undefined)
        if (disposed || recovery !== currentRecovery || revision !== start) return
        const saved = await current.value<SavedDraft>(STORAGE)
        const old = saved ? null : await current.draft('assistant')
        const pending = (await currentJournal?.pending()) ?? []
        const sent = pending?.some(
          (entry) => entry.key === saved?.submissionKey && !isKnownUnadmitted(entry),
        )
        if (disposed || recovery !== currentRecovery || revision !== start) return
        const savedPhoto = !sent ? decodePhoto(saved?.photo ?? null) : null
        changing = true
        draft.value = sent ? '' : (saved?.text ?? old?.text ?? '')
        replyTo.value = sent ? null : (saved?.reply ?? null)
        releasePhoto()
        photo.value = savedPhoto
          ? {
              blob: savedPhoto,
              url: URL.createObjectURL(savedPhoto),
              ...(saved?.imageId ? { imageId: saved.imageId } : {}),
            }
          : !sent && saved?.imageId
            ? uploadedPhoto(saved.imageId)
            : null
        transcriptionId = sent ? null : (saved?.transcriptionId ?? null)
        submissionKey = null
        attempts.value = (saved?.attempts ?? [])
          .filter(
            (attempt) =>
              attempt.key !== saved?.submissionKey &&
              !pending.some((entry) => entry.key === attempt.key),
          )
          .map((attempt) => ({ ...attempt, photo: decodePhoto(attempt.photo), state: 'failed' }))
        currentRecovery.ids = new Set(saved?.recoveredExchangeIds ?? [])
        recoveredExchanges.value = new Set(currentRecovery.ids)
        changing = false
        if (old) {
          await persist()
          await current.deleteDraft('assistant')
        }
        loaded = true
      } catch (error) {
        if (!disposed && recovery === currentRecovery) storageError.value = error
      } finally {
        changing = false
        if (!disposed && recovery === currentRecovery) {
          ready.value = loaded
          if (loaded) void persist().catch(() => undefined)
        }
      }
    },
    { immediate: true, flush: 'sync' },
  )

  /** Persist the envelope before any upload or admission can leave this page. */
  async function prepare(sourceId: string | null): Promise<DraftSubmission> {
    if (!ready.value || !store.value) throw new Error('Composer is not ready')
    transcriptionId = sourceId ?? transcriptionId
    submissionKey = crypto.randomUUID()
    const snapshot: DraftSubmission = {
      owner: store.value,
      revision,
      key: submissionKey,
      body: {
        ...(draft.value.trim() ? { text: draft.value.trim() } : {}),
        ...(replyTo.value ? { reply_to_message_id: replyTo.value.messageId } : {}),
        ...(transcriptionId ? { transcription_id: transcriptionId } : {}),
        ...(photo.value?.imageId ? { image_id: photo.value.imageId } : {}),
      },
      // A photo uploaded ahead is named by its id instead of being uploaded again.
      photo: photo.value?.imageId ? null : (photo.value?.blob ?? null),
    }
    attempts.value = [
      ...attempts.value,
      {
        key: snapshot.key,
        body: snapshot.body,
        photo: snapshot.photo,
        preview: replyTo.value?.preview ?? '',
        state: 'sending',
      },
    ]
    try {
      await persist()
    } catch (error) {
      attempts.value = attempts.value.map((attempt) =>
        attempt.key === snapshot.key ? { ...attempt, state: 'failed' } : attempt,
      )
      throw error
    }
    return snapshot
  }

  /** A journaled send owns this snapshot now; edits made while it travelled still belong here. */
  async function delivered(snapshot: DraftSubmission) {
    if (!belongs(snapshot)) return
    attempts.value = attempts.value.filter((attempt) => attempt.key !== snapshot.key)
    if (snapshot.body.transcription_id === transcriptionId) transcriptionId = null
    if (snapshot.revision !== revision) {
      await persist()
      return
    }
    changing = true
    draft.value = ''
    replyTo.value = null
    releasePhoto()
    photo.value = null
    transcriptionId = null
    submissionKey = null
    revision++
    changing = false
    await persist()
  }

  function belongs(snapshot: DraftSubmission): boolean {
    return !disposed && snapshot.owner === store.value
  }

  function owns(snapshot: DraftSubmission): boolean {
    return belongs(snapshot) && snapshot.revision === revision
  }

  async function failed(snapshot: DraftSubmission, journaled: boolean) {
    if (!belongs(snapshot)) return
    attempts.value = attempts.value.flatMap((attempt) =>
      attempt.key !== snapshot.key
        ? [attempt]
        : journaled || owns(snapshot)
          ? []
          : [{ ...attempt, state: 'failed' as const }],
    )
    await persist()
  }

  async function restoreUnsent(key: string): Promise<boolean> {
    const currentRecovery = recovery
    const attempt = attempts.value.find((item) => item.key === key)
    if (!attempt || !(await restore(attempt.body, attempt.preview, attempt.photo))) return false
    if (disposed || recovery !== currentRecovery) return false
    await dismissUnsent(key)
    return !disposed && recovery === currentRecovery
  }

  async function dismissUnsent(key: string) {
    attempts.value = attempts.value.filter((attempt) => attempt.key !== key)
    await persist()
  }

  /**
   * The draft's photo reached the server ahead of the send (photoUploads). The same photo, so not
   * an edit: a send already in flight keeps its revision and still clears the composer.
   */
  function photoUploaded(blob: Blob, imageId: string) {
    if (photo.value?.blob !== blob || photo.value.imageId === imageId) return
    changing = true
    photo.value = { ...photo.value, imageId }
    changing = false
    if (ready.value) void persist().catch(() => undefined)
  }

  function setPhoto(blob: Blob | null) {
    releasePhoto()
    photo.value = blob ? { blob, url: URL.createObjectURL(blob) } : null
  }

  function releasePhoto() {
    if (photo.value?.blob) URL.revokeObjectURL(photo.value.url)
  }

  /** A rejected journal entry still owns its uploaded image and reply bindings. */
  async function restore(
    body: MessageBody,
    preview: string,
    blob: Blob | null = null,
    recoveryId: string | null = null,
  ): Promise<boolean> {
    if (!ready.value || !store.value) return false
    if (recoveryId && recoveredExchanges.value.has(recoveryId)) return true
    if (draft.value || photo.value || replyTo.value) return false
    const currentRecovery = recovery
    changing = true
    draft.value = body.text ?? ''
    replyTo.value = body.reply_to_message_id
      ? { messageId: body.reply_to_message_id, preview }
      : null
    photo.value = blob
      ? { blob, url: URL.createObjectURL(blob) }
      : body.image_id
        ? uploadedPhoto(body.image_id)
        : null
    transcriptionId = body.transcription_id ?? null
    submissionKey = null
    revision++
    const restoredRevision = revision
    changing = false
    try {
      // The mark and complete input share one IndexedDB write. The visible history is
      // retired only after that write succeeds, never merely after filling the editor.
      await persist(recoveryId)
    } catch (error) {
      if (disposed || recovery !== currentRecovery) return false
      // Keep newer user edits, but let an unchanged failed restore be retried directly.
      if (revision === restoredRevision) {
        changing = true
        draft.value = ''
        replyTo.value = null
        releasePhoto()
        photo.value = null
        transcriptionId = null
        submissionKey = null
        revision++
        changing = false
      }
      throw error
    }
    return !disposed && recovery === currentRecovery
  }

  onScopeDispose(() => {
    disposed = true
    releasePhoto()
  })

  return {
    draft,
    replyTo,
    photo,
    ready,
    storageError,
    persistedVersion,
    unsent,
    recoveredExchanges,
    prepare,
    delivered,
    failed,
    belongs,
    owns,
    setPhoto,
    photoUploaded,
    restore,
    restoreUnsent,
    dismissUnsent,
    retryLoad: () => reloadVersion.value++,
  }
}

/**
 * Text or a photo for the assistant from elsewhere in the app: dictation from the tab bar, a
 * meal photo from the food diary. It is written into the saved draft through the same write
 * queue as the composer, so the assistant screen shows it when it opens, and nothing is sent:
 * the person reviews the draft there. The caller awaits it before navigating.
 */
export interface Handoff {
  /** `append` adds to what is already typed; `prefill` fills an empty draft only. */
  mode: 'append' | 'prefill'
  text: string
  /** The dictation that produced the text: it travels with the message once sent. */
  transcriptionId?: string | null
  photo?: Blob | null
  /** A draft photo is kept unless the person agreed to replace it (draftHasPhoto). */
  replacePhoto?: boolean
}

export async function handOff(
  store: Store,
  journal: Pick<SubmissionJournal, 'pending'> | null,
  entry: Handoff,
): Promise<void> {
  const write = (pendingWrites.get(store) ?? Promise.resolve())
    .catch(() => undefined)
    .then(async () => {
      const saved = await store.value<SavedDraft>(STORAGE)
      const legacy = saved ? null : await store.draft('assistant')
      // A draft whose send was admitted is gone, exactly as the composer would load it.
      const pending = saved?.submissionKey ? ((await journal?.pending()) ?? []) : []
      const sent = pending.some(
        (item) => item.key === saved?.submissionKey && !isKnownUnadmitted(item),
      )
      const text = sent ? '' : (saved?.text ?? legacy?.text ?? '')
      const photo = sent ? null : (saved?.photo ?? null)
      const imageId = sent ? null : (saved?.imageId ?? null)
      const attach = !!entry.photo && (entry.replacePhoto || (!photo && !imageId))
      const next: SavedDraft = {
        text:
          entry.mode === 'append' ? joinDraft(text, entry.text) : text.trim() ? text : entry.text,
        reply: sent ? null : (saved?.reply ?? null),
        photo: attach ? await encodePhoto(entry.photo ?? null) : photo,
        imageId: attach ? null : imageId,
        transcriptionId: entry.transcriptionId ?? (sent ? null : (saved?.transcriptionId ?? null)),
        submissionKey: null,
        attempts: saved?.attempts ?? [],
        recoveredExchangeIds: saved?.recoveredExchangeIds ?? [],
      }
      await store.setValue(STORAGE, next)
    })
  pendingWrites.set(store, write)
  return write
}

/** Whether the saved draft already holds a photo that a handed-off one would replace. */
export async function draftHasPhoto(store: Store): Promise<boolean> {
  await pendingWrites.get(store)?.catch(() => undefined)
  const saved = await store.value<SavedDraft>(STORAGE)
  return !!(saved?.photo || saved?.imageId)
}

/** Receipt cleanup waits for durable transfer out of the composer, including in-flight photos. */
export async function draftOwnsSubmission(
  store: Pick<AccountStore, 'value'>,
  key: string,
): Promise<boolean> {
  const saved = await store.value<SavedDraft>(STORAGE)
  return saved?.submissionKey === key || !!saved?.attempts?.some((attempt) => attempt.key === key)
}

function uploadedPhoto(imageId: string): DraftPhoto {
  return { blob: null, imageId, url: `/v1/conversation/images/${encodeURIComponent(imageId)}` }
}
