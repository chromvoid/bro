import 'fake-indexeddb/auto'
import { Blob as NodeBlob } from 'node:buffer'
import { effectScope, shallowRef, type EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AccountStore, purgeAccount } from '@/core/storage'
import { LocalKeyLock, SubmissionJournal, type Submission } from '@/core/journal'
import { Api } from '@/core/http'
import { deliverMessage, RejectedDelivery } from './delivery'
import { useComposerDraft, draftHasPhoto, draftOwnsSubmission, handOff } from './useComposerDraft'

describe('complete composer draft', () => {
  let store: AccountStore
  let user: string
  let scopes: EffectScope[]
  let pending: Submission[]

  beforeEach(async () => {
    user = crypto.randomUUID()
    store = await AccountStore.open(user)
    scopes = []
    pending = []
    // fake-indexeddb uses Node structuredClone, which cannot clone happy-dom's Blob internals.
    vi.stubGlobal('Blob', NodeBlob)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
  })
  afterEach(async () => {
    scopes.forEach((scope) => scope.stop())
    store.close()
    await purgeAccount(user)
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  async function open() {
    const scope = effectScope()
    scopes.push(scope)
    const composer = scope.run(() =>
      useComposerDraft(shallowRef(store), shallowRef({ pending: async () => pending })),
    )!
    await vi.waitFor(() => expect(composer.ready.value).toBe(true))
    return { ...composer, stop: () => scope.stop() }
  }

  it('appends dictation from elsewhere to the saved draft and keeps its transcription', async () => {
    const first = await open()
    first.draft.value = 'Купить молоко'
    first.stop()
    await handOff(
      store,
      { pending: async () => pending },
      {
        mode: 'append',
        text: 'и хлеб.',
        transcriptionId: 'tr-1',
      },
    )
    const composer = await open()
    expect(composer.draft.value).toBe('Купить молоко и хлеб.')
    // Nothing was sent: the person sends it from the composer, with the dictation's id.
    const sending = await composer.prepare(null)
    expect(sending.body).toEqual({ text: 'Купить молоко и хлеб.', transcription_id: 'tr-1' })
  })

  it('prefills only an empty draft and keeps a draft photo unless told to replace it', async () => {
    await handOff(store, null, {
      mode: 'prefill',
      text: 'Запиши еду с фото',
      photo: new Blob(['meal'], { type: 'image/jpeg' }),
    })
    expect(await draftHasPhoto(store)).toBe(true)
    let composer = await open()
    expect(composer.draft.value).toBe('Запиши еду с фото')
    expect(composer.photo.value).not.toBeNull()
    composer.draft.value = 'Свой текст'
    composer.stop()

    await handOff(store, null, {
      mode: 'prefill',
      text: 'Запиши еду с фото',
      photo: new Blob(['second'], { type: 'image/jpeg' }),
    })
    composer = await open()
    expect(composer.draft.value).toBe('Свой текст')
    expect(await (await composer.prepare(null)).photo?.text()).toBe('meal')
    composer.stop()

    await handOff(store, null, {
      mode: 'prefill',
      text: 'Запиши еду с фото',
      photo: new Blob(['second'], { type: 'image/jpeg' }),
      replacePhoto: true,
    })
    composer = await open()
    expect(await (await composer.prepare(null)).photo?.text()).toBe('second')
  })

  it('names a photo uploaded ahead instead of sending it again, also after reload', async () => {
    const first = await open()
    const photo = new Blob(['photo'], { type: 'image/jpeg' })
    first.draft.value = 'Обед'
    first.setPhoto(photo)
    first.photoUploaded(new Blob(['other'], { type: 'image/jpeg' }), 'img-other')
    expect(first.photo.value?.imageId).toBeUndefined()
    first.photoUploaded(photo, 'img-1')
    expect(first.photo.value).toMatchObject({ blob: photo, imageId: 'img-1' })
    first.stop()

    const restored = await open()
    expect(restored.photo.value?.imageId).toBe('img-1')
    expect(await restored.photo.value?.blob?.text()).toBe('photo')
    const sending = await restored.prepare(null)
    expect(sending.body).toEqual({ text: 'Обед', image_id: 'img-1' })
    expect(sending.photo).toBeNull()
  })

  it('still clears the composer when the photo finished uploading during the send', async () => {
    const composer = await open()
    const photo = new Blob(['photo'], { type: 'image/jpeg' })
    composer.setPhoto(photo)
    const sending = await composer.prepare(null)
    composer.photoUploaded(photo, 'img-1')
    await composer.delivered(sending)
    expect(composer.photo.value).toBeNull()
    expect(composer.draft.value).toBe('')
  })

  it('does not bring back a draft whose send was already admitted', async () => {
    const first = await open()
    first.draft.value = 'Уже отправлено'
    const snapshot = await first.prepare(null)
    first.stop()
    pending = [{ key: snapshot.key, state: 'accepted' } as Submission]
    await handOff(store, { pending: async () => pending }, { mode: 'append', text: 'Новое' })
    const composer = await open()
    expect(composer.draft.value).toBe('Новое')
  })

  it('keeps and reloads the photo, reply, text and transcription after failure before admission', async () => {
    const first = await open()
    first.draft.value = 'Съел половину'
    first.replyTo.value = { messageId: 'reply', preview: 'Ваша порция' }
    first.setPhoto(new Blob(['photo'], { type: 'image/jpeg' }))
    const sent = await first.prepare('transcription')
    const saved = await store.value<{
      photo: { type: string; bytes: ArrayBuffer }
      attempts: Array<{ photo: { type: string; bytes: ArrayBuffer } }>
    }>('assistant.composer')
    expect(saved?.photo).not.toBeInstanceOf(Blob)
    expect(saved?.photo.type).toBe('image/jpeg')
    expect(new TextDecoder().decode(saved?.photo.bytes)).toBe('photo')
    expect(new TextDecoder().decode(saved?.attempts[0]?.photo.bytes)).toBe('photo')
    expect(first.draft.value).toBe('Съел половину')
    expect(first.photo.value).not.toBeNull()
    first.stop()

    const restored = await open()
    const next = await restored.prepare(null)
    expect(next.body).toEqual(sent.body)
    expect(restored.replyTo.value).toEqual({ messageId: 'reply', preview: 'Ваша порция' })
    expect(next.photo?.type).toBe('image/jpeg')
    expect(next.photo?.size).toBe(5)
    expect(await next.photo?.text()).toBe('photo')
  })

  it('keeps the complete editable envelope after a first CSRF failure and reload', async () => {
    const composer = await open()
    composer.draft.value = 'Синтетическая порция'
    composer.replyTo.value = { messageId: 'reply', preview: 'Уточнение порции' }
    composer.setPhoto(new Blob(['photo'], { type: 'image/jpeg' }))
    const snapshot = await composer.prepare('transcription')
    const fetcher = vi.fn<typeof fetch>(async () => {
      throw new TypeError('offline')
    })
    const api = new Api(fetcher)
    // Image storage completed; admission still needs its own CSRF preparation.
    vi.spyOn(api, 'upload').mockResolvedValue({
      status: 201,
      data: { id: 'image', width: 20, height: 10, url: '/image' },
    })
    const journal = new SubmissionJournal(store, api, new LocalKeyLock())
    const error = await deliverMessage(
      journal,
      api,
      snapshot.body,
      snapshot.photo,
      snapshot.key,
    ).catch((error: unknown) => error)
    expect(error).toBeInstanceOf(RejectedDelivery)
    pending = await journal.pending()
    expect(pending[0]).toMatchObject({
      state: 'rejected',
      attempt: { disposition: 'not_dispatched' },
    })
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual(['/v1/auth/csrf'])
    await composer.failed(snapshot, true)
    expect(composer.owns(snapshot)).toBe(true)
    expect(composer.draft.value).toBe('Синтетическая порция')
    composer.stop()
    const restored = await open()
    const next = await restored.prepare(null)
    expect(next.body).toEqual(snapshot.body)
    expect(await next.photo?.text()).toBe('photo')
    expect(restored.replyTo.value?.messageId).toBe('reply')
  })

  it('reads legacy Blob drafts and saves them as portable image bytes', async () => {
    await store.setValue('assistant.composer', {
      text: 'Ранее выбранное фото',
      photo: new Blob(['legacy photo'], { type: 'image/jpeg' }),
      attempts: [
        {
          key: 'old-attempt',
          body: { text: 'Неотправленное' },
          photo: new Blob(['legacy attempt'], { type: 'image/jpeg' }),
          preview: '',
          state: 'failed',
        },
      ],
    })
    const composer = await open()
    expect(await composer.photo.value?.blob?.text()).toBe('legacy photo')
    expect(await composer.unsent.value[0]?.photo?.text()).toBe('legacy attempt')
    await composer.prepare(null)
    const saved = await store.value<{ photo: unknown; attempts: Array<{ photo: unknown }> }>(
      'assistant.composer',
    )
    expect(saved?.photo).not.toBeInstanceOf(Blob)
    expect(saved?.attempts[0]?.photo).not.toBeInstanceOf(Blob)
  })

  it('clears only the delivered version and preserves a newer complete set of edits', async () => {
    const composer = await open()
    composer.draft.value = 'Съел яблоко'
    composer.replyTo.value = { messageId: 'first', preview: 'Первый ответ' }
    composer.setPhoto(new Blob(['old']))
    const sent = await composer.prepare(null)
    composer.draft.value = 'Теперь груша'
    composer.replyTo.value = { messageId: 'second', preview: 'Другой ответ' }
    const newPhoto = new Blob(['new'])
    composer.setPhoto(newPhoto)
    await composer.delivered(sent)
    expect(composer.draft.value).toBe('Теперь груша')
    expect(composer.replyTo.value?.messageId).toBe('second')
    expect(composer.photo.value?.blob).toBe(newPhoto)
    const newer = await composer.prepare(null)
    await composer.delivered(newer)
    expect(composer.draft.value).toBe('')
    expect(composer.replyTo.value).toBeNull()
    expect(composer.photo.value).toBeNull()
    await vi.waitFor(async () =>
      expect(await store.value('assistant.composer')).toMatchObject({
        text: '',
        photo: null,
        reply: null,
      }),
    )
  })

  it('hands a interrupted journaled send to recovery instead of restoring a second sendable copy', async () => {
    const first = await open()
    first.draft.value = 'Запиши завтрак'
    const sent = await first.prepare(null)
    pending = [{ key: sent.key, state: 'uncertain' } as Submission]
    first.stop()
    const restored = await open()
    expect(restored.draft.value).toBe('')
    expect(pending).toHaveLength(1)
  })

  it('retains a refused envelope after reload', async () => {
    const first = await open()
    first.draft.value = 'Запиши завтрак'
    first.replyTo.value = { messageId: 'reply', preview: 'Завтрак' }
    const sent = await first.prepare(null)
    pending = [{ key: sent.key, state: 'rejected' } as Submission]
    first.stop()
    const restored = await open()
    expect(restored.draft.value).toBe('Запиши завтрак')
    expect(restored.replyTo.value?.messageId).toBe('reply')
  })

  it('restores a rejected journal envelope with the uploaded image and preserves newer drafts', async () => {
    const composer = await open()
    const body = {
      text: 'Завтрак',
      image_id: 'uploaded-photo',
      reply_to_message_id: 'reply',
      transcription_id: 'voice',
    }
    expect(await composer.restore(body, 'О порции')).toBe(true)
    expect(composer.photo.value?.url).toBe('/v1/conversation/images/uploaded-photo')
    expect(composer.photo.value?.blob).toBeNull()
    const prepared = await composer.prepare(null)
    expect(prepared.body).toEqual(body)
    expect(prepared.photo).toBeNull() // The original upload is reused; no new binary upload.
    composer.draft.value = 'Более новый текст'
    expect(await composer.restore(body, 'Старый ответ')).toBe(false)
    expect(composer.draft.value).toBe('Более новый текст')
  })

  it('commits recovery with the full envelope and keeps its mark in edits queued during saving', async () => {
    const composer = await open()
    await vi.waitFor(() => expect(composer.persistedVersion.value).toBeGreaterThan(0))
    const save = store.setValue.bind(store)
    let release: (() => void) | undefined
    const writes: unknown[] = []
    vi.spyOn(store, 'setValue').mockImplementationOnce(async (name, value) => {
      writes.push(value)
      await new Promise<void>((resolve) => (release = resolve))
      await save(name, value)
    })
    const body = {
      text: 'Завтрак',
      image_id: 'meal-photo',
      reply_to_message_id: 'portion-reply',
      transcription_id: 'voice',
    }
    const restoring = composer.restore(body, 'Порция', null, 'failed-exchange')
    await vi.waitFor(() => expect(release).toBeDefined())
    expect(composer.recoveredExchanges.value.size).toBe(0)
    expect(writes[0]).toMatchObject({
      text: body.text,
      imageId: body.image_id,
      reply: { messageId: body.reply_to_message_id, preview: 'Порция' },
      transcriptionId: body.transcription_id,
      recoveredExchangeIds: ['failed-exchange'],
    })
    composer.draft.value = 'Завтрак с поправкой'
    release!()
    expect(await restoring).toBe(true)
    expect(composer.recoveredExchanges.value.has('failed-exchange')).toBe(true)
    await vi.waitFor(async () =>
      expect(await store.value('assistant.composer')).toMatchObject({
        text: 'Завтрак с поправкой',
        recoveredExchangeIds: ['failed-exchange'],
      }),
    )
  })

  it('retains recovered history after clearing, sending, handing off and reloading the draft', async () => {
    let composer = await open()
    expect(composer.recoveredExchanges.value.size).toBe(0) // Old snapshots have no marks.
    expect(await composer.restore({ text: 'Завтрак' }, '', null, 'failed-exchange')).toBe(true)
    composer.draft.value = ''
    composer.draft.value = 'Новый запрос'
    const sending = await composer.prepare(null)
    await composer.delivered(sending)
    expect(composer.draft.value).toBe('')
    expect(composer.recoveredExchanges.value.has('failed-exchange')).toBe(true)
    composer.stop()
    await handOff(store, null, { mode: 'append', text: 'Список покупок' })
    composer = await open()
    expect(composer.draft.value).toBe('Список покупок')
    expect(composer.recoveredExchanges.value.has('failed-exchange')).toBe(true)
    expect(await store.value('assistant.composer')).toMatchObject({
      text: 'Список покупок',
      recoveredExchangeIds: ['failed-exchange'],
    })
  })

  it('keeps the recovery mark when a journaled send clears the restored input on reload', async () => {
    const first = await open()
    expect(await first.restore({ text: 'Завтрак' }, '', null, 'failed-exchange')).toBe(true)
    const sent = await first.prepare(null)
    pending = [{ key: sent.key, state: 'uncertain' } as Submission]
    first.stop()
    const reloaded = await open()
    expect(reloaded.draft.value).toBe('')
    expect(reloaded.recoveredExchanges.value.has('failed-exchange')).toBe(true)
    expect(reloaded.unsent.value).toEqual([])
  })

  it.each(['text', 'photo', 'reply'] as const)(
    'leaves an editor occupied by %s intact and never marks its recovery',
    async (occupied) => {
      const composer = await open()
      if (occupied === 'text') composer.draft.value = 'Свой текст'
      if (occupied === 'photo') composer.setPhoto(new Blob(['own photo']))
      if (occupied === 'reply') composer.replyTo.value = { messageId: 'own-reply', preview: 'Свое' }
      const before = {
        text: composer.draft.value,
        photo: composer.photo.value,
        reply: composer.replyTo.value,
      }
      expect(
        await composer.restore({ text: 'Старый запрос', image_id: 'old-photo' }, '', null, 'old'),
      ).toBe(false)
      expect(composer.draft.value).toBe(before.text)
      expect(composer.photo.value).toBe(before.photo)
      expect(composer.replyTo.value).toBe(before.reply)
      expect(composer.recoveredExchanges.value.size).toBe(0)
    },
  )

  it('keeps failed recovery unmarked and immediately retryable when storage rejects its snapshot', async () => {
    const composer = await open()
    await vi.waitFor(() => expect(composer.persistedVersion.value).toBeGreaterThan(0))
    const saving = vi.spyOn(store, 'setValue').mockRejectedValueOnce(new Error('storage full'))
    const body = { text: 'Завтрак', reply_to_message_id: 'reply', image_id: 'photo' }
    await expect(composer.restore(body, 'Порция', null, 'failed-exchange')).rejects.toThrow(
      'storage full',
    )
    expect(composer.storageError.value).toBeInstanceOf(Error)
    expect(composer.recoveredExchanges.value.size).toBe(0)
    expect(composer.draft.value).toBe('')
    expect(composer.replyTo.value).toBeNull()
    expect(composer.photo.value).toBeNull()
    expect(await store.value('assistant.composer')).not.toMatchObject({
      recoveredExchangeIds: ['failed-exchange'],
    })
    saving.mockRestore()
    expect(await composer.restore(body, 'Порция', null, 'failed-exchange')).toBe(true)
    expect(composer.storageError.value).toBeNull()
    expect(composer.recoveredExchanges.value.has('failed-exchange')).toBe(true)
  })

  it('preserves newer edits when a recovery save fails', async () => {
    const composer = await open()
    await vi.waitFor(() => expect(composer.persistedVersion.value).toBeGreaterThan(0))
    let reject: ((reason: Error) => void) | undefined
    vi.spyOn(store, 'setValue').mockImplementationOnce(
      () => new Promise<void>((_resolve, fail) => (reject = fail)),
    )
    const restoring = composer.restore({ text: 'Старый запрос' }, '', null, 'old')
    const failed = expect(restoring).rejects.toThrow('storage full')
    await vi.waitFor(() => expect(reject).toBeDefined())
    composer.draft.value = 'Новый запрос'
    composer.replyTo.value = { messageId: 'new-reply', preview: 'Новое' }
    const photo = new Blob(['new photo'])
    composer.setPhoto(photo)
    reject!(new Error('storage full'))
    await failed
    expect(composer.draft.value).toBe('Новый запрос')
    expect(composer.replyTo.value?.messageId).toBe('new-reply')
    expect(composer.photo.value?.blob).toBe(photo)
    await vi.waitFor(async () =>
      expect(await store.value('assistant.composer')).toMatchObject({
        text: 'Новый запрос',
        recoveredExchangeIds: [],
      }),
    )
  })

  it.each([false, true])(
    'isolates an in-flight recovery from a new account, including a failed save (%s)',
    async (fail) => {
      const owner = shallowRef<Pick<
        AccountStore,
        'value' | 'setValue' | 'draft' | 'deleteDraft'
      > | null>(store)
      const scope = effectScope()
      scopes.push(scope)
      const composer = scope.run(() =>
        useComposerDraft(owner, shallowRef({ pending: async () => [] })),
      )!
      await vi.waitFor(() => expect(composer.persistedVersion.value).toBeGreaterThan(0))
      const save = store.setValue.bind(store)
      let release: (() => void) | undefined
      vi.spyOn(store, 'setValue').mockImplementationOnce(async (name, value) => {
        await new Promise<void>((resolve) => (release = resolve))
        if (fail) throw new Error('old account storage failed')
        await save(name, value)
      })
      const restoring = composer.restore({ text: 'Личный запрос A' }, '', null, 'failure-A')
      await vi.waitFor(() => expect(release).toBeDefined())
      const second = {
        value: async <T>() => ({ text: 'Личный черновик B' }) as T,
        setValue: vi.fn(async () => undefined),
        draft: async () => undefined,
        deleteDraft: async () => undefined,
      }
      owner.value = second
      // The old draft is cleared in the same turn, before B is read or editable.
      expect(composer.ready.value).toBe(false)
      expect(composer.draft.value).toBe('')
      expect(composer.recoveredExchanges.value.size).toBe(0)
      await vi.waitFor(() => expect(composer.ready.value).toBe(true))
      release!()
      expect(await restoring).toBe(false)
      expect(composer.draft.value).toBe('Личный черновик B')
      expect(composer.recoveredExchanges.value.size).toBe(0)
      expect(composer.storageError.value).toBeNull()
      expect(second.setValue).toHaveBeenCalledWith(
        'assistant.composer',
        expect.objectContaining({ text: 'Личный черновик B', recoveredExchangeIds: [] }),
      )
      if (!fail)
        expect(await store.value('assistant.composer')).toMatchObject({
          text: 'Личный запрос A',
          recoveredExchangeIds: ['failure-A'],
        })
    },
  )

  it('fails before upload when the complete input cannot be saved', async () => {
    const composer = await open()
    composer.draft.value = 'Съел кашу'
    vi.spyOn(store, 'setValue').mockRejectedValue(new Error('storage full'))
    await expect(composer.prepare(null)).rejects.toThrow('storage full')
    expect(composer.draft.value).toBe('Съел кашу')
  })

  it('retains a failed upload separately when the user has already written another draft', async () => {
    const composer = await open()
    composer.draft.value = 'Первый завтрак'
    composer.replyTo.value = { messageId: 'reply-A', preview: 'A' }
    composer.setPhoto(new Blob(['photo-A']))
    const sent = await composer.prepare(null)
    composer.draft.value = 'Новый черновик'
    composer.replyTo.value = { messageId: 'reply-B', preview: 'B' }
    composer.setPhoto(new Blob(['photo-B']))
    await composer.failed(sent, false)
    expect(composer.unsent.value[0]).toMatchObject({
      key: sent.key,
      body: { text: 'Первый завтрак', reply_to_message_id: 'reply-A' },
    })
    expect(composer.unsent.value[0]?.photo?.size).toBe(7)
    composer.stop()
    const restored = await open()
    expect(restored.draft.value).toBe('Новый черновик')
    expect(restored.replyTo.value?.messageId).toBe('reply-B')
    expect(restored.unsent.value[0]?.body.text).toBe('Первый завтрак')
    expect(await restored.photo.value?.blob?.text()).toBe('photo-B')
    expect(await restored.unsent.value[0]?.photo?.text()).toBe('photo-A')
  })

  it('keeps durable receipt ownership until clearing the delivered draft has persisted', async () => {
    const composer = await open()
    composer.draft.value = 'Завтрак'
    const sent = await composer.prepare(null)
    expect(await draftOwnsSubmission(store, sent.key)).toBe(true)
    const save = store.setValue.bind(store)
    let release: (() => void) | undefined
    vi.spyOn(store, 'setValue').mockImplementation(async (name, value) => {
      await new Promise<void>((resolve) => {
        release = resolve
      })
      await save(name, value)
    })
    const completion = composer.delivered(sent)
    await vi.waitFor(() => expect(release).toBeDefined())
    expect(await draftOwnsSubmission(store, sent.key)).toBe(true)
    release!()
    await completion
    expect(await draftOwnsSubmission(store, sent.key)).toBe(false)
  })

  it('ignores a disposed delivery callback after a newer composer has saved its draft', async () => {
    const old = await open()
    old.draft.value = 'Первый'
    const sent = await old.prepare(null)
    old.stop()
    const current = await open()
    current.draft.value = 'Новый'
    await current.prepare(null)
    await old.delivered(sent)
    expect(await store.value('assistant.composer')).toMatchObject({ text: 'Новый' })
  })

  it('drains queued typing after navigation before a new composer hydrates the same store', async () => {
    const save = store.setValue.bind(store)
    let release: (() => void) | undefined
    let firstWrite = true
    vi.spyOn(store, 'setValue').mockImplementation(async (name, value) => {
      if (firstWrite) {
        firstWrite = false
        await new Promise<void>((resolve) => {
          release = resolve
        })
      }
      await save(name, value)
    })
    const previous = await open()
    await vi.waitFor(() => expect(release).toBeDefined())
    previous.draft.value = 'Новый текст перед уходом'
    previous.replyTo.value = { messageId: 'reply', preview: 'Порция' }
    previous.setPhoto(new Blob(['photo'], { type: 'image/jpeg' }))
    previous.stop()
    const nextComposer = open()
    release!()
    const current = await nextComposer
    expect(current.draft.value).toBe('Новый текст перед уходом')
    expect(current.replyTo.value?.messageId).toBe('reply')
    expect(current.photo.value?.blob?.size).toBe(5)
    expect(await store.value('assistant.composer')).toMatchObject({
      text: 'Новый текст перед уходом',
    })
  })

  it('hands a confirmed queued edit back exactly once across remount and later send', async () => {
    const composer = await open()
    const body = {
      text: 'Synthetic restored speech',
      image_id: 'photo',
      transcription_id: 'speech',
      reply_to_message_id: 'reply',
    }
    expect(await composer.restore(body, 'Synthetic reply', null, 'control-key')).toBe(true)
    expect(await store.value('assistant.composer')).toMatchObject({
      recoveredExchangeIds: ['control-key'],
      transcriptionId: 'speech',
    })
    const sent = await composer.prepare(null)
    expect(sent.body).toEqual(body)
    await composer.delivered(sent)
    composer.stop()
    const reopened = await open()
    expect(await reopened.restore(body, 'Synthetic reply', null, 'control-key')).toBe(true)
    expect(reopened.draft.value).toBe('')
    expect(reopened.photo.value).toBeNull()
  })

  it('keeps a queued edit envelope outside an occupied composer', async () => {
    const composer = await open()
    composer.draft.value = 'New independent draft'
    expect(await composer.restore({ text: 'Withdrawn source' }, '', null, 'control-key')).toBe(
      false,
    )
    const snapshot = await composer.prepare(null)
    expect(snapshot.body).toEqual({ text: 'New independent draft' })
    expect(await store.value('assistant.composer')).toMatchObject({ recoveredExchangeIds: [] })
  })

  it('never copies a previous account draft into an account whose hydration failed', async () => {
    const owner = shallowRef<Pick<
      AccountStore,
      'value' | 'setValue' | 'draft' | 'deleteDraft'
    > | null>(store)
    const scope = effectScope()
    scopes.push(scope)
    const composer = scope.run(() =>
      useComposerDraft(owner, shallowRef({ pending: async () => [] })),
    )!
    await vi.waitFor(() => expect(composer.ready.value).toBe(true))
    composer.draft.value = 'Личный черновик A'
    const sent = await composer.prepare(null)
    const second = {
      value: async () => {
        throw new Error('storage unavailable')
      },
      setValue: vi.fn(),
      draft: async () => undefined,
      deleteDraft: async () => undefined,
    }
    owner.value = second
    await vi.waitFor(() => expect(composer.storageError.value).not.toBeNull())
    expect(composer.ready.value).toBe(false)
    expect(composer.draft.value).toBe('')
    await composer.delivered(sent)
    expect(second.setValue).not.toHaveBeenCalled()
  })
})
