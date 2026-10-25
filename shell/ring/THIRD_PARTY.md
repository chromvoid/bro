# Происхождение Rust-модуля

Дата: 2026-10-05.

Код `qring-r20` и ABI реализован заново по фактам формата пакетов, исследованным
в официальном HerzRing APK (Oudmon SDK) и локальном ATT capture R20. Проприетарный
Java код APK не копировался и не включён. Подробные источники и степень проверки:
`../../docs/herzring-slim-pro-x6-reverse-engineering.md`.

Независимая публичная сверка:

- [Puxtril QRing protocol documentation](https://colmi.puxtril.com/commands/) —
  схема core protocol; не используется как скопированный исходный код.
- [smart-ring-dashboard protocol.ts](https://github.com/servolok84/smart-ring-dashboard/blob/b1f4c4800d424c821bb8087866b69578d9b6750a/web/src/ble/protocol.ts) —
  MIT, copyright 2026 Smart Ring Dashboard contributors. Использован для сверки
  framing/CRC; реализация на TypeScript не портировалась. Различия R20/APK
  (sleep request FF01, температурные layouts, calendar rule) сохранены явно.
- [colmi_r02_client](https://github.com/tahnok/colmi_r02_client) — MIT,
  copyright 2024 Wesley Ellis. Сверка батареи/UUID; исходный код не копировался.

Cargo зависимости и точные registry checksums закреплены в `Cargo.lock`.
`serde`, `serde_json` — MIT OR Apache-2.0; `jni` 0.21.1 — MIT OR Apache-2.0.
Полные package license texts/attribution для закреплённого набора сохраняются
в `licenses/`. Это включает target-specific и build dependencies lockfile;
не все из них входят в Android-библиотеку. При обновлении lockfile пересмотреть
этот набор и сохранить новые лицензии. License/provenance table —
`licenses/index.md`.
