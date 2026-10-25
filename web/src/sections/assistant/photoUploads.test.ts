import { describe, expect, it, vi } from 'vitest'
import type { Api } from '@/core/http'
import { dropUpload, sentPhoto, uploading, uploadPhoto } from './photoUploads'

/** An upload endpoint that answers in order: each call takes the next reply. */
function server(...replies: Array<string | Error>) {
  const upload = vi.fn(async (_path: string, _photo: Blob, options: { signal?: AbortSignal }) => {
    const reply = replies.shift()
    if (options.signal?.aborted) throw new DOMException('aborted', 'AbortError')
    if (reply instanceof Error || reply === undefined) throw reply ?? new Error('no reply')
    return { status: 201, data: { id: reply, width: 4, height: 3, url: `/images/${reply}` } }
  })
  return { api: { upload } as unknown as Pick<Api, 'upload'>, upload }
}

const photo = () => new Blob([crypto.randomUUID()], { type: 'image/jpeg' })

describe('photo uploads', () => {
  it('lets the send reuse the upload started when the photo was chosen', async () => {
    const { api, upload } = server('early')
    const chosen = photo()
    const early = uploadPhoto(api, chosen, { expectedUser: 'anna', early: true })
    expect(uploading.has(chosen)).toBe(true)
    expect(await uploadPhoto(api, chosen, { expectedUser: 'anna' })).toBe('early')
    expect(await early).toBe('early')
    expect(upload).toHaveBeenCalledOnce()
    expect(uploading.has(chosen)).toBe(false)
    expect(sentPhoto('early')).toMatch(/^blob:/)
  })

  it('uploads again for another account or after a failed early upload', async () => {
    const { api, upload } = server(new TypeError('network lost'), 'retried', 'boris')
    const chosen = photo()
    await expect(uploadPhoto(api, chosen, { expectedUser: 'anna' })).rejects.toThrow('network lost')
    expect(await uploadPhoto(api, chosen, { expectedUser: 'anna' })).toBe('retried')
    expect(await uploadPhoto(api, chosen, { expectedUser: 'boris' })).toBe('boris')
    expect(upload).toHaveBeenCalledTimes(3)
  })

  it('stops the upload of a photo taken out of the draft', async () => {
    const { api, upload } = server('late', 'again')
    const chosen = photo()
    const early = uploadPhoto(api, chosen, { early: true })
    dropUpload(chosen)
    expect(uploading.has(chosen)).toBe(false)
    expect(upload.mock.calls[0]?.[2].signal?.aborted).toBe(true)
    await early.catch(() => undefined)
    // Chosen again, it starts anew.
    await uploadPhoto(api, chosen)
    expect(upload).toHaveBeenCalledTimes(2)
  })

  it('keeps an upload a send already waits for', async () => {
    const { api, upload } = server('kept')
    const chosen = photo()
    void uploadPhoto(api, chosen, { early: true })
    const sending = uploadPhoto(api, chosen)
    dropUpload(chosen)
    expect(upload.mock.calls[0]?.[2].signal?.aborted).toBe(false)
    expect(await sending).toBe('kept')
  })
})
