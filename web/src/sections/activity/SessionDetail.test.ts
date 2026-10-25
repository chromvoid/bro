import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { ActivitySession, ExerciseDefinition } from './api'
import ExerciseCatalog from './ExerciseCatalog.vue'
import SessionDetail from './SessionDetail.vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))

const definition = ExerciseDefinition.parse({
  id: 'bench',
  revision: 5,
  name: 'Updated name',
  equipment: 'Barbell',
  variant: '',
  load_basis: 'total',
})
const session = ActivitySession.parse({
  id: 'session',
  revision: 2,
  kind: 'strength',
  date: '2026-10-05',
  timezone: 'Europe/Moscow',
  started_at: '2026-10-05T08:30:00Z',
  duration_minutes: null,
  distance_km: '0',
  note: '',
  exercises: [
    {
      id: 'performed-bench',
      revision: 3,
      exercise_id: 'bench',
      position: 0,
      snapshot: {
        name: 'Historical bench name',
        equipment: 'Barbell',
        variant: '',
        load_basis: 'total',
      },
      note: '',
      sets: [
        {
          id: 'warmup',
          revision: 1,
          position: 0,
          kind: 'warmup',
          repetitions: 0,
          load_value: '0',
          load_unit: 'kg',
          implement_count: 1,
          note: '',
        },
        {
          id: 'working',
          revision: 4,
          position: 1,
          kind: 'working',
          repetitions: 8,
          load_value: null,
          load_unit: 'kg',
          implement_count: 1,
          note: '',
        },
      ],
    },
  ],
})
const props = { session, catalog: [definition], canWrite: true, busy: false }

describe('activity strength diary', () => {
  it('renders historical definitions, separate warmup and unknown values distinct from zero', () => {
    const wrapper = mount(SessionDetail, { props })
    expect(wrapper.get('article h3').text()).toBe('Historical bench name')
    expect(wrapper.findAll('dd').map((value) => value.text())).toEqual([
      'activity.unknown',
      '0 activity.kilometers',
      '11:30',
    ])
    const sets = wrapper.findAll('ol li')
    expect(sets[0]!.text()).toContain(
      'activity.setKinds.warmup · 0 activity.repetitionsShort · 0 activity.loadUnits.kg',
    )
    expect(sets[1]!.text()).toContain(
      'activity.setKinds.working · 8 activity.repetitionsShort · activity.unknown',
    )
    wrapper.unmount()
  })

  it('adds a partially known set without inventing load or repetitions', async () => {
    const wrapper = mount(SessionDetail, { props })
    await wrapper
      .get('article')
      .findAll('button')
      .find((button) => button.text() === 'activity.addSet')!
      .trigger('click')
    await wrapper.get('article form').trigger('submit')
    const operation = wrapper.emitted('operation')![0]!
    expect(operation.slice(0, 3)).toEqual([
      'set.add',
      {
        session_id: 'session',
        session_exercise_id: 'performed-bench',
        repetitions: null,
        load_value: null,
        load_unit: 'kg',
        implement_count: 1,
        kind: 'working',
        note: '',
      },
      { session: 2, 'performed-bench': 3 },
    ])
    expect(wrapper.find('article form').exists()).toBe(true)
    ;(operation[3] as () => void)()
    await nextTick()
    expect(wrapper.find('article form').exists()).toBe(false)
    wrapper.unmount()
  })

  it('accepts a decimal comma and sends a normalized load without replacing zero', async () => {
    const wrapper = mount(SessionDetail, { props })
    await wrapper
      .get('article')
      .findAll('button')
      .find((button) => button.text() === 'activity.addSet')!
      .trigger('click')
    await wrapper.get('#activity-set-load-performed-bench').setValue('12,5')
    await wrapper.get('article form').trigger('submit')
    expect(wrapper.emitted('operation')![0]![1]).toMatchObject({ load_value: '12.5' })
    await wrapper.get('#activity-set-load-performed-bench').setValue('0')
    await wrapper.get('article form').trigger('submit')
    expect(wrapper.emitted('operation')![1]![1]).toMatchObject({ load_value: '0' })
    wrapper.unmount()
  })

  it('preserves an edited set and its original revisions after a conflict refresh', async () => {
    const wrapper = mount(SessionDetail, { props })
    await wrapper
      .findAll('ol li')[1]!
      .findAll('button')
      .find((button) => button.text() === 'common.edit')!
      .trigger('click')
    await wrapper.get('#activity-set-load-performed-bench').setValue('60')
    await wrapper.setProps({
      session: {
        ...session,
        revision: 9,
        exercises: [
          {
            ...session.exercises[0]!,
            revision: 10,
            sets: session.exercises[0]!.sets.map((set) => ({ ...set, revision: 11 })),
          },
        ],
      },
    })
    expect(wrapper.get<HTMLInputElement>('#activity-set-load-performed-bench').element.value).toBe(
      '60',
    )
    await wrapper.get('article form').trigger('submit')
    const operation = wrapper.emitted('operation')![0]!
    expect(operation[0]).toBe('set.update')
    expect(operation[1]).toMatchObject({ set_id: 'working', load_value: '60', repetitions: 8 })
    expect(operation[2]).toEqual({ session: 2, 'performed-bench': 3, working: 4 })
    await wrapper.setProps({ session: { ...session, revision: 12 } })
    expect(wrapper.get<HTMLInputElement>('#activity-set-load-performed-bench').element.value).toBe(
      '60',
    )
    wrapper.unmount()
  })

  it('requires confirmation for deleting a set and includes all parent revisions', async () => {
    const wrapper = mount(SessionDetail, { props })
    const working = wrapper.findAll('ol li')[1]!
    await working
      .findAll('button')
      .find((button) => button.text() === 'common.delete')!
      .trigger('click')
    expect(wrapper.emitted('operation')).toBeUndefined()
    expect(working.get('[role="alert"]').text()).toContain('activity.confirmDeleteSet')
    await working
      .get('[role="alert"]')
      .findAll('button')
      .find((button) => button.text() === 'common.delete')!
      .trigger('click')
    expect(wrapper.emitted('operation')![0]!.slice(0, 3)).toEqual([
      'set.delete',
      { session_id: 'session', session_exercise_id: 'performed-bench', set_id: 'working' },
      { session: 2, 'performed-bench': 3, working: 4 },
    ])
    wrapper.unmount()
  })

  it('shows no mutation controls to a reader', () => {
    const wrapper = mount(SessionDetail, { props: { ...props, canWrite: false } })
    expect(wrapper.findAll('form')).toHaveLength(0)
    expect(
      wrapper.findAll('button').every((button) => button.text() === 'activity.exerciseHistory'),
    ).toBe(true)
    wrapper.unmount()
  })
})

