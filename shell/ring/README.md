# Rust-ядро QRing R20

Дата: 2026-10-18. Статус: источник Android ABI 1 для нативного приложения; проверка непустой истории
на HerzRing X6 и полноты хранения ещё требуется.

`core/` — portable safe Rust: кодек, проверка checksum/CRC, ограниченная сборка
notifications, парсеры пяти метрик и синхронная машина событий. `ffi/` — C ABI и
тонкий JNI. Здесь нет BLE, файловой системы, сети, потоков, Tokio или WebView.
Android выполняет GATT, хранение, проверку IANA timezone, планирование и доставку.

`mobile/androidPlatform` использует этот каталог через `build-android.sh` для основного
приложения `com.chromvoid.bro` (application ID с 26 октября 2026). Переход с
`dev.assistant.shell` создаёт отдельную установку без автоматического переноса локальных данных. Cargo source/lock и CI-кэш общие; исходники протокола
не копируются. JNI экспортирует только `assistant.mobile.platform.ring.NativeRing`
и `NativeRingOta` поверх ABI 1. GATT/SQLite/Keystore/collector и сессия принадлежат
нативному Android-приложению. Обновление внутри `com.chromvoid.bro` сохраняет UID и совместимые
схемы/aliases; привязка источника проверяется перед возобновлением очереди.
Rust-тесты, JNI smoke, 16 KiB ELF и zipalign выполняются на CI. Тренировки/HR/GPS/FGS,
ActivityRecognition, NotificationListener, Accessibility-жесты и OTA используют
нативный Android runtime этапа 24b.

## Проверка выбранного физического кольца на Android

`RingHardwareAcceptanceTest` — отдельный opt-in instrumentation-сценарий для
проверки настоящих `RingGatt`, JNI и парсеров без сервера и без пользовательской
очереди. Без обоих аргументов `ringAddress` и `confirmReadOnlyHistory=yes` он
пропускается. Адрес задаётся явно после выбора владельцем; автоматического
подключения к найденным устройствам нет. Перед запуском кольцо должно быть
свободно от HerzRing Soul, а тестовому APK надо выдать разрешение Bluetooth.

Имя подтверждения означает чтение истории: обычная инициализация всё равно
пишет часы (`01`) и тип Android-клиента (`04`), затем отправляет фиксированные
запросы батареи и истории. Измерения, вибрация, жесты, сброс и OTA не запускаются.
Запрос `25` может вернуть сырой ответ, но его числовая температура остаётся
неподтверждённой и не преобразуется в измерение.

После сборки и установки debug APK и его androidTest APK запуск выглядит так
(имя пакета и адрес нужно подставить для выбранной установки и кольца):

```sh
adb -s "$RING_TEST_SERIAL" shell am instrument -w -r \
  -e class assistant.mobile.platform.ring.RingHardwareAcceptanceTest \
  -e ringAddress "$RING_TEST_ADDRESS" \
  -e confirmReadOnlyHistory yes \
  -e ringTimezone Europe/Moscow \
  "$RING_TEST_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
adb -s "$RING_TEST_SERIAL" exec-out run-as "$RING_TEST_PACKAGE" \
  cat files/ring-acceptance/summary.json
```

Один сеанс длится максимум 60 секунд, включая подключение. Повторный запуск
продолжает checkpoint того же кольца, календарного дня, timezone и окна истории;
`ringHistoryDays` необязательно задаёт окно 1..30 (по умолчанию 30). Сводка
показывает батарею, версии, завершённые/пустые/ошибочные запросы и количество
сырых и преобразованных записей **этого сеанса**. `completed=false` означает,
что весь проход ещё не закончен. Даже успешный тест сохраняет статус
`unverified_requires_soul_comparison`: пустой ответ и рабочий transport не
доказывают смысл непустых данных.

Внутри private files тестового приложения сохраняются
`ring-acceptance/offlinehardware.jsonl` (append-only TX/RX, effects и кандидаты),
`summary.json` и `checkpoint.json`. Это персональные диагностические данные,
они не выводятся в Logcat, не отправляются по сети и экспортируются **вне Git**.
Журнал ограничен 32 MiB. На основной установке с данными владельца запускать
нужно **только явно выбранные hardware test classes через `-e class`**.
Полный androidTest suite и `RingStoreTest` запускать нельзя: синтетические
storage-тесты явно очищают свою SQLite-очередь. Hardware-сценарии к ней не обращаются.

