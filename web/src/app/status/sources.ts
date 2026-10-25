/** One pure function per source of the status line: inputs and handlers in, one item or none out. */
import { CloudOff, Mic, MicOff, Phone, Upload, Volume2 } from '@lucide/vue'
import { ambientErrorKey, visibleAmbientFailure, type AmbientState } from '@/ambient/native'
import type { Call } from '@/calls/api'
import type { CallModel } from '@/calls/useCalls'
import type { PlaybackState } from '@/speech/playback'
import { PRIORITY, type StatusAction, type StatusItem } from './statusModel'

type Translate = (key: string, values?: Record<string, unknown>) => string

/** A call this account holds on another device: ending it stays one tap away. */
export function callStatus(model: CallModel, t: Translate, end: (call: Call) => void) {
  if (model.mode !== 'elsewhere') return null
  const ringing = model.call.state === 'ringing'
  const action: StatusAction = {
    label: t(ringing ? 'calls.cancel' : 'calls.hangUp'),
    run: () => end(model.call),
    variant: 'destructive',
    testid: 'call-elsewhere-end',
  }
  return {
    id: 'call',
    rank: PRIORITY.call,
    icon: Phone,
    tone: 'active',
    line: t(ringing ? 'calls.elsewhereCalling' : 'calls.elsewhere'),
    quick: action,
    actions: [action],
    sticky: true,
    testid: 'call-elsewhere',
  } satisfies StatusItem
}

export interface AmbientInput {
  supported: boolean
  state: AmbientState | null
  failure: string | null
  busy: boolean
  signedIn: boolean
}

/** Background recording: running, stopped by a problem, or audio still waiting to upload. */
export function ambientStatus(
  input: AmbientInput,
  t: Translate,
  handlers: { open: () => void; stop: () => void },
): StatusItem | null {
  if (!input.supported) return null
  const { state } = input
  const failure = visibleAmbientFailure(state, input.failure, input.signedIn)
  const queued = state?.queued_batches ?? 0
  const queue = queued ? t('ambientAudio.queue', { count: queued }) : ''
  const manage: StatusAction = {
    label: t('ambientAudio.controls'),
    run: handlers.open,
    variant: 'outline',
    testid: 'ambient-open',
  }
  const detail = (...parts: string[]) => parts.filter(Boolean).join(' ')
  if (state?.recording) {
    const stop: StatusAction = {
      label: t('ambientAudio.stop'),
      run: handlers.stop,
      variant: 'destructive',
      testid: 'ambient-stop',
    }
    return {
      id: 'recording',
      rank: PRIORITY.recording,
      icon: Mic,
      tone: 'danger',
      line: t(failure ? 'status.ambient.uploadIssue' : 'status.ambient.recording'),
      detail: detail(
        t('ambientAudio.recording'),
        failure ? t(ambientErrorKey(failure)) : '',
        queue,
      ),
      quick: { ...stop, disabled: input.busy },
      actions: [
        { ...stop, disabled: input.busy },
        { ...manage, disabled: input.busy },
      ],
      sticky: true,
      testid: 'ambient-status',
    }
  }
  if (failure)
    return {
      id: 'ambient',
      rank: PRIORITY.ambientStopped,
      icon: MicOff,
      tone: 'warning',
      line: t('status.ambient.stopped'),
      detail: detail(t(ambientErrorKey(failure)), queue),
      actions: [{ ...manage, disabled: input.busy }],
      sticky: false,
      testid: 'ambient-status',
    }
  if (queued)
    return {
      id: 'ambient',
      rank: PRIORITY.queued,
      icon: Upload,
      tone: 'neutral',
      line: t('status.ambient.queued', { count: queued }),
      detail: detail(queue, t('ambientAudio.offline')),
      actions: [{ ...manage, disabled: input.busy }],
      sticky: false,
      testid: 'ambient-status',
    }
  return null
}

/** Without a connection the screens show what they loaded last and send nothing. */
export function connectionStatus(online: boolean, t: Translate): StatusItem | null {
  if (online) return null
  return {
    id: 'offline',
    rank: PRIORITY.offline,
    icon: CloudOff,
    tone: 'warning',
    line: t('status.offline'),
    detail: t('connection.offline'),
    actions: [],
    sticky: false,
    testid: 'connection-status',
  }
}

/** Speech of a reply or a voice preview; stopping it stays one tap away. */
export function speechStatus(
  playback: PlaybackState,
  t: Translate,
  stop: () => void,
): StatusItem | null {
  if (playback.problem) {
    const close: StatusAction = {
      label: t('common.close'),
      run: stop,
      variant: 'outline',
      testid: 'speech-player-stop',
    }
    return {
      id: 'speech',
      rank: PRIORITY.speechProblem,
      icon: Volume2,
      tone: 'warning',
      line: t('status.speech.failed'),
      detail: t(`speechPlayback.errors.${playback.problem}`),
      quick: close,
      actions: [close],
      sticky: false,
      testid: 'speech-player',
    }
  }
  if (playback.status === 'idle') return null
  const preview = playback.key === 'preview'
  const loading = playback.status === 'loading'
  const halt: StatusAction = {
    label: t('speechPlayback.stop'),
    run: stop,
    variant: 'outline',
    testid: 'speech-player-stop',
  }
  const actions = [halt]
  if (playback.key?.startsWith('turn:'))
    actions.push({
      label: t('speechPlayback.returnToConversation'),
      to: { name: 'assistant' },
      variant: 'ghost',
      testid: 'speech-player-return',
    })
  return {
    id: 'speech',
    rank: PRIORITY.speech,
    icon: Volume2,
    tone: 'active',
    line: t(
      preview
        ? loading
          ? 'speechPlayback.previewLoading'
          : 'speechPlayback.previewPlaying'
        : loading
          ? 'speechPlayback.loading'
          : 'speechPlayback.playing',
    ),
    detail: t('speechPlayback.aiVoice'),
    quick: halt,
    actions,
    sticky: false,
    testid: 'speech-player',
  }
}
