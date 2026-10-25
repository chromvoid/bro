import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { dayIn, momentLabel } from '@/core/dates'
import { ApiError } from '@/core/http'
import ru from '@/locales/ru.json'
import FoodCatalogView from './FoodCatalogView.vue'
import { stagePercent, type CatalogJob } from './foodCatalog'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  operate: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))
vi.mock('@/core/client', () => ({ api: { get: mocks.get } }))
vi.mock('@/core/operations', () => ({ useLedger: () => ({ operate: mocks.operate }) }))
vi.mock('vue-sonner', () => ({ toast: { success: mocks.success, error: mocks.error } }))
vi.mock('@/stores/session', () => ({ useSession: () => ({ me: { timezone: 'UTC' } }) }))

const active = {
  import_version_id: 'version-2',
  release: '2026-10-10',
  imported_at: '2026-10-10T12:00:00Z',
  products: 13035,
  ml: 2210,
  with_package: 9120,
  units: { declared: 5074, from_quantity_text: 5633, read_by_model: 133, undeclared: 2195 },
}
const earlier = {
  import_version_id: 'version-1',
  release: '2026-09-19',
  status: 'superseded',
  imported_at: '2026-09-19T08:00:00Z',
  products: 13105,
}
const job = (fields: Partial<CatalogJob>): CatalogJob => ({
  id: 'job-1',
  state: 'running',
  stage: 'download',
  progress: {},
  error: null,
  attempt: 1,
  import_version_id: null,
  created_at: '2026-10-10T11:00:00Z',
  finished_at: null,
  ...fields,
})
const catalog = (current: CatalogJob | null) => ({
  source: 'openfoodfacts-ru',
  active,
  releases: [earlier],
  job: current,
})

