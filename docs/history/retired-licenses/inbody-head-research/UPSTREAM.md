# Источники остановленного исследования головы

10 октября 2026. Статус: исследование остановлено; эти головы и их производные
не входят в ресурсы приложения. Владелец решил сохранить исходного спартанца
CI 2092. Запись сохраняет происхождение и атрибуцию удалённых из рабочего кода
экспериментальных экспортёров, просмотрщиков и CI-сценариев.

## Einar

`Einar Rig (CC-BY) Blender Foundation | studio.blender.org`.
Издатель — Blender Foundation / Blender Studio. Источник:
[Einar v1](https://studio.blender.org/characters/einar/v1/),
лицензия [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Автор [Einar hair groom](https://studio.blender.org/projects/charge/3a67348bcb277c/?asset=6072)
— Andy Goralczyk, Blender Studio; опубликовано 7 июля 2022.

Точные исходники, использовавшиеся в исследовательском CI, хранятся вне Git:

| Исходник | Байты | SHA-256 |
| --- | ---: | --- |
| [Hair groom, BLEND](https://studio.blender.org/download-source/files/5e/5e211120afa7360a1db47c02f3726870/5e211120afa7360a1db47c02f3726870.blend) | 349372195 | `f3211d9e1f738705c12ae940acb18358473c15caa1f5ff6cd340d8b51f0effed` |
| [Real-time demo, BLEND](https://studio.blender.org/download-source/files/2f/2fbb24408728d2ccc124595c8d404f51/2fbb24408728d2ccc124595c8d404f51.blend) | 346845916 | `73a002b36ee140794d61c4ab9d23f9535b82b63c3af22a32bf0e97585faa22e8` |
| [Original character, ZIP](https://studio.blender.org/download-source/files/41/419e83fb75f30b989adf7920dec7a21c/419e83fb75f30b989adf7920dec7a21c.zip) | 579834421 | `3cbb5a1dd9ffd3ca39edab420a83ea7f7db337b435a78ef27a0c87ff3e6977f7` |
| `einar_release_v1.blend` внутри ZIP | 91323760 | `1c6663da0a2e9d6822978467c244e9186edc76083bae2414e02f9e695e711f7b` |

Изменения проекта в кандидатах: выделение головы и глаз, уменьшение сетки,
запекание рельефа, перенос материалов для glTF, исследование исходного groom,
hair cards и производных атласов. Это производные работы с сохранением
атрибуции, а не собственная исходная скульптура проекта. Проверенный отдельный
прототип анатомии — [CI 2126](https://kaifaty.ru/git/kaifaty/device-assistant/actions/runs/2126).
Он не был принят как итоговое лицо спартанца.

## Скульптура eon

[kratos from god of war (free download)](https://sketchfab.com/3d-models/kratos-from-god-of-war-free-download-19bff2ed58a141adb4656434e848704b)
— автор [eon](https://sketchfab.com/facerate), указанная исходником лицензия
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Исходная скульптура не является работой проекта.

Исходный ZIP: 48270948 байт, SHA-256
`5a1a43342dd05003cc820b082e6b2870680196517cdbdd61c52b4b37aeb0a53c`.
Преобразованный исходный GLB: 38840436 байт, SHA-256
`2c37f123644c0a716018be339464a7eb872d3c16351e81330edbdadc04af719c`.
Исходники остаются вне Git. В исследовании выделены глаза `Object_4`, голова и
шея `Object_16`, борода и усы `Object_22`. Проект подготовил производную
упрощённую сетку, UV, tangent normal maps и карту глаз; итоговая интеграция не
была принята. Последняя попытка —
[CI 2430](https://kaifaty.ru/git/kaifaty/device-assistant/actions/runs/2430),
завершившаяся ошибкой браузерной проверки.

Полные исходники исследовательских инструментов, receipts, концепты и кадры
сохранены в рабочем архиве `.local/tasks/20261009-inbody-spartan/` основного
checkout. При повторном использовании производных нужно сохранять указанную
атрибуцию, ссылку на лицензию и перечень фактических изменений.
