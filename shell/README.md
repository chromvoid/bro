# Мобильные клиенты и выпуск

Основной Android — `dev.assistant.shell` из `mobile/`: KMP `core`/`plugin-api`,
`design`/`androidPlatform` и Jetpack Compose `androidApp`. Все семейные разделы нативные;
Android WebView-оболочка и её JavaScript-мост удалены из собираемого APK этапом 26.
Браузерный клиент `web/` сохраняется; iOS остаётся Swift/WKWebView в `shell/ios`.
Общие `shell/ring`, `shell/tools` и `shell/licenses` сохраняют Rust-ядро, упаковку VAD и лицензии.

## Android

Origin установки задаётся при сборке (`assistantOrigin`, для debug также `assistantDevOrigin`).
Release разрешает HTTPS, debug — также loopback. Вход, invite/reset и все семейные экраны
работают в Compose; административный интерфейс открывается в Custom Tabs без native credential.
Сессии, права, journal/recovery, foreground и фоновые сборщики принадлежат нативному клиенту.
Веб обновляется серверным деплоем; Android UI требует нового APK.

### Боевой APK и обновление на телефонах

`.forgejo/workflows/android-release.yml` на Mac mini собирает единственный основной пакет
из `mobile/`. На PR он сохраняет подписанный артефакт без публикации. После слияния владельцем
push в `main` публикует APK через обычный ограниченный deployment account; ручной запуск
публикует только из `main` и только при явном `publish=true`.

Подпись прежняя: repo secrets `ANDROID_SIGNING_KEYSTORE`, `ANDROID_SIGNING_STORE_PASSWORD`,
`ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_KEY_PASSWORD`. Ключ не меняется и не копируется
между установками; на раннере временный файл `0600`, пароли передаются окружением.
Публичный pin перенесён в `mobile/release-certificate.sha256`.
versionCode — минуты с 2026-01-01 по времени коммита; release CI проверяет рост относительно
последнего production-выпуска, publisher повторяет проверку относительно опубликованного APK.

Артефакт `android-release-<номер>` содержит основной подписанный `bro-<коммит>.apk`, `.sha256`
и `release.json` с размером, origin, коммитом, версией и SHA-256 сертификата.
Проверяются package, подпись, hash, origin, 16 KiB ELF/zipalign, отсутствие Android WebView в
исходниках и DEX. Подписанный upgrade-тест собирает старую оболочку из зафиксированного
предыдущего коммита только в CI, заполняет синтетические хранилища и устанавливает новый APK
поверх неё с тем же UID. Исходники прежней оболочки в текущем дереве не хранятся.
После `NativeUpgradeSmokeTest` на том же подписанном R8-release APK выполняются четыре
теста `NativeReleaseJniSmokeTest`: Filament/gltfio, WebRTC, ONNX и Ring; отчёты — `upgrade/reports/release-jni`.

Publisher принимает только новый основной пакет после успешной выкладки коммита или его
потомка (проверяет ancestry и `deploy.json`, удерживает `deploy.lock`). Manifest и три последних
APK лежат в `$ASSISTANT_DATA/state/shell/android/dev.assistant.shell`.
Каталог временного кандидата остаётся нечитаемым хвостом: API и publisher его не выбирают,
production-данные при подготовке PR не удаляются.

Единственные маршруты — `GET /v1/shell/android` и `GET /v1/shell/android/<code>.apk`.
Оба доступны без входа и проверки transport; параметра выбора package нет.
Нативный updater доступен всем, кто видит настройки, без сессии и `session.control`.
Проверка и скачивание всегда идут на `assistant.origin` из meta-data сборки, независимо от
выбранного сервера, self-hosted и переезда сессии. Новая сборка с другим `assistant.origin`
меняет источник обновлений. Отдельный HTTP-клиент не передаёт Bearer, `X-Expected-User` или
другие credentials, не использует cookies, redirects и retry; redirect и сетевой сбой дают ошибку.
Updater проверяет размер, SHA-256, `dev.assistant.shell`, строго больший versionCode и
сертификат, закреплённый в `BuildConfig.RELEASE_CERTIFICATE_SHA256`, повторно проверяет APK
перед PackageInstaller. Отказ установки и ошибка показаны в настройках; повтор требует кнопки.
`/.well-known/assetlinks.json` связывает
`/login` с основным пакетом и тем же сертификатом. Android может не подтвердить ссылку
на закрытом Tailscale-origin; владелец включает открытие ссылок в настройках приложения.

