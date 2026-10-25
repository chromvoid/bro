import { ISOLATED } from './backend'
import { APP, expect, test } from './fixtures'
import { INSTALLATION, fakeTrading } from './trading'
import { openSection } from './navigation'

const NB = ' '
const screen = (name = '') => `${APP}plugins/${INSTALLATION}${name ? `/${name}` : ''}`

test('the trading module shows the portfolio and moves between its screens', async ({ page }) => {
  await fakeTrading(page)
  await page.goto(APP)
  await expect(page.getByRole('heading', { level: 1, name: 'Сегодня' })).toBeVisible()
  // The catalog adds the plugin to the navigation (the sidebar, or the profile menu on phones).
  await openSection(page, 'Торговля')
  await expect(page).toHaveURL(new RegExp(`/plugins/${INSTALLATION}$`))
  await expect(page.getByRole('heading', { level: 1, name: 'Торговля' })).toBeVisible()
  await expect(page.getByTestId('portfolio')).toContainText(`2${NB}520,5${NB}$`)
  await expect(page.getByTestId('portfolio')).toContainText(`+20,5${NB}$`)
  await expect(page.getByTestId('portfolio')).toContainText(`+1,37${NB}%`)
  await expect(page.getByTestId('trading-result')).toContainText('Итог торговли')
  await expect(page.getByTestId('balances')).toContainText('BTC')
  await expect(page.getByRole('img', { name: 'История стоимости' })).toBeVisible()
  // The caveat sits under «О данных» since the palette round of 8 October.
  await page.locator('summary', { hasText: 'О данных' }).click()
  await expect(page.getByText('стоимость восстановлена по сделкам')).toBeVisible()

  await page.getByRole('button', { name: 'Ордера', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/plugins/${INSTALLATION}/orders$`))
  await expect(page.getByTestId('open-orders')).toContainText('BTCUSDT')
  // Orders and trades share a tab.
  await page.getByRole('radio', { name: 'Сделки', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/plugins/${INSTALLATION}/trades$`))
  await expect(page.getByRole('button', { name: 'Ордера', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  )
  await expect(page.getByTestId('freshness')).toContainText('данные на')
  await page.getByRole('button', { name: 'Позиции', exact: true }).click()
  await expect(page.getByTestId('positions')).toContainText('GRAM')
  await expect(page.getByTestId('results-by-coin')).toContainText('LTC')
  // A coin opens its own page, which is an address too.
  await page.getByTestId('positions').getByRole('button', { name: /LTC/ }).click()
  await expect(page).toHaveURL(new RegExp(`/plugins/${INSTALLATION}/position\\?coin=LTC$`))
  await expect(page.getByTestId('pnl')).toContainText('Средняя цена входа')
  // The coin's price with its trades, and its realized result by month.
  await expect(page.getByRole('img', { name: 'Цена и сделки' })).toBeVisible()
  await expect(page.getByTestId('price-chart')).toContainText('вход')
  await expect(page.getByRole('img', { name: 'Реализовано по месяцам' })).toBeVisible()
  await page.getByRole('button', { name: 'Позиции', exact: true }).first().click()
  await expect(page.getByTestId('months')).toBeVisible()
  await expect(page.getByTestId('positions')).toBeVisible()
  await page.getByRole('button', { name: 'Деньги', exact: true }).click()
  await expect(page.getByTestId('cashflow')).toContainText('Депозит')
  // Screens are addresses: a reload stays where it was.
  await page.reload()
  await expect(page.getByTestId('cashflow')).toContainText('Депозит')
})

test('results are counted in BTC and BTC becomes the strategy coin', async ({ page }) => {
  const trading = await fakeTrading(page)
  await page.goto(screen('pnl'))
  const unit = page.getByTestId('unit-menu')
  await expect(unit).toHaveText('В USD')
  await unit.click()
  await page.getByRole('menuitemradio', { name: 'BTC' }).click()
  await expect(page.getByTestId('pnl-totals')).toContainText(`${NB}BTC`)
  await unit.click()
  await page.getByRole('menuitem', { name: 'Считать в BTC по умолчанию' }).click()
  await unit.click()
  await expect(page.getByRole('menuitemradio', { name: 'BTC' })).toContainText('★')
  await page.keyboard.press('Escape')
  const sent = trading.invocations.filter((call) => call.tool === 'positions.strategy')
  expect(sent.map((call) => JSON.parse(call.body).arguments)).toEqual([{ coin: 'BTC' }])
})

test('the owner chooses which purchases a sale closes', async ({ page }) => {
  const trading = await fakeTrading(page)
  await page.goto(`${screen('position')}?coin=LTC`)
  await expect(page.getByTestId('pnl')).toContainText('Итог по монете')
  await expect(page.getByTestId('pnl')).toContainText('Реализовано в текущем цикле')
  const method = page.getByTestId('method-menu')
  await expect(method).toHaveText('Закрывает последние')
  await method.click()
  await expect(page.getByRole('menuitemradio', { name: /последние покупки/ })).toHaveAttribute(
    'aria-checked',
    'true',
  )
  await page.getByRole('menuitemradio', { name: /самые старые покупки/ }).click()
  await expect(method).toHaveText('Закрывает старые')
  // The numbers change at once; the write goes out after the host journals it.
  await expect
    .poll(() =>
      trading.invocations
        .filter((call) => call.tool === 'positions.method')
        .map((call) => JSON.parse(call.body).arguments),
    )
    .toEqual([{ method: 'fifo' }])
})

test('the owner marks the core of a position', async ({ page }) => {
  const trading = await fakeTrading(page)
  await page.goto(`${screen('position')}?coin=LTC`)
  const core = page.getByTestId('core')
  await expect(core).toContainText('Основа не отмечена')
  await core.getByRole('button', { name: 'Отметить основу' }).click()
  await core.getByLabel('Сколько LTC в основе').fill('1500')
  await core.getByLabel('С какой даты').fill('2025-11-19')
  await core.getByRole('button', { name: 'Сохранить' }).click()
  await expect(core).toContainText('Основа позиции')
  await expect(core).toContainText(`1${NB}500 LTC с 19.11.2025`)
  await expect(core).toContainText('Вход основы')
  const sent = trading.invocations.filter((call) => call.tool === 'positions.core')
  const args = JSON.parse(sent[0]!.body).arguments as Record<string, string>
  expect([args.coin, args.qty, new Date(args.since!).getDate()]).toEqual(['LTC', '1500', 19])
  await core.getByRole('button', { name: 'Убрать основу' }).click()
  await expect(core).toContainText('Основа не отмечена')
})

test('a deposit is marked as money from outside at the price paid', async ({ page }) => {
  const trading = await fakeTrading(page)
  await page.goto(screen('cashflow'))
  const deposit = page.getByTestId('cashflow').getByRole('listitem').filter({ hasText: 'Депозит' })
  await deposit.getByRole('button', { name: 'Как учитывать' }).click()
  await deposit.getByRole('radio', { name: /Новые деньги/ }).check()
  // A Russian keyboard types a decimal comma; the service still gets a point.
  await deposit.getByLabel(/Цена покупки/).fill('0,99')
  await deposit.getByRole('button', { name: 'Сохранить' }).click()
  await expect(deposit.getByRole('button', { name: 'Как учитывать' })).toBeVisible()
  const sent = trading.invocations.filter((call) => call.tool === 'flows.mark')
  expect(sent).toHaveLength(1)
  expect(JSON.parse(sent[0]!.body).arguments).toEqual({
    id: 'funding:NjA0ODI4MjY4MA==',
    treatment: 'external',
    unit_cost_usd: '0.99',
  })
})

test('a refill with a lost response is checked before anything is sent again', async ({ page }) => {
  const trading = await fakeTrading(page)
  // Without events: a reconnect would look the refill up by itself before the explicit check.
  await page.route(`**/v1/plugins/installations/${INSTALLATION}/events`, (route) => route.abort())
  await page.goto(screen('sync'))
  await expect(page.getByTestId('sync-status')).toContainText('Подключено')
  trading.loseNextRefill()
  await page.getByRole('button', { name: 'Догрузить историю' }).click()
  const refill = page.getByTestId('refill')
  await expect(refill).toContainText('Исход запроса неизвестен. Сначала проверьте результат.')
  await expect(page.getByRole('button', { name: 'Догрузить историю' })).toBeDisabled()
  await refill.getByRole('button', { name: 'Проверить результат' }).click()
  await expect(refill).toContainText('История догружена.')
  const sent = trading.invocations.filter((call) => call.tool === 'sync.refill')
  expect(sent).toHaveLength(1)
  expect(sent[0]!.key).toMatch(/^[0-9a-f-]{36}$/)
})

test('the key form sends the secret once and keeps none of it', async ({ page }) => {
  const trading = await fakeTrading(page, { connected: false })
  await page.goto(screen())
  const notConnected = page.getByTestId('trading-not-connected')
  await expect(notConnected).toContainText('Ключ Bybit не подключён')
  await notConnected.getByRole('button', { name: 'Подключить ключ' }).click()
  await expect(page).toHaveURL(new RegExp(`/plugins/${INSTALLATION}/connect$`))
  await expect(page.getByText('Привяжите ключ к IP-адресу сервера: 203.0.113.10.')).toBeVisible()

  await page.getByLabel('API key').fill('short')
  await expect(
    page.getByText('Ключ и секрет состоят только из латинских букв и цифр.'),
  ).toBeVisible()
  await expect(page.getByRole('button', { name: 'Подключить', exact: true })).toBeDisabled()
  await page.getByLabel('API key').fill('SyntheticKey0001')
  await page.getByLabel('API secret').fill('SyntheticSecret000000000001')
  await page.getByRole('button', { name: 'Подключить', exact: true }).click()
  await expect(page.getByTestId('connect-notice')).toContainText('Ключ подключён')
  await expect(page.getByLabel('API key')).toHaveValue('')
  await expect(page.getByLabel('API secret')).toHaveValue('')
  await expect(page.getByTestId('connection')).toContainText('Ключ …AB12 · testnet')

  const put = trading.configurations.find((call) => call.method === 'PUT')!
  expect(put.ifMatch).toBeNull()
  expect(JSON.parse(put.body!)).toMatchObject({
    secret: { api_key: 'SyntheticKey0001', api_secret: 'SyntheticSecret000000000001' },
  })
  // Nothing of the secret stays on the device.
  const stored = await page.evaluate(async () => {
    const dump: string[] = [JSON.stringify(localStorage), JSON.stringify(sessionStorage)]
    for (const { name } of await indexedDB.databases()) {
      if (!name) continue
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open(name)
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
      })
      for (const store of Array.from(db.objectStoreNames)) {
        const all = await new Promise<unknown[]>((resolve) => {
          const request = db.transaction(store).objectStore(store).getAll()
          request.onsuccess = () => resolve(request.result)
        })
        dump.push(JSON.stringify(all))
      }
      db.close()
    }
    return dump.join('\n')
  })
  expect(stored).not.toContain('SyntheticSecret')

  // Replacing the key names the revision it replaces; disconnecting asks first.
  await page.getByRole('button', { name: 'Отключить', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Отключить и удалить данные' }).click()
  await expect(page.getByTestId('connect-notice')).toContainText('Ключ отключён')
  const deleted = trading.configurations.find((call) => call.method === 'DELETE')!
  expect(deleted.ifMatch).toBe('"1"')
})

test('a module for another client version shows an explanation instead', async ({ page }) => {
  await fakeTrading(page, { clientApi: 2 })
  await page.goto(screen())
  await expect(page.getByTestId('plugin-fallback')).toContainText(
    'Экран плагина рассчитан на другую версию приложения',
  )
  await expect(page.getByTestId('plugin-fallback')).toContainText(
    'Остальные разделы ассистента работают как обычно',
  )
})

test('the real trading installation of the development backend answers', async ({
  page,
  baseURL,
}) => {
  test.skip(ISOLATED, 'the disposable backend has no plugin installations')
  // The development backend signs the browser in on the first page.
  await page.goto(`${APP}plugins/trading-main`)
  const catalog = (await (
    await page.request.get(`${new URL(baseURL!).origin}/v1/plugins`)
  ).json()) as { installations: Array<{ installation_id: string }> }
  test.skip(
    !catalog.installations.some((item) => item.installation_id === 'trading-main'),
    'trading is not installed here (python3 tools/trading_dev.py setup && up)',
  )
  // Read-only: without a key the service says so; with one the portfolio shows.
  await expect(
    page.getByTestId('trading-not-connected').or(page.getByTestId('portfolio')),
  ).toBeVisible()
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()
  await expect(page.getByTestId('sync-status')).toBeVisible()
})
