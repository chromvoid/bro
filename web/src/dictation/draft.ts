/**
 * Merges dictation into a draft: partial and final results replace only the dictated suffix,
 * and once the user edits the text by hand, later recognition never overwrites it.
 */
export class DictationDraft {
  private last: string
  private edited = false

  constructor(private readonly original: string) {
    this.last = original
  }

  get manuallyEdited(): boolean {
    return this.edited
  }

  /** Returns the draft to show after a recognition result for the current text. */
  update(current: string, recognized: string): string {
    if (current !== this.last) this.edited = true
    if (this.edited) return current
    this.last = joinDraft(this.original, recognized)
    return this.last
  }

  /** Cancelling restores the original draft unless the user already edited it. */
  cancel(current: string): string {
    return !this.edited && current === this.last ? this.original : current
  }
}

/** Dictated text goes after what was already typed, separated by one space. */
export function joinDraft(original: string, addition: string): string {
  const separator = original && !/\s$/.test(original) ? ' ' : ''
  return original + separator + addition
}
