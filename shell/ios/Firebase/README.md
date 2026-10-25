# Публичная конфигурация Firebase для Bro iOS

`GoogleService-Info.plist` получен через официальный Firebase MCP для приложения
`Bro iOS` в проекте `chromvoid-bro-push`:

- Bundle ID: `com.chromvoid.assistant`.
- Firebase App ID: `1:412629376411:ios:d1a41671841c411c98153e`.
- Google Analytics выключен; Analytics SDK в приложении отсутствует.

Это публикуемая клиентская конфигурация. Её API key идентифицирует приложение и
проект, не даёт серверного права отправки FCM. Google автоматически ограничил
отдельный iOS key списком Firebase API, включая регистрации FCM и Installations;
сервер использует другой service account с `cloudmessaging.messages.create`.

APNs `.p8`, JSON key service account и OAuth credentials сюда не помещаются.
Файл включается в приложение только для FCM-сборки; сборка сверяет точные Bundle ID
и project ID. APNs-сборка удаляет прежний Firebase plist из переиспользуемого output.

В ручном Forgejo workflow `ios.yml` input `push_transport=fcm` проверяет FCM-сборку
без изменения Variables. `signed_archive=false` и `testflight=false` оставляют
проверку без подписи и загрузки. На рабочей машине тесты и сборки не запускаются.

Статус шлюза, ключей и provisioning: [журнал настройки](../../../docs/firebase-push-setup.md).