Отдельный `RingLegacyHeartRateAcceptanceTest` дополнительно требует аргумент
`ringLegacyHeartRate=yes`. Он проверяет только SDK-запрос core `15` для сегодня
и вчера: локальная календарная полночь кодируется как LE-u32 UTC-shaped epoch,
затем checksum. Ответы собираются по 16 байт с проверкой checksum; `FF`
завершает день, как и индекс `declared_frame_count - 1` из header `00`
(24 кадра → последний индекс 23, для любого запрошенного дня). Бюджет ответа —
8 секунд на день. Это диагностический эксперимент без Rust/session/import:
новые production-парсеры и время измерений из него автоматически не выводятся.
Приватные файлы — `legacy-heart-rate.jsonl` и
`legacy-heart-rate-summary.json` в той же директории. Для запуска нужно выбрать
эту отдельную test class и добавить `-e ringLegacyHeartRate yes` к аргументам
выше; результат остаётся `unverified_requires_soul_comparison_no_import`.

Команды проверки на CI:

```sh
cargo +1.97.1 test --locked --manifest-path shell/ring/Cargo.toml
cargo +1.97.1 clippy --locked --manifest-path shell/ring/Cargo.toml --workspace --all-targets -- -D warnings
cargo +1.97.1 fmt --all --manifest-path shell/ring/Cargo.toml --check
shell/ring/build-android.sh --ndk "$ANDROID_NDK_HOME" --output /absolute/path/to/generated/ringJni
```

Rust закреплён на `1.97.1`, зависимости — в `Cargo.lock`. Сборка Android использует
NDK 29.0.13846066, API 26, ABI `arm64-v8a`/`x86_64`; библиотека `libherzring.so`
линкуется с `max-page-size=16384` и `common-page-size=16384`. Скрипт не устанавливает приложение.

## Протокол и границы

Core frame содержит opcode, 14 байт payload и сумму первых 15 байт modulo 256.
Исходный opcode сохраняется; высокий бит не объявляется универсальной ошибкой.
Core write требует ATT response. Bulk write выполняется без ATT response.
Bulk: `BC action lengthLE16 crcLE16 payload`, CRC-16/MODBUS только payload.

UUID находятся в `core/src/lib.rs`; реальные ATT handles нужно обнаруживать.
Assembler принимает разрезанные заголовки/payload, несколько frames в одном
notification и остаток следующего frame. Ошибка заголовка, длины или CRC сбрасывает
сборку; следующие байты не интерпретируются как продолжение повреждённого frame.

Локальные ограничения, которые не являются пределами firmware: bulk payload
16 KiB, вход notification 32 KiB, JSON input 512 KiB, JSON output 8 MiB,
64 открытых sessions и 64 C buffers с общим лимитом 16 MiB. Wire length LE16
может выражать больше данных: такие frames завершаются явной ошибкой лимита.

SDK разрешает step day offsets 0..29. Профиль запрашивает 30 таких дней и
интервальные HR/SpO₂/temperature day offsets 0..6, которые наблюдались у
официального приложения. Это окно чтения, **не доказанная глубина хранения R20**.
`history_days` ограничен 1..30; intervals ограничены первыми семью днями профиля.
Сначала читаются все метрики дня 0, затем более старые дни, чтобы длинная
step-история не отодвигала остальные данные.

## JNI ABI 1

Kotlin object: `assistant.mobile.platform.ring.NativeRing`, `System.loadLibrary("herzring")`:

```kotlin
external fun apiVersion(): Int
external fun create(configJson: ByteArray): Long
external fun dispatch(handle: Long, inputJson: ByteArray, nowMs: Long): ByteArray
external fun destroy(handle: Long)
```

JSON всегда UTF-8. `nowMs` — monotonic elapsed time Android, а не Unix timestamp.
Новый GATT connection получает новое `generation` и новую session. Callback от
закрытого GATT отбрасывается Android до JNI; core дополнительно сверяет generation.
ByteArray копируется, ссылки на Java memory не сохраняются.

