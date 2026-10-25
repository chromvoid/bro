import { describe, expect, it } from 'vitest'
import { stepLabel } from './activity'
import ru from '@/locales/ru.json'
import { TOOLS, toolProgress } from './progress'

describe('conversation progress', () => {
  it('labels the observed food stages of live steps', () => {
    const tools = ['nutrition.estimate', 'nutrition.search', 'nutrition.log']
    const stages = tools.map(toolProgress)
    expect(stages).toEqual(['calculating', 'searchingFood', 'addingFood'])
    expect(
      tools.map((label) =>
        stepLabel({
          id: 'step',
          kind: 'tool',
          status: 'running',
          label,
          started_at: '',
          duration_ms: null,
        }),
      ),
    ).toEqual(stages.map((stage) => `assistant.progress.${stage}`))
    expect(toolProgress('assistant.history_read')).toBe('reading')
  })
  it('distinguishes corrections, removals and cancellation from adding eaten food', () => {
    expect(['nutrition.update', 'nutrition.update_dish'].map(toolProgress)).toEqual([
      'editingFood',
      'editingFood',
    ])
    expect(toolProgress('nutrition.delete_dish')).toBe('removingFood')
    expect(toolProgress('nutrition.undo')).toBe('undoingFood')
    expect(toolProgress('nutrition.update_draft')).toBe('editingDraft')
    expect(toolProgress('nutrition.cancel_draft')).toBe('cancellingDraft')
    expect(toolProgress('nutrition.import_and_log')).toBe('addingFood')
    expect(toolProgress('nutrition.import')).toBe('savingCatalog')
    expect(toolProgress('nutrition.save_composition')).toBe('savingComposition')
    expect(toolProgress('nutrition.favorite')).toBe('updatingFavorites')
    expect(toolProgress('nutrition.goal_propose')).toBe('preparingGoal')
  })
  it('keeps product discovery separate from comparison and source-specific lookup', () => {
    expect(toolProgress('nutrition.resolve')).toBe(toolProgress('nutrition.search'))
    expect(toolProgress('nutrition.compare')).toBe('comparingFood')
    expect(toolProgress('nutrition.packaged_search')).toBe('searchingPackaged')
    expect(toolProgress('nutrition.barcode')).toBe('searchingBarcode')
    expect(toolProgress('nutrition.web_search')).toBe('searchingWeb')
  })
  it('tells a quick web lookup from a source-checked investigation', () => {
    expect(toolProgress('research.quick_search')).toBe('lookingUp')
    expect(toolProgress('research.investigate')).toBe('researching')
  })
  it('names the work on tasks, timers and reminders instead of a generic action', () => {
    expect(['tasks.list', 'tasks.get', 'tasks.projects'].map(toolProgress)).toEqual([
      'readingTasks',
      'readingTasks',
      'readingTasks',
    ])
    expect(toolProgress('tasks.operate')).toBe('changingTasks')
    expect(toolProgress('tasks.delegate')).toBe('delegatingTask')
    expect(toolProgress('timers.list')).toBe('readingTimers')
    expect(toolProgress('timers.start')).toBe('startingTimer')
    expect(toolProgress('timers.remind')).toBe('settingReminder')
    expect(toolProgress('timers.cancel')).toBe('cancellingTimer')
    expect(toolProgress('timers.cancel_reminder')).toBe('cancellingReminder')
    expect(toolProgress('assistant.notifications')).toBe('readingNotifications')
    expect(toolProgress('personal.sync')).toBe('syncingSource')
    expect(toolProgress('personal.caliper_delete')).toBe('removingMeasurement')
  })
  it('has a Russian label for every stage', () => {
    const labels = ru.assistant.progress as Record<string, string>
    for (const tool of TOOLS) expect(labels[toolProgress(tool)]).toBeTruthy()
  })
  it('does not display arbitrary tool strings', () => {
    for (const name of [
      '<script>anything</script>',
      'toString',
      'nutrition.new_tool',
      'nutrition.log.extra',
    ]) {
      expect(toolProgress(name)).toBe('processing')
    }
  })
})
