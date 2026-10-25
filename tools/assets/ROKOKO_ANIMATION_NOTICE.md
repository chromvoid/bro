# Rokoko LookingAround — происхождение и разрешение использования

Проверено 4 октября 2026 года. Это отдельный ресурс анимации; его разрешение
не заменяет лицензии моделей MPFB и прежних анимаций Quaternius.

Автор записи: **Sam Lazarus / Rokoko**. Набор: **10 free everyday idle animations**.
Официальная [страница набора](https://www.rokoko.com/resources/rokoko-mocap-10-free-everyday-idle-animations)
указывает FBX, скелет Mixamo и 30 кадров/с. Она разрешает использовать анимации
в творческих проектах, включая коммерческие:

> “from passion project to commercial use”

Это разрешение издателя, **не CC0**. Отдельного файла лицензии в полученном
архиве нет. Страница не предоставляет отдельного разрешения перепродавать
или распространять исходный набор как самостоятельную библиотеку.
В проект включается производная анимация для персонажа приложения;
исходные FBX и весь набор не добавляются в Git.

## Проверенные исходники

| Ресурс | SHA-256 |
| --- | --- |
| [EVERYDAY-IDLES-MOCAP.zip](https://media.rokoko.com/EVERYDAY-IDLES-MOCAP.zip) | `ce239abe239a264cc6567be14a53a973fa719a759095eece0cddb7cc3f16fa37` |
| `Idle_LookingAround_MIXAMO_769.fbx` | `0c967adac22b5b2337c26ea36272b6ef089f4ea2be22e873caceeab496ead595` |

Выбрана одна запись LookingAround. Полная запись после двух начальных
калибровочных кадров — кадры 3–1014 с исходной частотой 30 Гц:
1012 отсчётов, 33,7 секунды. Для спокойной стойки выбран непрерывный участок
**150–960: 811 отсчётов, 27 секунд**. Вход и выход с перемещением стоп остаются
за пределами этого участка; калибровочные кадры 1 и 2 также исключены.
Точные границы записываются в манифест, скорость исходного исполнения сохраняется.
Исходная запись не является замкнутым циклом.
Повторение с переходом в приложении — отдельная обработка воспроизведения,
а не свойство исходного файла. Смешивание конца и начала в течение 1,2 секунды
даёт период повторения 25,8 секунды, не ускоряя оставшиеся движения.

## Производные ресурсы

`tools/assets/import_inbody_rokoko_idle.py` переносит запись отдельно на мужской
и женский скелет. Геометрия, материалы и варианты телосложения остаются в базовых
моделях. Файлы `male-lookaround.glb` и `female-lookaround.glb` содержат только
иерархию узлов и анимацию; они привязаны к точным SHA-256 соответствующих моделей.

`tools/assets/promote_inbody_rokoko_idle.py` проверяет хеши, иерархию, каналы,
длительность, частоту, конечность чисел, нормализацию вращений и отсутствие
изменения масштаба костей. Без `--write` он только проверяет ресурсы.
После визуальной приёмки `--write` добавляет два файла и отдельный
`web/src/assets/body/rokoko-lookaround.json` с происхождением и хешами.
Прежние `manifest.json` и `*-idle.glb` Quaternius не изменяются.

Численные проверки подтверждают целостность переноса, но не заменяют просмотр
движения, контакта стоп и деформации рук на обеих фигурах.

## Воспроизведение экспорта

Команды выполняются из корня репозитория. Нужны Blender 5.2 LTS, `.venv`
проекта и исходные `character.blend` обеих фигур из существующей сборки
`tools/assets/export_inbody_production.py`. Они должны соответствовать
базовым `web/src/assets/body/male.glb` и `female.glb`; проверка хешей не допускает
перенос записи на другую версию модели. Рабочие файлы остаются в игнорируемом `runtime/`.

Загрузка архива и извлечение только выбранного FBX с проверкой хешей:

```sh
mkdir -p runtime/inbody-rokoko/source
curl --fail --location --output runtime/inbody-rokoko/source/EVERYDAY-IDLES-MOCAP.zip \
  https://media.rokoko.com/EVERYDAY-IDLES-MOCAP.zip
.venv/bin/python -B - <<'PY'
from pathlib import Path
import zipfile
from tools.assets.promote_inbody_rokoko_idle import ARCHIVE_SHA256, SOURCE_FILE, SOURCE_SHA256, sha
directory = Path('runtime/inbody-rokoko/source')
archive = directory / 'EVERYDAY-IDLES-MOCAP.zip'
assert sha(archive.read_bytes()) == ARCHIVE_SHA256, 'Original archive hash changed'
with zipfile.ZipFile(archive) as contents:
    recording = contents.read('EVERYDAY-IDLES-MOCAP/' + SOURCE_FILE)
assert sha(recording) == SOURCE_SHA256, 'Original recording hash changed'
(directory / SOURCE_FILE).write_bytes(recording)
PY
rokoko_source="$PWD/runtime/inbody-rokoko/source/Idle_LookingAround_MIXAMO_769.fbx"
```

Два последовательных экспорта с одинаковым исходным временным участком:

```sh
BLENDER_USER_RESOURCES="$PWD/runtime/inbody-model/blender" \
blender -b --factory-startup --python-exit-code 1 \
  --python tools/assets/import_inbody_rokoko_idle.py -- \
  --source "$rokoko_source" \
  --base web/src/assets/body/male.glb \
  --blend runtime/inbody-character/review/character.blend \
  --output runtime/inbody-rokoko/male --start-frame 150 --end-frame 960 --render

BLENDER_USER_RESOURCES="$PWD/runtime/inbody-model/blender" \
blender -b --factory-startup --python-exit-code 1 \
  --python tools/assets/import_inbody_rokoko_idle.py -- \
  --source "$rokoko_source" \
  --base web/src/assets/body/female.glb \
  --blend runtime/inbody-character/female/review/character.blend \
  --output runtime/inbody-rokoko/female --start-frame 150 --end-frame 960 --render

.venv/bin/python -B tools/assets/promote_inbody_rokoko_idle.py \
  --source-fbx "$rokoko_source" \
  --source-archive runtime/inbody-rokoko/source/EVERYDAY-IDLES-MOCAP.zip
```

После просмотра обеих фигур в движении и проверки перехода между повторами
добавить проверенные ресурсы, затем проверить именно опубликованные файлы:

```sh
.venv/bin/python -B tools/assets/promote_inbody_rokoko_idle.py \
  --source-fbx "$rokoko_source" \
  --source-archive runtime/inbody-rokoko/source/EVERYDAY-IDLES-MOCAP.zip --write
.venv/bin/python -B -m unittest tests.test_rokoko_assets -v
```
