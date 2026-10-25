import { ApiError, NetworkError } from './http'

/** An i18n key describing why a request failed, in terms the user can act on. */
export function describeError(error: unknown): string {
  if (error instanceof NetworkError) return error.timedOut ? 'errors.timeout' : 'errors.network'
  if (error instanceof ApiError) {
    switch (error.status) {
      case 401:
        return 'errors.session'
      case 403:
        return 'errors.forbidden'
      case 404:
        return 'errors.notFound'
      case 409:
        return 'errors.conflict'
      case 413:
        return 'errors.tooLarge'
      case 422:
      case 400:
        return 'errors.invalid'
      case 429:
        return 'errors.limit'
      default:
        return error.status >= 500 ? 'errors.server' : 'errors.unknown'
    }
  }
  return 'errors.unknown'
}

/** The server's own explanation when it wrote one for the user, otherwise a generic reason. */
export function errorText(error: unknown, translate: (key: string) => string): string {
  if (
    error instanceof ApiError &&
    error.serverMessage &&
    error.status >= 400 &&
    error.status < 500
  ) {
    return error.serverMessage
  }
  return translate(describeError(error))
}

/** Seconds to wait before retrying a rate-limited request, if the server said so. */
export function retryAfterSeconds(error: unknown): number | null {
  if (!(error instanceof ApiError) || error.status !== 429 || !error.retryAfter) return null
  const seconds = Number(error.retryAfter)
  return Number.isFinite(seconds) && seconds > 0 ? Math.ceil(seconds) : null
}
