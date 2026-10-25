# Инструкция калипера по трём точкам

Дата: 5 октября 2026. Статус: ресурс веб-клиента; выкладка отдельно.

`male-three-site.png` — оригинальная иллюстрация мужского протокола Jackson–Pollock,
сгенерированная встроенным инструментом imagegen. Сторона тела и направления складок
проверены отдельно после генерации. При выбранном женском протоколе изображение скрыто.
Текстовые описания всех точек остаются доступными без картинки.

Рисунок схематический: 2 см справа от пупка нужно отмерить линейкой на своём теле.
Середину передней поверхности бедра определяют от паховой складки до верхнего края
коленной чашечки, а не от края шорт. Красная линия обозначает продольное направление
складки; губки калипера ставятся поперёк неё.

Ресурс: PNG, 1024×1536, непрозрачный фон. Исходный сгенерированный файл:
`exec-7a659c69-c3e2-4492-bfdd-88470867afb6.png`.
SHA-256: `44286ea7f8049e14afe040efe67d95c3eae129fb1439b04fbf7db8940c9e1328`.
Внешние фотографии, шрифтовые файлы и чужие иллюстрации в ресурс не копировались.

## Источники техники

- [Определения точек, таблица 1 первичного исследования](https://pmc.ncbi.nlm.nih.gov/articles/PMC7156815/).
- [Инструкция производителя Lafayette](https://lafayetteinstrument.com/downloads/manuals/MAN828-01128-pdf-rev5.pdf): расслабленное бедро, повторные замеры, техника калипера.
- [Первичное исследование с чтением через 2 секунды](https://pmc.ncbi.nlm.nih.gov/articles/PMC9144069/).

Время считывания определяется инструкцией конкретного калипера: распространённые
протоколы и устройства используют разные интервалы. В приложении это указано отдельно.

## Запросы к генератору

Использован встроенный imagegen, без CLI и API-ключа. Ниже точные запросы, участвовавшие
в создании выбранного ресурса. Промежуточные варианты в приложение не включены.

### Исходная генерация

```text
Use case: scientific-educational.
Asset type: a Russian-language instructional poster for the male Jackson–Pollock three-site skinfold protocol, to be embedded in a personal health measurement journal.
Create one polished original high-resolution PORTRAIT infographic, approximately 1024×1536 proportions, fully opaque warm white background. Clear medical textbook illustration, adult male with ordinary build, natural proportions, short dark sports shorts, no sexualization. Muted teal accent, dark slate text, bright red ONLY for fold measurement lines. Large readable Cyrillic typography, generous spacing. Three numbered horizontal panels stacked vertically: each has an anatomically correct FRONT VIEW cropped body-region illustration at left and concise text at right. It must be usable on a phone when enlarged.
Header exactly: "КАЛИПЕР: 3 ТОЧКИ"
Subheader exactly: "Мужской протокол Jackson–Pollock"
Prominent note exactly: "Измеряйте справа на своём теле"
Small clarifier exactly: "На рисунке правая сторона тела — слева для зрителя."

Panel 1 title exactly: "1. ГРУДЬ"
Illustration: true front view of male upper torso, right upper arm slightly away for landmark visibility. On the man's anatomical RIGHT chest (viewer LEFT), show the anterior axillary fold where the front of the upper arm meets the chest, and the right nipple. A pale dashed diagonal guideline connects the anterior axillary landmark down and inward to the right nipple. Exactly halfway along it, place a SHORT red diagonal fold line parallel to the guideline, with a red dot at its midpoint. This is the pectoral skinfold point, not the nipple, not the sternum, not directly inside the armpit. Do not mark the opposite chest. Text exactly: "Середина между передним краем подмышки и соском." and "Складка — диагональная."

Panel 2 title exactly: "2. ЖИВОТ"
Illustration: true front view of male abdomen, navel clearly visible at center. Show a short horizontal double-headed distance arrow from navel to a point exactly 2 cm to the man's anatomical RIGHT of navel (viewer LEFT), SAME horizontal level as navel, very close to navel rather than side waist. Label arrow exactly: "2 см". At this point draw a SHORT red VERTICAL fold line with red dot at midpoint. No other measurement point. Text exactly: "На 2 см правее пупка." and "Складка — вертикальная."

Panel 3 title exactly: "3. БЕДРО"
Illustration: true front view of adult male hips and both thighs down to knees, wearing small shorts that leave most thighs visible. On anatomical RIGHT thigh (viewer LEFT), clearly indicate the inguinal crease where the thigh joins the pelvis and the UPPER edge of the right kneecap. A pale dashed vertical reference segment along the FRONT CENTER of the right thigh spans these two landmarks. At EXACT midpoint, draw a short red VERTICAL fold line with red midpoint dot. Point must be front midline, not outer or inner thigh, not at hip, not on knee. Both feet point forward; right thigh relaxed, weight on left leg. Text exactly: "Середина передней поверхности: от паховой складки до верхнего края коленной чашечки." and "Складка — вертикальная."

Bottom separated footer heading exactly: "КАК СНЯТЬ ПОКАЗАНИЕ"
Use four short clearly readable lines, exact text:
"Захватите кожу и подкожный жир без мышцы."
"Держите складку. Губки калипера — поперёк неё, примерно в 1 см от пальцев."
"Снимите показание через 1–2 секунды."
"Повторите точки по кругу. Показание в мм не делите на два."

Anatomical accuracy is more important than decorative art. Red lines indicate the LONG AXIS of the skinfold, not the direction in which caliper jaws clamp. The entire poster must preserve front-view laterality: all three targets are on the viewer's LEFT side, representing the person's RIGHT side. No mirrored view, no unnecessary arrows, no generic side waist point, no horizontal abdominal fold. No full names, photographs, watermarks, source logos, body-fat promises, extra invented text, extra fold sites, formula coefficients or skeletal cutaways. Keep text inside margins, spell every Russian word exactly as provided.
```

### Правка разметки и времени считывания

```text
Use case: scientific-educational / precise-object-edit.
Edit this Russian instructional poster non-destructively. Preserve the overall layout, dimensions, adult male appearance, colors, headings, all panel explanatory text, and correct RIGHT-body / viewer-LEFT laterality. Correct only factual measurement annotations and one device-dependent timing sentence.

1. CHEST: the current red slash is perpendicular to the dashed anterior-axillary-to-nipple line. That is WRONG for a fold-direction indicator. Replace it with a short RED line ALIGNED WITH the dashed line: it must descend from upper left to lower right on the page, like a backslash. Its center must be at the midpoint between the right anterior axillary landmark and the right nipple. Keep the dashed guideline and its two landmarks. This red line indicates the skinfold LONG AXIS, not caliper-jaw direction.

2. ABDOMEN: move the red vertical fold mark MUCH CLOSER to the navel and put its midpoint on precisely the SAME horizontal level as the CENTER of the navel. In this front view it remains on the viewer LEFT, representing person's RIGHT. The current mark is too far laterally. On the current approximately1024px-wide image, the navel center is near x307,y697; center the revised red mark near x282,y697, about25px left of navel, NOT x220. Keep it vertical. Redraw a SHORT horizontal double-ended distance bracket whose endpoints touch the red fold center line and the navel's central vertical axis; place exact "2 см" just above it, with no confusing gap between bracket and landmarks. This is a schematic2cm offset, not a long arrow to side waist.

3. THIGH: the dashed upper landmark currently starts at the shorts hem. The actual reference MUST be the anatomical inguinal crease where thigh joins pelvis, which is ABOVE the hem. Use a discrete pale overlay of the right inguinal crease if it is covered by the shorts. Start the dashed anterior midline reference at that anatomical level, NOT at clothing hem. Its lower endpoint remains at the UPPER edge of the kneecap, not center/bottom of kneecap. Move the red vertical short fold marker to the exact midpoint between these anatomical endpoints, on the FRONT CENTER of the right thigh (viewer LEFT), not inner or outer surface. Keep modest shorts; no exposed genitals. If necessary make the hem slightly shorter only to clarify the landmark. Do not mistake waistband for inguinal crease.

4. Footer line3: replace "3. Снимите показание через 1–2 секунды." with exactly "3. Время считывания — по инструкции вашего калипера."
All other footer sentences remain exact, no additional text. Keep clear large readable Cyrillic and no overlap with drawings.

Do not mirror, move targets to opposite body side, add extra folds, make other creative edits, add new labels, or change typography/style. Accuracy of fold locations and directions is essential.
```

### Завершающая правка графических обозначений

```text
Edit only the graphic annotation marks on this existing educational poster. Preserve the photographed person, body, shorts, hands, every Russian text line, white background, three-panel layout, colors, borders, resolution and footer exactly. Do not alter any anatomy or clothing.

Two small graphic fixes:
A) In the second panel, move the existing red vertical measurement mark rightwards to lie very close beside the visible navel, still to its viewer-left. Red mark center should be aroundx280 at the same height as navel center aroundy694 on this1024px-wide image. Redraw the tiny horizontal "2 см" bracket between this mark and the navel. Remove old mark atx242. Preserve exact existing label "2 см".
B) In the third panel, REMOVE the blue dotted vertical guideline and its two blue endpoint dots from the leg. Leave ONLY the existing short red vertical fold line/dot in its current front-thigh position. The text already explains finding the midpoint, so there is no need for those blue reference marks. Restore clean natural background underneath those blue marks.

Nothing else changes. No warping, horizontal strips, extra text, overlays, mirroring, body edits, cropping, or layout changes. In particular keep the third panel's "3. БЕДРО" heading and hands/shorts perfectly clean and unaltered.
```
