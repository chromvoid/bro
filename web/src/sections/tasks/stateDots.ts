import type { TaskState } from './api'

/** The colour dot of each board state, beside its name in the state tabs and the move sheet. */
export const STATE_DOTS: Record<TaskState, string> = {
  todo: 'bg-muted-foreground',
  in_progress: 'bg-primary',
  waiting: 'bg-warning-fill',
  done: 'bg-muted-foreground/40',
}
