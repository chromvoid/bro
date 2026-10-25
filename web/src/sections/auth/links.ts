/** Invitation and reset links carry their one-time token in the fragment, never in the query. */
export interface AuthLink {
  kind: 'invite' | 'reset'
  token: string
}

export const JOIN_URL = 'https://bro.chromvoid.com/join'

export interface JoinLink extends AuthLink {
  server: string
}

/** Only a canonical HTTPS origin and one unambiguous set of fragment parameters are accepted. */
export function readJoinLink(hash: string): JoinLink | null {
  const params = new URLSearchParams(hash.replace(/^#/, ''))
  if ([...params].length !== 3) return null
  if (['server', 'kind', 'token'].some((key) => params.getAll(key).length !== 1)) return null
  const server = params.get('server')!
  const kind = params.get('kind')
  const token = params.get('token')!
  if (kind !== 'invite' && kind !== 'reset') return null
  if (!token || token.length > 128 || /[\s\p{Cc}]/u.test(token)) return null
  try {
    const url = new URL(server)
    if (url.protocol !== 'https:' || url.origin !== server) return null
  } catch {
    return null
  }
  return { server, kind, token }
}

export function browserAuthLink({ server, kind, token }: JoinLink): string {
  return `${server}/login#kind=${kind}&token=${encodeURIComponent(token)}`
}

export function readAuthLink(hash: string): AuthLink | null {
  const params = new URLSearchParams(hash.replace(/^#/, ''))
  const token = params.get('token')
  const kind = params.get('kind')
  if (!token || token.length > 128 || (kind !== 'invite' && kind !== 'reset')) return null
  return { kind, token }
}

/** The server's rule for logins: 3–40 Latin letters, digits, dots, hyphens or underscores. */
export const LOGIN_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,39}$/
export const MIN_PASSWORD = 12

/** Where to go after sign-in: only a path inside this app, never another origin. */
export function safeNext(value: unknown): string | null {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return null
  if (value.includes('\\') || /[\u0000-\u001f]/.test(value)) return null
  return value.startsWith('/login') ? null : value
}

export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Europe/Moscow'
  } catch {
    return 'Europe/Moscow'
  }
}

export function timeZones(): string[] {
  try {
    return Intl.supportedValuesOf('timeZone')
  } catch {
    return []
  }
}
