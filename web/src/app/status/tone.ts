import type { StatusTone } from './statusModel'

/** The icon colour of a status item; the words carry the meaning, the colour only supports it. */
export const TONE: Record<StatusTone, string> = {
  active: 'text-primary',
  danger: 'text-destructive',
  warning: 'text-warning',
  neutral: 'text-muted-foreground',
}