### Обновление поверх прежней оболочки

Новый APK устанавливается поверх основного Bro с тем же сертификатом, сохраняя UID/Keystore.
Cookies WebView не импортируются: после обновления нужен нативный вход. Совместимые базы,
очереди, журналы и ключи сохраняются по прежним путям/aliases; недоказанная привязка не
даёт разрешения отправлять их новому аккаунту. Перед возобновлением проверяются установка,
пользователь, источник и прежний parent-bound grant. Неопределённая работа сохраняется;
явное закрытие старой очереди и нативный enrollment заменяют скрытый перенос credentials.

У ring/WHOOP миграция SQLite v1→v2 добавляет зашифрованный архив `closed_*` и receipt
`closed_without_transfer`: явный выбор BLE-устройства закрывает прежнюю SQL-привязку до
нового native enrollment, сохраняя физические command/workout journals и Keystore.
Ambient сохраняет `ambient-audio`; новый login/device не отправляет и не стирает старую очередь,
а Start заблокирован до её явного разбора и подтверждённой очистки. Старый `location-v1.sqlite`
остаётся читаемым с прежним alias, его grant не принимается новым сборщиком; старые системные
регистрации/работа остановлены. Notification session/installed prefs сохранены, alarms отменены,
нужен новый native opt-in. Первый запуск объясняет закрытие и повторную привязку.