describe('activity exercise catalog', () => {
  it('keeps definition edits and captured revision through a conflict refresh', async () => {
    const wrapper = mount(ExerciseCatalog, {
      props: { catalog: [definition], canWrite: true, busy: false },
    })
    await wrapper
      .findAll('li button')
      .find((button) => button.text() === 'common.edit')!
      .trigger('click')
    await wrapper.get('#activity-exercise-name').setValue('My bench press')
    await wrapper.setProps({ catalog: [{ ...definition, name: 'Another edit', revision: 6 }] })
    expect(wrapper.get<HTMLInputElement>('#activity-exercise-name').element.value).toBe(
      'My bench press',
    )
    await wrapper.get('form').trigger('submit')
    const operation = wrapper.emitted('operation')![0]!
    expect(operation.slice(0, 3)).toEqual([
      'exercise.update',
      {
        exercise_id: 'bench',
        name: 'My bench press',
        equipment: 'Barbell',
        variant: '',
        load_basis: 'total',
      },
      { bench: 5 },
    ])
    await wrapper.setProps({ catalog: [{ ...definition, revision: 7 }] })
    expect(wrapper.find('form').exists()).toBe(true)
    ;(operation[3] as () => void)()
    await nextTick()
    expect(wrapper.find('form').exists()).toBe(false)
    wrapper.unmount()
  })

  it('creates a personal exercise with per-implement load basis', async () => {
    const wrapper = mount(ExerciseCatalog, { props: { catalog: [], canWrite: true, busy: false } })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'activity.newExercise')!
      .trigger('click')
    await wrapper.get('#activity-exercise-name').setValue('Dumbbell row')
    await wrapper.get('#activity-exercise-load-basis').setValue('per_implement')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('operation')![0]!.slice(0, 3)).toEqual([
      'exercise.add',
      { name: 'Dumbbell row', equipment: '', variant: '', load_basis: 'per_implement' },
      {},
    ])
    wrapper.unmount()
  })
})
