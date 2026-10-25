import { playMerge } from '@/components/brandMerge'
import { MERGE_MS } from '@/components/brandMood'

/** How long Bro smiles before the launch screen fades, and how long the fade takes (boot.css). */
export const SMILE_MS = 200
export const LEAVE_MS = 320

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

function prefersStill(): boolean {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
}

/**
 * Ends the launch screen (index.html #boot, generated with the shells' splash) once the first
 * view has rendered: the dots flow into ")", the same flow as the chat's mark when an answer is
 * ready, Bro smiles for a moment and the screen fades into the app. With reduced motion the smile
 * simply appears. The screen goes even when a step of its farewell fails.
 */
export async function finishBoot(
  boot: HTMLElement | null,
  {
    still = prefersStill(),
    wait = sleep,
  }: { still?: boolean; wait?: (ms: number) => Promise<void> } = {},
): Promise<void> {
  if (!boot) return
  try {
    if (!still) {
      // Each piece starts as its dot (d) and ends as its third of the smile (data-to).
      const pieces = [...boot.querySelectorAll('.merge path')].map((path) => ({
        path,
        from: path.getAttribute('d') ?? '',
        to: path.getAttribute('data-to') ?? '',
      }))
      const stop = playMerge(pieces)
      boot.classList.add('merging')
      try {
        await wait(MERGE_MS)
      } finally {
        stop()
      }
      boot.classList.remove('merging')
    }
    boot.classList.add('smiling')
    await wait(SMILE_MS)
    boot.classList.add('leaving')
    await wait(LEAVE_MS)
  } finally {
    boot.remove()
  }
}

/**
 * The launch screen's end for this page: in the iOS app the shell's own splash covers
 * the page until it names its theme, so the launch screen just goes; a browser plays it out.
 */
export async function leaveBoot(
  boot: HTMLElement | null,
  inShell: boolean,
  options?: Parameters<typeof finishBoot>[1],
): Promise<void> {
  if (inShell) boot?.remove()
  else await finishBoot(boot, options)
}
