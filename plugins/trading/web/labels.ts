import strings from './labels.json'

/**
 * Russian texts of the trading UI, the same as the Kotlin module's resources. Placeholders are
 * `%1$s`, `%2$s`…; a code this build does not know gets a neutral label, never a crash.
 */
export const STRINGS = strings
export type StringKey = keyof typeof STRINGS

export function t(key: StringKey, ...args: string[]): string {
  return STRINGS[key].replace(/%(\d+)\$s/g, (_, index: string) => args[Number(index) - 1] ?? '')
}

const choose = (map: Record<string, StringKey>, value: string | null | undefined) =>
  t(value && value in map ? map[value]! : 'unknown_value')

export const methodLabel = (method: string) =>
  choose({ lifo: 'method_lifo', fifo: 'method_fifo' }, method)

export const connectionLabel = (state: string | null | undefined) =>
  choose(
    {
      active: 'state_active',
      validating: 'state_validating',
      degraded: 'state_degraded',
      auth_failed: 'state_auth_failed',
      ip_blocked: 'state_ip_blocked',
      key_expired: 'state_key_expired',
      secret_unavailable: 'state_secret_unavailable',
      disconnected: 'state_disconnected',
      not_configured: 'state_not_configured',
    },
    state,
  )

export const streamLabel = (state: string) =>
  choose({ live: 'stream_live', reconnecting: 'stream_reconnecting', down: 'stream_down' }, state)

export const sideLabel = (side: string) => choose({ buy: 'side_buy', sell: 'side_sell' }, side)

const ORDER_STATUSES = [
  'New',
  'PartiallyFilled',
  'Filled',
  'Cancelled',
  'PartiallyFilledCanceled',
  'Rejected',
  'Untriggered',
  'Triggered',
  'Deactivated',
  'Active',
  'Unknown',
] as const

/** Bybit's order status in words; «PartiallyFilledCanceled» is a closed order, not a partial one. */
export const orderStatusLabel = (status: string) =>
  (ORDER_STATUSES as readonly string[]).includes(status)
    ? t(`order_${status}` as StringKey)
    : status

export const orderTypeLabel = (type: string) =>
  type === 'Limit' || type === 'Market' ? t(`type_${type}`) : type

export const domainLabel = (domain: string) =>
  choose(
    {
      wallet: 'domain_wallet',
      executions: 'domain_executions',
      orders: 'domain_orders',
      open_orders: 'domain_open_orders',
      deposits: 'domain_deposits',
      internal_deposits: 'domain_internal_deposits',
      withdrawals: 'domain_withdrawals',
      unified_ledger: 'domain_unified_ledger',
      funding_ledger: 'domain_funding_ledger',
      funding_wallet: 'domain_funding_wallet',
      earn: 'domain_earn',
    },
    domain,
  )

export const phaseLabel = (phase: string) =>
  choose(
    {
      idle: 'phase_idle',
      backfill: 'phase_backfill',
      incremental: 'phase_incremental',
      refill: 'phase_refill',
      error: 'phase_error',
    },
    phase,
  )

const OPERATIONS = [
  'deposit',
  'internal_deposit',
  'withdrawal',
  'internal_withdrawal',
  'transfer',
  'p2p_sale',
  'p2p_sale_cancelled',
  'p2p_purchase',
  'earn_stake',
  'earn_redeem',
  'earn_reward',
  'earn_other',
  'bot_transfer',
  'airdrop',
  'bonus',
  'fee_refund',
  'interest',
  'convert',
] as const

/** What happened; an operation this build does not know shows Bybit's own description. */
export const operationLabel = (operation: string, note?: string | null) =>
  (OPERATIONS as readonly string[]).includes(operation)
    ? t(`op_${operation}` as StringKey)
    : note || t('unknown_value')

export const flowKindLabel = (kind: string) =>
  choose(
    Object.fromEntries(
      [
        'deposit',
        'withdrawal',
        'transfer',
        'p2p',
        'earn',
        'bot',
        'reward',
        'interest',
        'convert',
        'other',
      ].map((value) => [value, `flow_kind_${value}` as StringKey]),
    ),
    kind,
  )

/** An account of the user or the other side of a movement. */
export const placeLabel = (place: string | null | undefined) =>
  choose(
    Object.fromEntries(
      [
        'unified',
        'funding',
        'earn',
        'spot',
        'chain',
        'bybit_user',
        'p2p',
        'bybit',
        'bot',
        'market',
      ].map((value) => [value, `place_${value}` as StringKey]),
    ),
    place,
  )

/** The owner's mark of a deposit (`incoming`) or withdrawal, in words. */
export const treatmentLabel = (treatment: string, incoming: boolean) =>
  treatment === 'own_wallet' || treatment === 'external'
    ? t(`treatment_${treatment}_${incoming ? 'in' : 'out'}`)
    : t('treatment_auto')

export const earnLabel = (category: string) =>
  choose(
    { FlexibleSaving: 'earn_FlexibleSaving', OnChain: 'earn_OnChain', FixedTerm: 'earn_FixedTerm' },
    category,
  )

const ERRORS: Record<string, StringKey> = {
  key_not_read_only: 'error_key_not_read_only',
  credential_rejected: 'error_credential_rejected',
  ip_not_whitelisted: 'error_ip_not_whitelisted',
  permission_missing: 'error_permission_missing',
  unsupported_account: 'error_unsupported_account',
  key_expired: 'error_key_expired',
  account_changed: 'error_account_changed',
  revision_conflict: 'error_revision_conflict',
  dependency_unavailable: 'error_dependency_unavailable',
  not_configured: 'not_connected',
  secret_unavailable: 'error_secret_unavailable',
  invalid_arguments: 'error_invalid_arguments',
  forbidden: 'error_forbidden',
  tool_unavailable: 'error_forbidden',
  quota_exceeded: 'error_quota_exceeded',
  deadline_exceeded: 'error_deadline_exceeded',
  network_unavailable: 'error_network',
  internal: 'error_internal',
  refill_in_progress: 'error_refill_in_progress',
  range_too_large: 'error_range_too_large',
  symbol_unknown: 'error_symbol_unknown',
  invalid_output: 'error_invalid_output',
}

/** The text for an error; unknown codes show the service's own safe message. */
export function errorText(error: { code: string; message: string }, egress: string[] = []): string {
  const key = ERRORS[error.code]
  if (!key) return t('error_other', error.message.slice(0, 300))
  return error.code === 'ip_not_whitelisted' ? t(key, egress.join(', ') || '—') : t(key)
}