Config строит Android из доверенных нативных значений:

```json
{
  "generation": 1,
  "timezone": "Europe/Moscow",
  "local_date": "2026-10-05",
  "clock": {"year":2026,"month":10,"day":5,"hour":12,"minute":30,"second":0,"language":10},
  "android": {"sdk":36,"model":"Synthetic"},
  "history_days": 30,
  "completed_queries": [],
  "collect_running_steps": false
}
```

Clock проверяется как Gregorian date 2000..2099 и должен совпадать с `local_date`.
Ядро генерирует BCD `01` и SDK-shaped `04` из typed config; generic raw command
input отсутствует. `04` обозначает Android client `[2, coarseOS, model]`, а не
Bluetooth bond. Model занимает до 12 UTF-8 bytes без разрезанного символа:
в SDK 13-й model byte попадает на checksum и перезаписывается.

Входы: `connected`, `disconnected`, `sync`, `notification` (`channel`,
`bytes_hex`), `write_complete` (`success`), `version` (`kind`, `value`),
`deadline`; каждый содержит `generation`. `version.kind` — firmware/hardware,
строка из GATT Device Information без NUL. Отсутствующая characteristic передаётся
как пустая строка; это диагностируется, но не блокирует доступную историю.

Выход — JSON array effects:

- `read_version {kind}` — firmware/hardware; Android сериализует GATT reads.
- `write {channel,bytes_hex,response,query_id}` — единственный текущий запрос.
- `deadline {at_ms,generation}` — Android вызывает `deadline` после этого времени.
- `battery {percent,charging}` и `version {kind,value}`.
- `observation {observation}` — типизированный candidate, не доказанная метрика.
- `progress {query_id,metric,day,page,status,validation_status,checkpoint}`.
- `diagnostic {code,message}` — восстановимая ситуация; Android продолжает effects.
- `error {code,message}` — фатальная ошибка текущей session/initialization.
- `completed {status,failed_queries}` — закончен полный проход; status complete/partial.

Порядок: versions → clock `01` → client bind `04` → battery `03` → history.
GATT write completion и ответ кольца — разные события; для перехода нужны оба.
Ответу отведено 8 секунд, максимум два повтора. После ошибки истории запрос
помечается failed и чтение следующих метрик продолжается. Ошибка initialization
останавливает историю. Таймер и partial assembler сбрасываются при disconnect.

`collect_running_steps` включается Android только после согласования поддержки
`activity_reports` с сервером; отсутствие поля означает `false`. Тогда после
успешной записи часов, client bind и батареи добавляется core `48`, один раз за
session. Его ответ — отдельный `running_steps` / `running_snapshot`, а не ещё
одна запись обычных шагов. Снимок не заменяет detailed history `43`, не создаёт
тренировку и не суммируется со steps. Ноль остаётся измеренным нулём. Сохраняются
BE-u24 total/running/calories/distance и BE-u16 duration minutes; единицы calories
и distance не объявляются подтверждёнными. Статус остаётся `source_only`.

У ответа `48` нет даты. В его `notification` Android передаёт `running_clock`:
`request_local_date`, `response_local_date`, `timezone`, `request_wall_time_ms`,
`response_wall_time_ms`, `request_monotonic_ms`, `response_monotonic_ms` и
`clock_unchanged`. Эти значения снимаются при фактической GATT-записи и callback,
с проверкой даты через платформенный `ZoneId`. Ядро требует совпадения дат с
днём session, timezone, успешной clock initialization, монотонного порядка и
согласованности wall/monotonic elapsed в пределах 2 секунд. Доказательство
сохраняется в `details.clock_proof`; момент выборки не подменяется началом дня.
Снимок выходит только после обоих валидного ответа и успешного ATT completion.

`48` имеет бюджет 8 секунд **без повторной отправки**: ошибка времени, timeout или
write failure не блокирует остальные метрики. Его успехи/ошибки не сохраняются
как исторический checkpoint: новая session читает свежий снимок. Переход суток
и смена часов отвергают снимок; следующее подключение повторяет чтение с новой
датой. Исторический aggregate `07` и тренировки `13` не запрашиваются: непустой
ответ и пригодность их wire-контракта на данной прошивке пока не подтверждены.

