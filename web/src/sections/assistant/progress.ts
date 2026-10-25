/** Transient progress explains the work; it never asserts that a domain write committed. */

export type Progress =
  | 'searchingFood'
  | 'searchingPackaged'
  | 'searchingBarcode'
  | 'searchingWeb'
  | 'calculating'
  | 'comparingFood'
  | 'addingFood'
  | 'editingFood'
  | 'removingFood'
  | 'undoingFood'
  | 'editingDraft'
  | 'cancellingDraft'
  | 'savingCatalog'
  | 'savingComposition'
  | 'savingPortion'
  | 'updatingFavorites'
  | 'readingDiary'
  | 'readingDrafts'
  | 'readingCompositions'
  | 'readingProfile'
  | 'updatingProfile'
  | 'savingWeight'
  | 'preparingGoal'
  | 'reading'
  | 'researching'
  | 'lookingUp'
  | 'updatingSources'
  | 'readingTasks'
  | 'changingTasks'
  | 'delegatingTask'
  | 'answeringTask'
  | 'reviewingTaskResult'
  | 'readingTimers'
  | 'startingTimer'
  | 'settingReminder'
  | 'cancellingTimer'
  | 'cancellingReminder'
  | 'readingNotifications'
  | 'updatingNotifications'
  | 'readingPersonal'
  | 'checkingSources'
  | 'syncingSource'
  | 'savingMeasurement'
  | 'removingMeasurement'
  | 'readingWeather'
  | 'readingPlaces'
  | 'checkingSystem'
  | 'applyingProposal'
  | 'cancellingProposal'
  | 'processing'

// Exact tool identities describe observed work, not a predicted pipeline or a receipt.
// Search/resolve can fall back to external sources; imports and saved recipes are not meals.
const TOOL_PROGRESS: ReadonlyMap<string, Progress> = new Map([
  ['nutrition.search', 'searchingFood'],
  ['nutrition.resolve', 'searchingFood'],
  ['nutrition.packaged_search', 'searchingPackaged'],
  ['nutrition.barcode', 'searchingBarcode'],
  ['nutrition.web_search', 'searchingWeb'],
  ['nutrition.estimate', 'calculating'],
  ['nutrition.compare', 'comparingFood'],
  ['nutrition.log', 'addingFood'],
  ['nutrition.log_dish', 'addingFood'],
  ['nutrition.log_composition', 'addingFood'],
  ['nutrition.import_and_log', 'addingFood'],
  ['nutrition.update', 'editingFood'],
  ['nutrition.update_dish', 'editingFood'],
  ['nutrition.delete', 'removingFood'],
  ['nutrition.delete_dish', 'removingFood'],
  ['nutrition.undo', 'undoingFood'],
  ['nutrition.update_draft', 'editingDraft'],
  ['nutrition.cancel_draft', 'cancellingDraft'],
  ['nutrition.import', 'savingCatalog'],
  ['nutrition.save_composition', 'savingComposition'],
  ['nutrition.portion', 'savingPortion'],
  ['nutrition.favorite', 'updatingFavorites'],
  ['nutrition.day', 'readingDiary'],
  ['nutrition.drafts', 'readingDrafts'],
  ['nutrition.draft', 'readingDrafts'],
  ['nutrition.compositions', 'readingCompositions'],
  ['nutrition.profile', 'readingProfile'],
  ['nutrition.profile_update', 'updatingProfile'],
  ['nutrition.weight', 'savingWeight'],
  ['nutrition.goal_propose', 'preparingGoal'],
  ['assistant.context', 'reading'],
  ['assistant.history_search', 'reading'],
  ['assistant.history_read', 'reading'],
  ['research.investigate', 'researching'],
  ['research.quick_search', 'lookingUp'],
  ['research.sources', 'reading'],
  ['research.source_change', 'updatingSources'],
  ['tasks.get', 'readingTasks'],
  ['tasks.list', 'readingTasks'],
  ['tasks.projects', 'readingTasks'],
  ['tasks.assistant_status', 'readingTasks'],
  ['tasks.operate', 'changingTasks'],
  ['tasks.delegate', 'delegatingTask'],
  ['tasks.delegation_change', 'delegatingTask'],
  ['tasks.answer', 'answeringTask'],
  ['tasks.review_result', 'reviewingTaskResult'],
  ['timers.list', 'readingTimers'],
  ['timers.start', 'startingTimer'],
  ['timers.remind', 'settingReminder'],
  ['timers.cancel', 'cancellingTimer'],
  ['timers.cancel_reminder', 'cancellingReminder'],
  ['assistant.notifications', 'readingNotifications'],
  ['assistant.notification_update', 'updatingNotifications'],
  ['assistant.archive_read', 'reading'],
  ['assistant.system_status', 'checkingSystem'],
  ['assistant.confirm', 'applyingProposal'],
  ['assistant.proposal_cancel', 'cancellingProposal'],
  // A sync request only queues work: the label says so, never that data arrived.
  ['personal.body_history', 'readingPersonal'],
  ['personal.resolved', 'readingPersonal'],
  ['personal.caliper_history', 'readingPersonal'],
  ['personal.sources', 'checkingSources'],
  ['personal.sync_status', 'checkingSources'],
  ['personal.sync', 'syncingSource'],
  ['personal.caliper_add', 'savingMeasurement'],
  ['personal.caliper_complete', 'savingMeasurement'],
  ['personal.caliper_delete', 'removingMeasurement'],
  ['weather.forecast', 'readingWeather'],
  ['places.history', 'readingPlaces'],
])

/** The tools that have their own label. */
export const TOOLS: readonly string[] = [...TOOL_PROGRESS.keys()]

/** The live activity snapshot's label for a running tool step. */
export function toolProgress(tool: string): Progress {
  return TOOL_PROGRESS.get(tool) ?? 'processing'
}
