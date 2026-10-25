/**
 * Dictation from anywhere: the tab bar's microphone, the sidebar and the Today tile all call
 * the shell's `dictate()`. On the assistant screen its own composer records in place; everywhere
 * else the voice sheet records and hands the text to the composer's draft (useComposerDraft).
 */
import { inject, shallowRef, type InjectionKey } from 'vue'

export interface Voice {
  /** Starts dictation; call it straight from the tap, the microphone needs the gesture. */
  dictate(): void
}

export const VOICE: InjectionKey<Voice> = Symbol('voice')

export function useVoice(): Voice | null {
  return inject(VOICE, null)
}

/** The assistant composer's own dictation while that screen is open; false when it cannot. */
const composer = shallowRef<(() => boolean) | null>(null)

export function registerComposerDictation(dictate: () => boolean): () => void {
  composer.value = dictate
  return () => {
    if (composer.value === dictate) composer.value = null
  }
}

export function composerDictation(): (() => boolean) | null {
  return composer.value
}