## Checkpoint и повторные циклы

Checkpoint содержит `completed_queries`: завершённые IDs, terminal markers
`metric:day:done`, cumulative sample markers `metric:day:samples:N`,
interval markers `metric:day:interval:N` и
`failed:query_id`. Последний не позволяет continuation снова застрять на раннем
неотвечающем запросе. Он не означает успех.

Android сохраняет checkpoint атомарно вместе с local date, timezone и владельцем
кольца. При смене календарного дня, timezone или привязки владельца старый
checkpoint использовать нельзя. При ограничении времени worker создаёт новую
session с checkpoint, перечитывает initialization и продолжает оставшиеся запросы.
`completed` и complete, и partial означает конец текущего прохода: checkpoint
сбрасывается перед следующим плановым циклом, чтобы получить обновления дня и
повторить ранее failed запросы. Partial завершение отличается от budget interruption.

Страницы intervals датируются по **числу реальных предыдущих samples**, включая
позиции missing. Не используется `page * длина текущей страницы`. Счётчик
фиксируется после write completion и валидного ответа; повтор не сдвигает время.
Если старый checkpoint не содержит счётчика и interval, день перечитывается с
page 0. Изменение interval между страницами отвергается.

## Кандидаты истории и статус доказательства

| Метрика | Парсер | Что сохраняется |
|---|---|---|
| steps | core `43` | BCD calendar, time index, LE steps/calories/distance |
| running_steps | opt-in core `48` | отдельный снимок дня, BE aggregate, wall/monotonic proof; без интервала тренировки |
| sleep | bulk `27`, `3E` | локальные start/end dates, минуты, raw stage types/durations |
| heart_rate | bulk `75`, fallback core `15` | unsigned samples, interval/day/page или SDK-derived position; 0 остаётся missing |
| spo2 | bulk `5F`, `2A` | interval samples; `2A` сохраняет hourly min/max, не выдумывает average |
| temperature | bulk `74`, `25` | `74` LE-u16/100 °C; `25` raw + альтернативные layout hypotheses |

Автоматический сценарий не включает HRV, stress и команды начала/остановки
ручного измерения. Detailed steps `43` остаётся основным источником steps;
opt-in core `48` даёт отдельную статистику running_steps. Запрос sleep `27` на захваченном R20 порождает также
supplemental nap `3E`: ядро принимает его в активном sleep-цикле, даже когда уже
начался следующий запрос, и не завершает этим ответом чужой запрос. Отдельный
write `3E` не придумывается. Partial bulk leftover сохраняется между успешными
запросами и сбрасывается при retry, новом цикле или disconnect.

Каждый candidate содержит metric, source, opcode, local_date, timezone,
minute_of_day, day_offset, sample_index, value/unit/raw_value, quality,
validation_status и typed details. Сохранены исходные local calendar fields;
UTC/DST resolution выполняет Android. Ambiguous time, отсутствующая позиция,
невозможные значения и неопределённый layout нельзя превращать в надёжный record.

Непустые samples имеют `validation_status=source_only`: они ещё требуют
сопоставления реального X6 ответа с официальным UI и правильной датой/единицей.
Наблюдавшиеся пустые формы сна и intervals имеют `empty_capture_only`.
Пустой capture доказывает framing/empty shape, а не смысл непустых samples.
Synthetic tests проверяют реализацию, но не повышают эти статусы.

`25` имеет конфликтующие SDK layouts (+20°C против +32°C/RLE). Его scalar value
и timestamp остаются null, quality `temperature_layout_unconfirmed`.
Sleep использует явно сохранённое SDK calendar rule (>18:00 — предыдущий день);
неоднозначный случай не получает выдуманного timestamp. Raw sleep stage codes
сохраняются без переименования в подтверждённые категории.

### Legacy HR `15` после пустого `75`

Дата изменения: 2026-10-06. В аппаратной проверке R20 интервальный `75` оказался
пустым, а legacy core `15` содержал данные. Поэтому после явного пустого ответа
page 0 от `75` ядро читает `15` для того же дня из существующего окна 0..6.
При непустом `75` второй формат не запрашивается: одинаковый день не импортируется
из двух форматов автоматически. Это окно профиля, не подтверждение всей retention.

