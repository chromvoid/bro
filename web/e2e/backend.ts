import { request, type APIRequestContext, type Cookie } from '@playwright/test'

export const ISOLATED = process.env.ASSISTANT_E2E_ISOLATED === '1'

/** An administrator session on the disposable backend, for arranging test data through the API. */
export async function administrator(baseURL: string): Promise<{
  api: APIRequestContext
  post: (path: string, body: unknown) => Promise<unknown>
}> {
  const origin = new URL(baseURL).origin
  const api = await request.newContext({ baseURL: origin, extraHTTPHeaders: { Origin: origin } })
  const login = await api.post('/v1/auth/development', { data: {} })
  if (!login.ok()) throw new Error(`development login failed: ${login.status()}`)
  const { token } = (await (await api.get('/v1/auth/csrf')).json()) as { token: string }
  const post = async (path: string, body: unknown) => {
    const response = await api.post(path, { data: body, headers: { 'X-CSRF-Token': token } })
    if (!response.ok()) throw new Error(`${path}: ${response.status()} ${await response.text()}`)
    return response.json()
  }
  return { api, post }
}

export const uniqueLogin = (prefix: string) =>
  `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`

/** A new invited person and session, without spending the production sign-in attempt limit. */
export async function freshPerson(
  baseURL: string,
  person: { prefix: string; name: string; timezone: string; scopes: string[] },
  api?: APIRequestContext,
): Promise<{ id: string; login: string; name: string; cookies: Cookie[] }> {
  const origin = new URL(baseURL).origin
  const admin = await administrator(baseURL)
  let token: string
  try {
    token = ((await admin.post('/v1/admin/tokens', { scopes: person.scopes })) as { token: string })
      .token
  } finally {
    await admin.api.dispose()
  }
  const login = uniqueLogin(person.prefix)
  // A page's request context shares its browser cookies; setup can use a temporary context.
  const session = api ?? (await request.newContext({ baseURL: origin }))
  try {
    const response = await session.post(`${origin}/v1/auth/development/redeem`, {
      headers: { Origin: origin },
      data: { token, login, name: person.name, timezone: person.timezone },
    })
    if (!response.ok()) {
      throw new Error(`development redeem: ${response.status()} ${await response.text()}`)
    }
    const me = await session.get(`${origin}/v1/me`)
    if (!me.ok()) throw new Error(`new person session: ${me.status()} ${await me.text()}`)
    const { id } = (await me.json()) as { id: string }
    return { id, login, name: person.name, cookies: (await session.storageState()).cookies }
  } finally {
    if (!api) await session.dispose()
  }
}