Временный кандидат автоматически не обновляется между package IDs. До его удаления владелец
закрывает его очереди и переносит черновики осознанно, затем открывает обновлённый основной Bro.
Общая подпись не объединяет UID, базы или ключи двух приложений.
Полный порядок и список приёмки — [§6 плана](../docs/android-native-plan.md#6-пакеты-подпись-и-переключение).
Недоступный телефон или неизвестный исход блокирует приёмку; очистка данных не заменяет её.

### Кольцо и native CI

HerzRing использует общий Rust `shell/ring`, JNI и Kotlin GATT в `mobile/androidPlatform`.
Основной APK содержит `arm64-v8a`; библиотеки собираются из исходников, в Git не хранятся.
Kotlin/AGP/SDK/NDK и зависимости закреплены в `mobile/gradle/libs.versions.toml`, lockfiles и
`mobile/gradle/verification-metadata.xml`. Новые зависимости разрешаются только на CI:
`try.yml`, `mobile_dependencies=refresh`, затем файлы артефакта проверяются и коммитятся.

Все проверки идут на CI. `mobile_core` выполняет KMP JVM/contract tests;
`mobile_android` на единственном Android-раннере Mac mini выполняет Rust/JNI/store units,
сборку, lint и полный нативный instrumentation/Compose smoke, 125%, IME, 16 KiB и обновление
старой synthetic установки. Нулевое число тестов/ошибка загрузки эмулятора — красный результат.
Приёмку физических телефонов, BLE-устройств и публикацию выполняет владелец после ревью.

### Сплеш

Android, iOS и браузер используют знак B⋮ и улыбку из `web/src/assets/bro/moods.json`,
цвета — из `web/src/design/tokens.css`. `web/tooling/generate_palette.py` выпускает ресурсы
`mobile/`, iOS assets и веб-сплеш; CI держит их равными. Основная метка — Bro, иконка —
основной знак, без оформления кандидата. Темы system/light/dark и reduced motion нативные.

## iOS

iOS — универсальная Swift/WKWebView-оболочка. Пользователь вводит адрес установки и
подтверждает подключение. Мост `AssistantShell` в собираемых приложениях остаётся только для неё: JSON-запросы
`{id,type,payload}`, ответ `{id,ok,result}` или `{id,ok:false,error}`. Доступ имеет только
главный фрейм своего origin через `WKScriptMessageHandlerWithReply`; проверяются frameInfo,
размер и тип. Чужие ссылки открывает `SFSafariViewController`.

Сохраняются `shell.capabilities`, `screen.wake/release`, `theme.set`, `ambient.*`,
`location.*`, `notifications.*`, `connection.open`; Android-only команды удалены из веба,
кроме описанного выше временного пути обновления установленной старой оболочки.
Вход остаётся cookie WKWebsiteDataStore. Ambient стартует только по явному действию;
сервер/пользователь/устройство перепроверяются нативно. Фоновая location queue —
`Application Support/location/queue.json`, credential — Keychain AfterFirstUnlockThisDeviceOnly.
Notification receiver key/auth secret — shared Keychain, метаданные — App Group;
extension проверяет binding, пользователя и expiry перед расшифровкой.

Тесты и unsigned release iOS идут через `ios.yml`; TestFlight/Apple provisioning — отдельные
ручные операции владельца. Android-замена не меняет iOS bridge protocol.

Debug предлагает `http://127.0.0.1:8766`: симулятор видит loopback Mac, поэтому `npm run dev`
доступен без туннеля. Исключение ATS для loopback есть только в debug: `Info.plist`
препроцессируется, ключ стоит под `ASSISTANT_DEBUG`. Release принимает только HTTPS с системной
проверкой TLS. Частный или Tailscale адрес требует доступной сети/VPN и доверенного сертификата;
приложение не устанавливает VPN и не обходит проверку сертификата. `GET /v1/client/config`
проверяет API 1, shell API 1, stable `instance_id` и `/assistant`. Смена установки пересоздаёт
WebView, очищает cookie/storage, ключи и доставки; native настройки доступны также без связи.
`ASSISTANT_PUBLISHER_SUPPORT_URL` задаёт публичный HTTPS-контакт издателя в сборке. Ссылка
«Поддержка издателя приложения» доступна на экране подключения до входа, при сетевой ошибке
и в управлении активным сервером. `client.support_url` выбранного сервера подписан отдельно
как контакт администратора установки. Unsigned/debug сборка допускает пустой контакт издателя;
`config`, Apple `preflight` и подписанный выпуск требуют действующий HTTPS URL без credentials.
Внешних зависимостей нет: SwiftUI, WebKit, SafariServices, CryptoKit, DeviceCheck. Экспорт сохраняется во временную папку
и открывается системным «Поделиться».

Push идёт через фиксированный `ASSISTANT_PUSH_RELAY` сборки (HTTPS origin). Пустое значение или
неподдерживаемый App Attest оставляет локальные напоминания, push capability становится `null`.
Relay проверяет App Attest, регистрирует APNs token и выдаёт send/management capabilities;
странице передаются только send capability и receiver public key/auth secret. `.p8` издателя
никогда не передаётся self-hosted серверу. Generation guard отменяет ответы после смены сессии.
Отвязка на relay — best effort; локальные ключи и расписание удаляются сразу.

Подпись использует `ASSISTANT_BUNDLE_ID` (extension — `<bundle>.notifications`),
`ASSISTANT_APP_GROUP` (`group.<bundle>`), `ASSISTANT_KEYCHAIN_GROUP`
(`$(AppIdentifierPrefix)<bundle>.shared`). Для обоих targets нужны App Group и Keychain Sharing,
для app — Push Notifications и App Attest. Debug использует development, Release — production;
relay получает APNs environment `sandbox`/`production`. Иконка Bro — светлый знак B) на приглушённом
синем фоне. Нейтральный прозрачный мастер — `web/src/assets/bro/symbol.png`, векторный
исходник и происхождение лежат рядом. Вариант `symbol-small.png` с более толстой улыбкой
и широким просветом используется для favicon 16 и 32 px; остальные размеры берут основной
символ. Квадратные иконки iOS и Apple touch рисуют знак на 0.84 холста (около 65 % ширины),
favicon — на весь холст.
Для воспроизводимого обновления иконок из `shell/ios`: `swift tools/generate-icon.swift`.
Генератор окрашивает альфа-канал мастера цветом `icon_foreground`, а фон берёт из
`icon_background` в `web/src/design/palette.generated.json`; палитру предварительно обновляет
`python3 web/tooling/generate_palette.py` из корня репозитория. Core Graphics создаёт
непрозрачные квадратные sRGB PNG для iOS (1024 × 1024), веб-приложения (192 и 512 px)
и Apple touch (180 px), favicon ICO с размерами 16/32/48 px и прозрачный foreground
Android (432 px, `ic_bro_foreground.png`). Генератор измеряет силуэт и при необходимости
уменьшает масштаб до безопасного круга maskable/adaptive-иконки; форму внешней маски
выбирает операционная система. Android использует тот же цветной символ и фон палитры.