Запрос `15` содержит LE-u32 календарное начало дня: локальная дата кольца
кодируется как midnight на UTC-shaped calendar. Это не настоящий UTC instant
локальной полуночи, и к этому числу не прибавляется текущий timezone offset.
SDK `ReadHeartRateReq` передаёт этот аргумент; capture обоих проверенных дней
подтвердил равенство аргумента и wire calendar stamp ответа.

Поддержан узкий подтверждённый R20 формат: 24 core frames с индексами 0..23;
payload `0` объявляет count 24, payload `1` содержит LE-u32 calendar stamp и
9 samples, payloads `2..23` — по 13 samples. Парсер ждёт все 24 кадра и сверяет
stamp с запрошенным днём. Последний индекс `count-1` завершает прошлый день;
отдельный `FF` не требуется. Неизвестные counts не превращаются в no-data.
MAIN-захват дополнительно подтвердил пустой ответ `15 ff 00…00 14` для дней без
данных: checksum корректна, все 13 payload bytes после `FF` равны нулю, header
и sample frames не предшествуют этому ответу. Только эта точная начальная форма
немедленно завершает pending `15` как `empty_capture_only` без observations и
повторов. `FF` после частичного потока или с ненулевым padding остаётся ошибкой.
Calendar echo в этой пустой форме отсутствует: день задаётся единственным typed
pending query при проверенных Native peer/generation.

Физически получается 295 sample bytes. SDK использует первые 288; семь хвостовых
байтов сохраняются как provenance count, их ненулевое содержимое диагностируется,
но не получает выдуманных временных позиций. Индексы 0..287 получают SDK-derived
пятиминутные local positions; `0` остаётся missing, синтетический zero padding
не создаётся. SDK today/endFlag имеет особую ветку на `23`; core не воспроизводит
ошибку adapter endFlag, из-за которой callback прошлого дня мог ждать timeout.
Фильтр future positions выполняет Android по timezone и текущему времени.

Assembler ограничен 24 слотами по 14 bytes: после header допускает reorder,
игнорирует идентичные duplicates, отвергает конфликтующие duplicates и неверный
calendar stamp. Неполный stream ничего не импортирует. Checksum проверяет общий
core codec; Native GATT проверяет peer и connection generation, core проверяет
generation и ожидаемый день. Retry и disconnect сбрасывают assembler.

Candidate source — `oudmon_legacy_hr`; identity зависит от local date, sample
position и source, а не от величины показания или порядка доставки. Typed details
содержат raw header range, declared count, wire calendar stamp и tail metadata.
Header range не объявляется доказанным interval: пятиминутная сетка основана на
явной SDK обработке 288 positions. Checkpoint marker `heart_rate:day:empty`
позволяет continuation восстановить незавершённый fallback; отдельный
`heart_rate_legacy:day:0` означает завершение fallback текущего прохода.

Статус всех этих непустых candidates остаётся `source_only`. Wire day/count
подтверждены, но точное сопоставление chronology с официальным Soul UI ещё не
завершено: значения/time label одной карточки не доказывают весь дневной ряд.
Нельзя исправлять timezone или sample index догадкой по такой карточке.

## C ABI и владение

`ffi/include/herzring.h` описывает ABI 1. Session handles и buffer tokens —
непрозрачные monotonically issued registry IDs, не указатели. Закрытый handle и
повторно освобождённый token отвергаются. C получает owned output buffer и
должен скопировать bytes, затем вызвать `herzring_buffer_free(token)`.

C caller обеспечивает корректные читаемые input pointers и writable aligned
output pointers. Произвольный C address нельзя доказать безопасным в ABI;
эти preconditions отделены от проверяемых opaque handles. Core запрещает unsafe.
Ожидаемые повреждённые данные возвращаются как ошибки. ABI ловит unwinding panic
и инвалидирует соответствующую session; release сохраняет panic=unwind.
Аварийное завершение процесса или allocator abort не является Rust unwinding.

Протокольная provenance и лицензии описаны в `THIRD_PARTY.md`. Ни APK, ни
персональные записи, pairing keys или реальные capture payloads сюда не включены.
