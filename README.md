# Bro

Bro — личный ассистент: чат, задачи и напоминания, дневник питания, личные показатели
и подключение совместимых устройств. Сайт: https://bro.chromvoid.com.

Это публичное зеркало исходников выпущенных клиентов Bro. Разработка и CI идут
в Forgejo; после успешного выпуска в GitHub появляется отдельный коммит и тег
`release-<source-sha>`. Поле `Source-Commit` в сообщении коммита указывает исходную
ревизию. История внутренней разработки в зеркало не переносится.

## Исходники

- `mobile/` — Kotlin Multiplatform core и нативный Android-клиент Jetpack Compose.
- `web/` — веб-клиент Vue/TypeScript; `plugins/*/web` — его подключения.
- `shell/ios/` — клиент iOS; `shell/ring/` — Rust-ядро кольца.
- `firmware/` — клиент настольного экрана ESP-IDF.
- `sdk/`, `contracts/`, `plugins/*/package/` — SDK и контракты подключений.
- `tools/android_ci.py`, `tools/android_release.py` — инструменты сборки Android.

Для работы клиента нужен аккаунт на совместимом сервере Bro. Backend, серверные
плагины и инфраструктура остаются коммерческими и в это зеркало не входят.
Android собирается из Gradle-root `mobile/`; версии инструментов и зависимостей
закреплены в проекте. Для iOS предоставьте собственный
`shell/ios/Firebase/GoogleService-Info.plist`. Ключи подписи и учётные данные не
входят в исходники; используйте собственные настройки установки.

## Лицензии

Клиенты и продуктовый интерфейс: **AGPL-3.0-only**. Самостоятельные SDK и контракты:
**MIT**. Подробная матрица — [LICENSING.md](LICENSING.md), тексты — [LICENSES/](LICENSES/).
Сторонние компоненты сохраняют свои условия: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Поддержка: support@chromvoid.com.