`shell/ios/ci.sh test` — то же, что проверяет CI: тесты на своём симуляторе «AssistantShell CI»
(создаётся один раз, чужие симуляторы не трогает) и release-сборка для устройств без подписи.
`shell/ios/ci.sh config` проверяет публичные publisher settings без ключей и сетевых запросов.
`shell/ios/ci.sh preflight` требует ASC key и спрашивает App Store Connect, ничего не выгружая:
подходят ли друг к другу ключ, Issuer ID и `.p8`, зарегистрированы ли основное приложение и
extension с заданным App ID prefix и есть ли запись приложения. Без ключа он завершается ошибкой.
`IOS_APP_ID_PREFIX` задаётся отдельно: prefix может отличаться от Team ID.
`shell/ios/ci.sh release` собирает подписанный архив, проверяет его, экспортирует локальный IPA,
проверяет итоговую distribution-подпись и только затем выгружает IPA в App Store Connect.
Проверяются app и extension, Team/bundle/prefix, provisioning, общий App Group/Keychain,
production APNs/App Attest итогового IPA, privacy manifests и параметры подключения.
Успешный preflight не заменяет эти проверки и не подтверждает доставку APNs на устройство.

### Сборка на Mac через Forgejo

Linux-сборщик не умеет Xcode, поэтому iOS-задачи (`.forgejo/workflows/ios.yml`,
`runs-on: macos-xcode`) выполняет Mac mini со своим сборщиком Forgejo. Проверка идёт на каждый пуш
в `main`, меняющий `shell/ios/`, и соответствующий pull request; `preflight` и сборка для TestFlight — вручную: Forgejo → Actions → ios → Run workflow →
«Собрать и отправить в TestFlight». Пока Mac mini выключен, спит или вне сети, задачи ждут в
очереди. Номер сборки — числовой идентификатор CI run; ручная сборка требует нового `BUILD_NUMBER`.

Один раз:

