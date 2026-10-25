import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import DataNotes from './DataNotes.vue'

function notes(list: Array<string | false | null | undefined>) {
  return mount(DataNotes, { props: { notes: list }, global: { plugins: [i18n] } })
}

describe('DataNotes', () => {
  it('folds the notes that apply under one «О данных» line', () => {
    const wrapper = notes(['Показаны не все замеры.', false, null, 'Линии соединяют измерения.'])
    const details = wrapper.get('details')
    expect(details.attributes('open')).toBeUndefined()
    expect(wrapper.get('summary').text()).toBe('О данных')
    expect(wrapper.findAll('li').map((item) => item.text())).toEqual([
      'Показаны не все замеры.',
      'Линии соединяют измерения.',
    ])
  })

  it('shows nothing when no note applies', () => {
    expect(notes([false, undefined]).find('details').exists()).toBe(false)
  })
})
