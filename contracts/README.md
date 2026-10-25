# Wire contracts

Проверяемый subset действующего API клиентов, не полная OpenAPI-схема сервера. JSON-fixtures
полностью синтетические. Python-тесты сравнивают с ними реальные ответы и конверты SDK,
нормализуя только случайные UUID (для звонков ещё время и собеседника); Vitest
(`web/src/contracts.test.ts`) проверяет, что декодеры веб-клиента принимают те же файлы.

- `POST /v1/auth/native/login` (`native-session.json`): `{login,password,device_name?}`; пароль, статус, блокировки и
  лимиты общие с cookie-входом. `POST /v1/auth/native/redeem` принимает
  `{token,password,login?,name?,timezone?,device_name?}` для invite/reset без Origin.
  Оба ответа: `{credential,user,device,session,expires_at,scopes}`; `user` — профиль и привязка
  сессии из `GET /v1/me` (scopes вынесены на верхний уровень), `device:{id,kind:"android",name}`, `session:{id,transport:"native"}`,
  `expires_at` — ISO timestamp, `scopes` — текущие права человека. Credential случайный,
  возвращается при выдаче, на сервере хранится hash в `core.web_sessions`; refresh-токена нет.
  При использовании срок native-сессии скользит до `now + session_days` (по умолчанию 30 дней),
  запись expiry обновляется не чаще раза в сутки; актуальное значение возвращает `GET /v1/me`.
  После 30 дней без использования сессия истекает. Cookie expiry не меняется.
  `POST /v1/auth/native/logout` с этим credential отзывает именно его сессию.
- Native person credential передаётся только в `Authorization: Bearer` у семейного HTTP,
  SSE и рукопожатия WebSocket; в query и cookie не принимается. Scopes и владельца сервер
  проверяет заново; несовпадение `X-Expected-User` возвращает 403 `account_changed`.
  Отозванная/истёкшая сессия — 401; открытые потоки замечают отзыв при проверке раз в секунду
  и закрываются, после чего данные не передаются. Управление администратора,
  Accounts, Workers и developer-поверхности недоступны native transport даже с правами
  администратора. Для cookie-клиентов требования Origin/CSRF сохраняются; ниже они относятся
  к cookie transport. У collectors собственный узкий credential, связанный с cookie/native
  родителем; у статуса удаления аккаунта — отдельный receipt credential.
- `GET /v1/conversation/submissions/by-key/{key}` (`submission.json`): личные `{message_id,state}`,
  scope `session.control`; видимость и действующая сессия проверяются. 404 означает «пока не
  найдено», GET не исполняет job.
- `GET /v1/conversation/messages` (`chat-history.json`): страница истории `{messages,before}`
  с состояниями обработки, уточнения и ошибки; фото — `{id,width,height,url,preview_url}`.
- `GET /v1/nutrition/operations/by-key/{key}`: исходная Ledger receipt только своего пользователя
  и nutrition kinds. Старый lookup по operation ID сохраняется.
- `POST /v1/nutrition/estimate` (`portion-estimate.json`): scope `nutrition.read`, Origin/CSRF
  обязательны. 1–10 видимых snapshot IDs; `{items,totals,recorded:false}`; decimal strings и null
  неизвестных нутриентов. Записи и операции не создаются.
- `GET /v1/nutrition/memory?date=YYYY-MM-DD`: `{foods:[...]}`, личные favorite/history signals.
  Дата по умолчанию вычисляется сервером в часовом поясе профиля. `food_id` не является `snapshot_id`.
- `POST /v1/timers/operations`: только `timer.start`/`timer.cancel`; `Idempotency-Key`,
  Origin/CSRF. Отмена требует revision каждого уникального timer ID. 200 receipt и для первого
  вызова, и для replay. `timer.fire` недоступен извне.
- `GET /v1/timers/operations/by-key/{key}`: собственная start/cancel receipt, scope `timers.read`.

Клиентский API плагинов (`/v1/plugins`, [trading-plugin.md](../docs/trading-plugin.md)), fixtures `plugin-*.json`:

- `GET /v1/plugins` → `plugin-catalog.json` (`assistant.catalog/v1`): только установки и инструменты,
  разрешённые пользователю; `ui[]` с `client_api` и `requires_tools`.
- `POST …/installations/{iid}/invoke`: 200 terminal `assistant.result/v1` (`plugin-result.json`),
  202 admission (`plugin-accepted.json`). `Idempotency-Key` обязателен для записи; чтение
  отправляется без ключа и не журналируется.
- `GET …/operations/by-key/{key}`: 200 `assistant.lookup/v1` (`plugin-lookup.json`), 404 с
  `dispatched` (`plugin-lookup-not-found.json`) — «пока не найдено», не разрешение повторить.
- Отказ до admission: `assistant.rejected/v1` (`plugin-rejected.json`); `field_errors` без значений аргументов.
- `GET …/events` (SSE): конверт события в `data` — `plugin-event.json`; служебные
  `stream.ready`/`resync_required` несут только `{cursor}`.
- `GET/PUT/DELETE …/configuration`: представление `plugin-configuration.json`; значения секретов
  не возвращаются, только `present`/`hint`.

DTO экранов плагина проверяются по `plugins/<id>/package/examples/**` тестами его модуля
(`plugins/<id>/web`).

Повтор ключа с другим телом — 409. Истёкшая сессия — 401; потерянные права — 403; неверные
поля — 422. Клиент сохраняет ключ и неизменяемые байты тела до POST. Восстановление выполняет
lookup; повтор POST — только явное действие и с прежним ключом/телом. HTTP 202 сообщения —
admission, не подтверждение еды.

Тесты: `tests/integration/test_client_contracts.py`, `tests/integration/test_native_identity.py`,
`tests/test_native_policy.py`, `tests/test_plugin_platform.py`,
`web/src/contracts.test.ts`. Fixtures не содержат паролей, cookie, provider credentials или
данных пользователя.

Звонки ([calls-spec.md](../docs/calls-spec.md)):

- `GET /v1/calls/current` (`call-current.json`): единственный открытый звонок `{call}` или
  `{call:null}`; права `calls.read` и `calls.write`. Звонящий видит `role:"caller"`, тот, кому
  звонят, — `role:"callee"`; `peer` — собеседник.
- WebSocket `/v1/calls/{id}/signal` (`call-signal.json`): кадры клиента `offer`/`answer`/`ping` и
  сервера `state`/`peer`/`offer`/`answer`/`pong`/`error`. SDP синтетические (адреса 192.0.2.x) и
  пересылаются без изменений.

Тесты: `tests/test_calls.py`, `tests/integration/test_calls.py` (сравнивает `GET current`,
нормализуя id, время и собеседника), `web/src/contracts.test.ts`.

Нативная фоновая запись: `ambient-vad.json` — общий контракт локального отбора речи
iOS/Android. Синтетические оценки VAD и исходные номера сэмплов проверяют края фраз,
паузы, длинные фрагменты и Stop; это не HTTP DTO и не замена проверке самой модели.
`ambient-vad-audio.json` содержит синтетический TTS-сигнал mono PCM16LE / 16 кГц
с текстом, происхождением и SHA-256 для проверки настоящей модели на обеих платформах.