describe('food catalog', () => {
  let wrapper: VueWrapper | undefined
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.operate.mockResolvedValue({})
  })
  afterEach(() => wrapper?.unmount())

  async function open() {
    wrapper = mount(FoodCatalogView, {
      global: {
        plugins: [
          [
            VueQueryPlugin,
            { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) },
          ],
          createI18n({ legacy: false, locale: 'ru', messages: { ru } }),
        ],
        stubs: { PageHeader: true },
      },
      attachTo: document.body,
    })
    await vi.waitFor(() => expect(wrapper!.find('[data-testid="catalog-job"]').exists()).toBe(true))
    return wrapper
  }
  const button = (view: VueWrapper, name: string) =>
    view.findAll('button').find((item) => item.text() === name)
  // Thousands are grouped by a no-break space, as Intl writes them in Russian.
  const grouped = (text: string) => text.replaceAll(' ', '\u00a0')

  it('shows the active release, where its units came from, and starts a refresh', async () => {
    const failed = job({ state: 'failed', error: 'download_failed' })
    failed.finished_at = '2026-10-09T10:00:00Z'
    mocks.get.mockResolvedValue(catalog(failed))
    const view = await open()
    const release = view.get('[data-testid="catalog-active"]')
    expect(release.text()).toContain('Выпуск от 10 октября 2026')
    const rows = release
      .findAll('dl > div')
      .map((row) => [row.get('dt').text(), row.get('dd').text()])
    expect(rows).toEqual([
      ['Загружен', momentLabel('2026-10-10T12:00:00Z', 'UTC', dayIn('UTC'))],
      ['Продуктов', grouped('13 035')],
      ['В миллилитрах', grouped('2 210')],
      ['С размером упаковки', grouped('9 120')],
      ['Поля Open Food Facts', grouped('5 074')],
      ['Текст количества', grouped('5 633')],
      ['Прочитана моделью', '133'],
      ['Не указана', grouped('2 195')],
    ])
    const when = momentLabel('2026-10-09T10:00:00Z', 'UTC', dayIn('UTC'))
    expect(view.get('[data-testid="catalog-job"]').text()).toContain(
      `Не удалось обновить: выгрузка не скачалась · ${when}`,
    )
    expect(mocks.get).toHaveBeenCalledWith('/v1/nutrition/catalog', expect.anything())
    await button(view, 'Обновить из Open Food Facts')!.trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({ kind: 'catalog.refresh', payload: {} })
    expect(mocks.success).toHaveBeenCalledWith('Обновление запущено')
  })

  it('shows a running refresh with its stage and progress and cancels it', async () => {
    const progress = { bytes: 1024 ** 3, bytes_total: 4 * 1024 ** 3, lines: 120000, kept: 340 }
    mocks.get.mockResolvedValue(catalog(job({ progress })))
    const view = await open()
    const running = view.get('[data-testid="catalog-job"]')
    expect(running.text()).toContain('Скачивание выгрузки')
    expect(running.text()).toContain(
      `Скачано 1 ГБ из 4 ГБ · строк: ${grouped('120 000')} · товаров для России: 340`,
    )
    expect(running.find('[aria-label="Скачивание выгрузки"]').exists()).toBe(true)
    expect(button(view, 'Обновить из Open Food Facts')).toBeUndefined()
    await button(view, 'Отменить')!.trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'catalog.cancel',
      payload: { job_id: 'job-1' },
    })
  })

  it('names the attempt of a refresh a restart started again', async () => {
    const progress = { bytes: 0, lines: 0, kept: 0 }
    mocks.get.mockResolvedValue(catalog(job({ attempt: 2, progress })))
    const view = await open()
    expect(view.get('[data-testid="catalog-job"]').text()).toContain(
      'попытка 2 из 3 · Скачано 0 Б · строк: 0 · товаров для России: 0',
    )
  })

  it('says how many units the model did not read and why', async () => {
    const units = { from_quantity_text: 5633, to_read: 368, asked: 368, failed: 368 }
    const done = job({
      state: 'succeeded',
      stage: 'done',
      import_version_id: 'version-2',
      finished_at: '2026-10-10T14:28:25Z',
      progress: { units: { ...units, error: 'no_account_access' } },
    })
    mocks.get.mockResolvedValue(catalog(done))
    const view = await open()
    expect(view.get('[data-testid="catalog-job"]').text()).toContain(
      'Модель не прочитала единицы товаров: 368. Для вас не настроен доступ к ассистенту.',
    )
    wrapper!.unmount()
    mocks.get.mockResolvedValue(catalog({ ...done, progress: { units } }))
    const unknown = await open()
    expect(unknown.get('[data-testid="catalog-job"]').text()).toContain(
      'Модель не прочитала единицы товаров: 368. Запрос к модели не удался.',
    )
  })

  it('makes an earlier release active only after its confirmation', async () => {
    mocks.get.mockResolvedValue(catalog(null))
    const view = await open()
    const releases = view.get('[data-testid="catalog-releases"]')
    expect(releases.text()).toContain('Выпуск от 19 сентября 2026')
    expect(releases.text()).toContain(`Продуктов: ${grouped('13 105')}`)
    await releases.get('button').trigger('click')
    await flushPromises()
    expect(mocks.operate).not.toHaveBeenCalled()
    const dialog = new DOMWrapper(document.querySelector('[role="dialog"]')!)
    expect(dialog.text()).toContain('Сделать активным выпуск от 19 сентября 2026')
    await dialog
      .findAll('button')
      .find((item) => item.text() === 'Сделать активным')!
      .trigger('click')
    await flushPromises()
    expect(mocks.operate).toHaveBeenCalledWith({
      kind: 'catalog.activate',
      payload: { import_version_id: 'version-1' },
    })
    expect(mocks.success).toHaveBeenCalledWith('Выпуск стал активным')
  })

  it('says why an operation was refused', async () => {
    mocks.get.mockResolvedValue(catalog(null))
    mocks.operate.mockRejectedValue(
      new ApiError(409, 'conflict', null, null, null, 'Каталог уже обновляется'),
    )
    const view = await open()
    await button(view, 'Обновить из Open Food Facts')!.trigger('click')
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledWith('Каталог уже обновляется')
  })
})

describe('stagePercent', () => {
  it('reads each stage against its own total and knows when there is none', () => {
    expect(stagePercent(job({ progress: { bytes: 50, bytes_total: 200 } }))).toBe(25)
    expect(stagePercent(job({ progress: { bytes: 50 } }))).toBeNull()
    const units = job({ stage: 'units', progress: { units: { asked: 100, to_read: 368 } } })
    expect(stagePercent(units)).toBe(27)
    expect(stagePercent(job({ stage: 'load', progress: { kept: 13035, loaded: 13035 } }))).toBe(100)
    expect(stagePercent(job({ stage: 'done' }))).toBeNull()
  })
})
