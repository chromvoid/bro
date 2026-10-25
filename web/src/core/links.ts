/** External web addresses only, without credentials or browser-local protocols. */
export function safeWebLink(url: string): boolean {
  try {
    const parsed = new URL(url)
    return (
      (parsed.protocol === 'https:' || parsed.protocol === 'http:') &&
      !parsed.username &&
      !parsed.password
    )
  } catch {
    return false
  }
}