1. **Сборщики на Mac mini.** `deploy/ci/mac-runner.sh` ставит два сборщика этого репозитория,
   каждый из Homebrew и как launch agent текущего пользователя (лог в его каталоге):
   - `xcode` — метка `macos-xcode`, задачи прямо на macOS по одной (они делят один симулятор),
     `~/.forgejo-runner`: `ios.yml` и Android lane основного `ci.yml`;
   - `docker` — метка `docker-arm64`, Linux-контейнеры arm64 в Docker Desktop по две,
     `~/.forgejo-runner-docker`: `agent-e2e.yml`, чтобы он не нагружал
     раннер `ci.yml`. Контейнер задачи получает сокет Docker Desktop и сам собирает образы.

   Для каждого: Forgejo → репозиторий → Settings → Actions → Runners → Create new runner, затем на
   Mac mini `deploy/ci/mac-runner.sh install xcode` (или `docker`) спросит UUID и токен. Для
   `xcode` нужны Xcode и node, для `docker` — Docker Desktop с включёнными Settings → Advanced →
   «Allow the default Docker socket to be used» и General → «Start Docker Desktop when you sign in».
   Launch agent и Docker Desktop живут в сеансе пользователя, поэтому Mac mini не должен засыпать
   (System Settings → Energy → «Prevent automatic sleeping…»), а пользователь — входить
   автоматически. Задачи выполняются от его имени без изоляции, лучше завести отдельного.
   `status [xcode|docker]` и `uninstall xcode|docker` — проверить и убрать. Чужой сборщик в тех же
   каталогах (например, глобальный `macmini`) скрипт не перезаписывает и не удаляет.
2. **Apple.** Apple Developer Program. В App Store Connect — приложение с идентификатором из
   `IOS_BUNDLE_ID` и ключ API (Users and Access → Integrations → App Store Connect API → Team Keys,
   роль App Manager; если Xcode скажет, что ключу не хватает прав на сертификаты, — Admin).
   Файл `.p8` скачивается один раз.
3. **Forgejo → Settings → Actions** (репозитория или пользователя). Secrets: `ASC_KEY_ID`,
   `ASC_ISSUER_ID`, `ASC_KEY_P8` (текст файла `.p8` целиком). Variables: `IOS_TEAM_ID`,
   `IOS_APP_ID_PREFIX`, `IOS_BUNDLE_ID` (идентификаторы можно вместо variables держать в secrets), `ASSISTANT_PUSH_RELAY`
   (origin relay издателя, `https://…`) и `ASSISTANT_PUBLISHER_SUPPORT_URL` (публичный
   контакт издателя, `https://…/support`). Эти два публичных URL workflow читает из variables.

Подпись автоматическая: ключ API позволяет Xcode самому создать сертификаты и профили при первой
сборке. `Info.plist` объявляет `ITSAppUsesNonExemptEncryption = NO`: системное HTTPS и стандартное
шифрование RFC 8291 через CryptoKit. Export compliance этой сборки подтверждает издатель при выпуске.
Выбор Apple team, отдельные ASC/APNs keys и инфраструктура шлюза описаны в
[порядке настройки издателя](../docs/ios-publisher-setup.md).

## Проверено

- Android, 04.10.2026: 21 unit-тест моста и G2, lint без ошибок, сборки debug/release;
  установка и проверка на Android 16 (WebView 154) против отдельного тестового
  сервера. Дневник, задачи, выбор фото, микрофон, экспорт, оформление и возврат после потери
  связи проверены; подробности и ограничения — [android-verification.md](docs/android-verification.md).
- iOS (5 октября 2026): 12 targeted unit-тестов origin, моста, конфигурации, session identity,
  Unicode-расписаний и RFC 8291 прошли на отдельном iPhone 17 Simulator (iOS 26.2).
  Ранее фиксированная оболочка проверялась с dev-сервером: вход, «Сегодня», ассистент и микрофон.
  Универсальное подключение, системные напоминания и APNs на телефоне требуют проверки владельца;
  TestFlight в этой работе не выгружался.
  После добавления контакта издателя прошли iOS 16 Swift typecheck и точечная проверка URL;
  XCTest контакта прошёл в PR CI. Иконка пересоздана генератором, проверены RGB,
  отсутствие alpha и ненулевые пиксели; выполнен визуальный просмотр.
- На реальном iPhone и очках оболочки ещё не проверялись.
- Android-звонок до тестового Chromium дошёл до ответа, но ICE-соединение не установилось.
  Разговор, mute и восстановление звонка не подтверждены; список проверок —
  [docs/calls-spec.md](../docs/calls-spec.md).
