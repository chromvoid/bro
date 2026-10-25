/** Delivery ends at durable admission; execution and its domain results belong to history. */
import {
  isKnownUnadmitted,
  UnpersistedOutcome,
  type Submission,
  type SubmissionJournal,
} from '@/core/journal'
import type { Api } from '@/core/http'
import { uploadPhoto } from './photoUploads'

export const ENDPOINT = '/v1/conversation/messages'
export const LOOKUP = '/v1/conversation/submissions/by-key/'

export interface MessageBody {
  text?: string
  reply_to_message_id?: string
  transcription_id?: string
  image_id?: string
}

export interface Delivery {
  state: 'accepted' | 'uncertain'
  submission: Submission
}

export class RejectedDelivery extends Error {
  constructor(
    readonly reason: unknown,
    readonly submission: Submission,
  ) {
    super('Message was not admitted')
  }
}

/**
 * Upload failures precede admission and keep the complete composer draft. Only an actual
 * journal entry can establish an uncertain send; an arbitrary network error cannot.
 */
export async function deliverMessage(
  journal: Pick<SubmissionJournal, 'submit' | 'pending' | 'acknowledge'>,
  api: Pick<Api, 'upload'>,
  body: MessageBody,
  photo: Blob | null,
  key: string,
  beforeSend: () => void = () => undefined,
  expectedUser?: string,
): Promise<Delivery> {
  beforeSend()
  // Usually uploaded already, since the photo was chosen (photoUploads).
  if (photo)
    body = { ...body, image_id: await uploadPhoto(api, photo, { beforeSend, expectedUser }) }
  beforeSend()
  try {
    const submission = await journal.submit({
      endpoint: ENDPOINT,
      lookupPrefix: LOOKUP,
      body,
      found: 'accepted',
      key,
      beforeSend,
      context: expectedUser ? { expectedUser } : {},
    })
    return { state: 'accepted', submission }
  } catch (error) {
    const submission =
      error instanceof UnpersistedOutcome
        ? error.submission
        : (await journal.pending()).find((entry) => entry.key === key)
    if (submission && submission.body !== JSON.stringify(body)) throw error
    if (submission && isKnownUnadmitted(submission)) {
      // The caller decides whether the composer still owns this exact input.
      throw new RejectedDelivery(
        error instanceof UnpersistedOutcome ? error.reason : error,
        submission,
      )
    } else if (submission) {
      return {
        state: ['accepted', 'resolved'].includes(submission.state) ? 'accepted' : 'uncertain',
        submission,
      }
    }
    throw error
  }
}
