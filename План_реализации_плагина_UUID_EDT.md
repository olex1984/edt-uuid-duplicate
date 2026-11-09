# План реализации плагина «Поиск дубликатов UUID» для 1С:EDT

> **Тип документа:** план реализации (implementation plan), не архитектура.
> **Версия:** 1.1 (решения по открытым вопросам зафиксированы 2026-11-06)
> **Дата:** 2026-11-03
> **Основание:**
> - `Архитектура_плагина_поиска_дублей_UUID_EDT.md` — целевая архитектура (компоненты, точки расширения).
> **Целевая платформа:** 1С:EDT 2025.x / 2026.x (Eclipse RCP / Equinox, Java 17+).
> **Оценка:** ~13 человеко-дней + буфер 20 %; MVP (M0–M3) — ~6 дней, один разработчик.

---

## 0. Кратко (TL;DR)

1. **Поведение зафиксировано и закрыто тестами.** Плагин определяет: набор расширений (`.mdo` + `.xml`), нормализацию UUID в нижний регистр, формат CSV (`;`, `UUID;Файл`), формат текстового отчёта, семантику «дубль = UUID в ≥2 файлах» и режим принудительного пересчёта.
2. **Надёжная основа вместо анализа текста:** StAX-парсер; персистентный кэш индекса в `state location`; автообновление через `IResourceChangeListener` + debounce + `WorkspaceJob`.
3. **Полный набор сценариев:** маркеры в Problems, view с навигацией, внутрифайловые повторы (как отдельная категория), кэш холодного старта, настройки per-project, экспорт отчёта/CSV из плагина.
4. **Архитектурный документ уточняется в 4 местах:** (а) свой builder/nature не добавляем (слишком интрузивно для чужого `.project`), (б) добавляем слой персистентного кэша и debounce, (в) явно фиксируем политику внутрифайловых дублей, (г) фиксируем форматы экспорта CSV/отчёта и тестовую стратегию.

---

## 1. Цель, границы, критерии успеха

### 1.1 Цель

Дать разработчику 1С:EDT инструмент, который **автоматически и без ручного запуска** находит дубликаты UUID в файлах метаданных открытого проекта и показывает их в привычных местах Eclipse (Problems view, отдельная view), без внешних зависимостей и не блокируя UI.

### 1.2 Входит в объём (v1.0)

- Сканирование файлов проекта EDT с расширениями `.mdo` (основное) и `.xml` (настраивается).
- Извлечение **только собственных** UUID — атрибут `uuid` XML-элемента (не `ref`, не `typeId`, не текст, не комментарии).
- Индекс `uuid → вхождения (файл, строка, тип элемента)` и обратный `файл → uuid`.
- Детекция дублей:
  - **межфайловые** (`uuid` в ≥2 файлах) — основная категория;
  - **внутрифайловые** (`uuid` дважды в одном файле) — отдельная категория (см. §2.3).
- Маркеры Problems view с переходом к строке; удаление при исчезновении дубля.
- View «Дубли UUID»: таблица, сортировка, фильтр, двойной клик → файл на строке.
- Триггеры: `IResourceChangeListener` (POST_CHANGE) + debounce, пользовательская команда «Проверить дубликаты UUID», фоновый прогрев при открытии проекта.
- Персистентный кэш индекса (холодный старт без полного скана), настройки (instance + project scope).
- Экспорт текстового отчёта и CSV в фиксированных форматах (для обмена и posthandling-пайплайна).

### 1.3 Не входит в объём (v1.0)

- Автоматическое исправление/перегенерация UUID (Quick Fix) — только как задел на v1.1.
- Поддержка `.cf`, `.dt`, `.epf`, `.erf` — это не файловая область; нужен отдельный адаптер.
- Сканирование произвольной папки вне workspace в GUI (вместо этого — headless-режим для CI, M7, опционально).
- Интеграция с Git-хуками, серверными проверками, CI-гейтами (кроме headless-экспорта).
- Точные типы элементов из модели EDT (`com._1c.*`) — сознательно не подключаем.

### 1.4 Критерии успеха

| № | Критерий | Как проверяем |
|---|---|---|
| 1 | На тестовом проекте с известными дублями плагин показывает все группы, ни одной лишней | Интеграционный чек-лист §8.3 |
| 2 | Правка `.mdo` отражается в Problems/View без ручных действий, ≤ 1 с после сохранения | Замер + ручной сценарий |
| 3 | Полный скан 5 000 `.mdo` — ≤ 3 с, UI не блокируется | Профилировочный тест §8.4 |
| 4 | CSV/отчёт совпадают с эталонными экспортами на «чистом» наборе фикстур (кроме задокументированных отличий) | Регрессионные тесты §8.2 |
| 5 | Плагин работает на EDT 2025.x и 2026.x без `com._1c.*` в зависимостях | Проверка MANIFEST + запуск на двух версиях |

---

## 2. Требования к поведению и проектные решения

### 2.1 Требования к извлечению и отчёту

1. Вход — проект рабочего пространства (GUI) или каталог (headless-режим); проверяется доступность и наличие сканируемых файлов.
2. Обход рекурсивный и детерминированный (сортировка путей); расширения `.mdo` и `.xml` сравниваются без учёта регистра; служебные каталоги исключаются.
3. UUID извлекается только из атрибута `uuid` элемента: имя атрибута сравнивается без учёта регистра, значение проверяется строгой маской 8-4-4-4-12 hex и нормализуется в нижний регистр. Невалидное значение не учитывается.
4. Строятся карты `файл → {uuid}` и `uuid → {файлы}`; вычисляется статистика: число файлов по расширениям, число уникальных UUID, число групп дублей.
5. Дубль — UUID, встречающийся в двух и более разных файлах; повтор внутри одного файла — отдельная категория.
6. Текстовый отчёт содержит статистику и список групп дублей с относительными путями; CSV — заголовок `UUID;Файл` и одну строку на пару (uuid, файл).
7. Экспорт выполняется по явной команде; при отсутствии дублей отчёт либо не создаётся, либо пишется пустым — в зависимости от настройки. Принудительный пересчёт игнорирует кэш.

### 2.2 Инварианты поведения

| № | Аспект | Требование | Решение в плагине |
|---|---|---|---|
| 1 | Набор расширений | `.xml` + `.mdo`, регистронезависимо | Настройка `scan.extensions`, по умолчанию `mdo,xml` (§6.4) |
| 2 | Что считается UUID | Только атрибут с именем `uuid` (любой регистр), значение строго 8-4-4-4-12 hex | `UuidXmlParser`, сравнение `localName` без учёта регистра + строгая regex |
| 3 | Нормализация | Нижний регистр | `toLowerCase(Locale.ROOT)` |
| 4 | Ключ дубля | UUID в ≥2 **разных** файлах | `DuplicateKind.CROSS_FILE` (ERROR/WARNING по настройке) |
| 5 | Относительные пути в отчёте/CSV | Пути относительно корня проекта | Проектно-относительный путь (`IPath`), разделитель `/` |
| 6 | Формат CSV | UTF-8, `;`, `UUID;Файл`, сортировка по UUID, затем по пути, одна строка на пару (uuid, файл) | `CsvExporter` — байт-в-байт (см. §A) |
| 7 | Формат отчёта | Эмодзи-заголовки, `━`×40, статистика, пустая строка, список дублей | `TextReportExporter` — 1:1 (см. §A) |
| 8 | Принудительный пересчёт | Команда, игнорирующая кэш | Команда «Полное пересканирование» + параметр `force` |
| 9 | Только при наличии дублей | Отчёт пишется, иначе пустой; CSV удаляется | Экспорт — по явной команде; опция «писать пустой отчёт» для CI |

### 2.3 Риски наивной реализации и принятые меры

**Д1. Поиск по тексту даёт ложные срабатывания.** Проверено на реальном шаблоне:

| Вход | Поиск по тексту находит | Это дубль UUID? |
|---|---|---|
| `<x myuuid="aaaaaaaa-..."/>` | **да** | нет — нет границы слова |
| `<!-- uuid="aaaaaaaa-..." -->` | **да** | нет — комментарий |
| `<name>uuid="aaaaaaaa-..."</name>` | **да** | нет — текст, не атрибут |
| `<x ref="aaaaaaaa-..."/>` | нет | верно (не `uuid`) |
| `<x UUID="AAAAAAAA-..."/>` | да | да (регистронезависимость) |

StAX устраняет все три ложных случая: проверяется именно атрибут элемента, комментарии и текст не участвуют.

**Д2. Хранение только множества UUID на файл теряет внутрифайловые повторы.** Если хранить по файлу только `set`, повтор внутри одного `.mdo` не виден. В плагине это отдельная категория `INTRA_FILE` (WARNING по умолчанию), включаемая настройкой.

**Д3. Сохранение состояния только при наличии дублей.** Если обновлять кэш лишь при найденных дублях, после исправления остаётся устаревший снимок → следующий запуск снова видит «изменения». В плагине индекс сохраняется **всегда** после успешного скана; сравнение — по `modificationStamp`/размеру файла, а не по полному словарю.

**Д4. Запись выходных файлов в каталог установки.** Запись отчётов рядом с приложением ломается при установке в `Program Files`, read-only-контейнере или параллельном запуске двух копий. В плагине: view + маркеры; экспорт — только по команде в выбранный пользователем путь; кэш — в `state location` workspace.

**Д5. Ошибки чтения/парсинга проглатываются молча.** Если ошибки не фиксировать, файл с невалидным XML или проблемой доступа выпадает из проверки — «слепая зона». В плагине: `ScanResult.failed`, агрегированный лог, счётчик ошибок в view/статусе; опционально — warning-маркер «файл не разобран».

**Д6. Пути зависят от текущего каталога.** Пути, зависящие от cwd, инвалидируют сравнение при переносе workspace. В плагине — проектно-относительные пути.

**Д7. Нет исключений служебных каталогов.** Рекурсивный обход без исключений собирает `.git`, `out`, `build`, `bin`, `.settings`, `node_modules`: это шум и лишние дубли (например, копии в `out`). В плагине — список исключений + пропуск derived/team-private/linked (настраиваемо).

**Д8. Неиспользуемые поля в отчёте.** Показатели, которые считаются, но не выводятся, усложняют формат и тесты. Оставляем только те, которые отображаются и проверяются.

**Д9. Базовый CSV содержит только пары (uuid, файл), без строк и типов.** Для Problems/View нужны вхождения с номерами строк; базовый CSV сохраняем как совместимый формат, расширенный — опцией.

**Д10. Жёсткое декодирование искажает данные.** Чтение в UTF-8 с игнорированием ошибок теряет значения в другой кодировке. StAX определяет кодировку по XML-декларации и BOM.

### 2.4 Сводка решений

| Область | Наивная реализация | Плагин | Причина |
|---|---|---|---|
| Извлечение | поиск по тексту | StAX, только атрибуты элемента | корректность (Д1) |
| Хранение | словарь `uuid → файлы` | индекс `uuid → вхождения` + `файл → uuid` + stamp | инкрементальность, навигация |
| Изменения | сравнение полного словаря | `modificationStamp` + размер + кэш | скорость, устойчивость (Д3, Д6) |
| Триггер | ручной запуск | listener + debounce + job + команда | UX, «оно само работает» |
| Вывод | txt + csv в каталог приложения | маркеры + view; экспорт по команде | безопасность, навигация (Д4) |
| Внутрифайловые | не видны | категория WARNING | полнота (Д2) |
| Ошибки | молча | `ScanResult.failed` + лог/счётчик | наблюдаемость (Д5) |

---

## 3. Целевая архитектура (уточнённая)

### 3.1 Компоненты и потоки

```
                    ┌──────────────────────── UuidCheckPlugin (Activator) ───────────────────────┐
                    │                                                                             │
  EDT events        │   ┌──────────────────┐      ┌──────────────────────┐                      │
  POST_CHANGE ──────┼──▶│ UuidResource     │─────▶│ ScanScheduler        │                      │
  команда      ─────┼──▶│ ChangeListener   │      │ (debounce, 750 мс)   │                      │
  startup      ─────┼──▶│ UuidCheckCommand │      └──────────┬───────────┘                      │
                    │   │ Handler          │                 │ WorkspaceJob (rule = project)     │
                    │   └──────────────────┘                 ▼                                   │
                    │                         ┌─────────────────────────────┐                  │
                    │                         │ UuidIndexService            │                  │
                    │                         │  • rescan / requestScan   │                  │
                    │                         │  • snapshot()               │                  │
                    │                         │  • add/removeListener()     │                  │
                    │                         └───────┬─────────────┬───────┘                  │
                    │                                 │             │                          │
                    │                    ┌────────────▼───┐   ┌─────▼─────────────┐            │
                    │                    │ MdoUuidScanner │   │ ProjectUuidIndex  │            │
                    │                    │  └ UuidXmlParser│  │ byUuid / byFile   │            │
                    │                    └────────────┬───┘   │ + PersistedStore  │            │
                    │                                 │       └─────┬─────────────┘            │
                    │                                 ▼             │                          │
                    │                         ┌───────────────────▼────┐                    │
                    │                         │ DuplicateAnalyzer      │                    │
                    │                         │ CROSS_FILE / INTRA_FILE│                    │
                    │                         └───────┬────────────────┘                    │
                    │                                 │                                     │
                    │           ┌─────────────────────┼──────────────────────┐               │
                    │           ▼                     ▼                      ▼               │
                    │   DuplicateUuidMarkerSvc   DuplicateUuidView   Exporters (CSV/TXT)       │
                    │           │                     │                      │               │
                    │           ▼                     ▼                      ▼               │
                    │     Problems view          таблица дублей        файлы на диске          │
                    └─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Ключевые решения (ADR)

**ADR-1. Работаем через workspace-модель (`IProject`/`IFile`), а не через `java.io.File`.**
Почему: дельты изменений, корректные кодировки, маркеры, linked-ресурсы, изоляция по проектам.
Следствие: GUI-плагин индексирует только проекты workspace; внешние каталоги — через импорт/линк или headless-режим (M7).

**ADR-2. Парсер — StAX (`XMLStreamReader`), потоково; regex остаётся только для валидации значения UUID.**
Почему: исключает ложные срабатывания (Д1), не грузит файл в память, даёт номер строки и корректную кодировку.
Следствие: парсер «fail-soft»: `ScanResult.failed`, файл не роняет скан; ошибки агрегируются.

**ADR-3. Персистентный кэш индекса в `state location`.**
Почему: не пишем в проект (Д4), ключи — проектно-относительные (Д6), валидация по `modificationStamp` + размеру, а не по полному словарю.
Следствие: холодный старт EDT не требует полного скана; кэш версионируется (`schemaVersion`), при несовпадении — миграция/сброс.

**ADR-4. Собственный builder и project nature не добавляем (в v1.0).**
Почему: builder требует правки `.project` пользователя, конфликтует с builder'ами EDT и сборочным порядком; для чужого плагина это интрузивно. Тот же результат дают `POST_CHANGE` + debounce + `WorkspaceJob` + команда полного скана. Дополнительно слушаем `POST_BUILD` как «повод перепроверить», но не встраиваемся в цепочку сборки.
Следствие: нет автоматического пересчёта при закрытом workspace — при старте кэш + фоновая валидация. Если понадобится жёсткая привязка к сборке — отдельный opt-in-режим в v1.1.

**ADR-5. Маркеры — `persistent=false`, пересоздаются по индексу.**
Почему: не нужно чистить устаревшие маркеры между сессиями и после падений; после рестарта EDT автоскан восстановит картину.
Следствие: между рестартами маркеров нет до первого скана; прогреваем проект на старте.

**ADR-6. Только публичные Eclipse API; ни одного импорта `com._1c.*`.**
Почему: совместимость 2025/2026 без пересборки под версии EDT.
Следствие: проект EDT детектируется по файловым признакам (`DT-INF/PROJECT.PMF`, `src/Configuration/Configuration.mdo`, наличие `.mdo`), список признаков — в настройках.

**ADR-7. Расширения настраиваемы; по умолчанию `mdo,xml`.**
Почему: у пользователя могут быть XML-дампы метаданных.
Следствие: для «чистого» EDT-проекта XML-файлов почти нет — цена нулевая; для дампов — сканирование также работает.

**ADR-8. Категории дублей и severity.**
Межфайловый дубль — `WARNING` по умолчанию (настраивается `ERROR`); внутрифайловый — `WARNING` (можно `IGNORE`). Маркер не блокирует сборку, но заметен в Problems.

**ADR-9. Идентичность вхождений и путей.**
`UuidOccurrence = { IPath projectRelativePath, String uuid, String elementType, int line, int charStart, int charEnd }`. В кэше и экспорте — только проектно-относительные пути с `/`; абсолютные пути — только для отображения.

**ADR-10. Без внешних библиотек.** JSON-кэш — минимальный собственный writer/reader (или `java.util.Properties`-формат), чтобы не тянуть Gson/Jackson в target platform. Парсер — JDK StAX.

---

## 4. Структура репозитория и бандла

```
EDT_PLUG_DUPLICATES/
├── ru.citcer.uuidcheck/                     # бандл плагина
│   ├── META-INF/MANIFEST.MF
│   ├── build.properties
│   ├── plugin.xml
│   ├── plugin.properties                      # NLS-строки (RU/EN)
│   ├── icons/
│   │   ├── duplicates.png                     # иконка view/команды
│   │   └── marker.png
│   └── src/ru/citcer/uuidcheck/
│       ├── UuidCheckPlugin.java               # Activator (AbstractUIPlugin)
│       ├── Messages.java                      # NLS
│       ├── PreferenceInitializer.java
│       ├── core/
│       │   ├── IUuidIndexService.java
│       │   ├── UuidIndexService.java          # фасад, RW-lock, планирование
│       │   ├── IndexSnapshot.java             # immutable снимок
│       │   ├── IndexChange.java
│       │   ├── IUuidIndexListener.java
│       │   ├── DuplicateAnalyzer.java
│       │   ├── DuplicateGroup.java
│       │   ├── DuplicateKind.java
│       │   ├── ProjectUuidIndex.java          # byUuid + byFile + stamp
│       │   ├── FileIndexEntry.java
│       │   ├── PersistedIndexStore.java
│       │   └── ScanScheduler.java
│       ├── scan/
│       │   ├── UuidXmlParser.java             # чистый Java, без Eclipse
│       │   ├── MdoUuidScanner.java            # адаптер IFile → parser
│       │   ├── UuidOccurrence.java
│       │   ├── RawOccurrence.java
│       │   └── ScanResult.java
│       ├── listener/
│       │   └── UuidResourceChangeListener.java
│       ├── markers/
│       │   ├── DuplicateUuidMarker.java       # константы типа/атрибутов
│       │   └── DuplicateUuidMarkerService.java
│       ├── export/
│       │   ├── CsvExporter.java
│       │   └── TextReportExporter.java
│       └── ui/
│           ├── DuplicateUuidView.java
│           ├── CheckUuidCommandHandler.java
│           ├── RescanAllHandler.java
│           ├── ExportCsvHandler.java
│           ├── ExportReportHandler.java
│           ├── UuidCheckPreferencePage.java
│           ├── ProjectSelection.java        # selection → IProject
│           └── UiUtils.java
├── ru.citcer.uuidcheck.tests/               # фрагмент-тесты
│   ├── META-INF/MANIFEST.MF                   # Fragment-Host
│   └── src/ru/citcer/uuidcheck/tests/...
├── releng/
│   └── ru.citcer.uuidcheck.target/          # .target на базе EDT
└── README.md, CHANGELOG.md, LICENSE
```

**Решение (2026-11-06):** `Bundle-SymbolicName` = `ru.citcer.uuidcheck` (дополнительно `ru.citcer.uuidcheck.tests`, `ru.citcer.uuidcheck.target`); вендор — `Oleg Zitzer` (контакт: oleg.zitzer@gmail.com); лицензия — EPL-2.0; `Bundle-Version` = `1.0.0.qualifier`.

---

## 5. Компоненты и API

### 5.1 Модель

```java
// scan/UuidOccurrence.java — immutable, ключ навигации
public record UuidOccurrence(
        IPath file,            // проектно-относительный путь, '/' разделитель
        String uuid,           // нижний регистр, канонический 8-4-4-4-12
        String elementType,    // localName, напр. "Catalog"
        String qualifiedName,  // qName, напр. "mdclass:Catalog"
        int line,              // 1-based
        int charStart,
        int charEnd) {}

// scan/RawOccurrence.java — результат парсера, ещё без IFile
public record RawOccurrence(String uuid, String elementType,
                            String qualifiedName, int line,
                            int charStart, int charEnd) {}

// core/DuplicateKind.java
public enum DuplicateKind { CROSS_FILE, INTRA_FILE }

// core/DuplicateGroup.java
public record DuplicateGroup(
        String uuid,
        DuplicateKind kind,
        List<UuidOccurrence> occurrences,
        Set<IPath> files) {
    public int fileCount() { return files.size(); }
}
```

### 5.2 Парсер `UuidXmlParser` (чистый, без Eclipse)

```java
public final class UuidXmlParser {
    private static final Pattern STRICT_UUID = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
      + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Потоково читает XML; на каждый элемент с атрибутом uuid вызывает sink. */
    public static void parse(InputStream in, Consumer<RawOccurrence> sink)
            throws XMLStreamException {
        XMLInputFactory f = newFactory();
        XMLStreamReader r = f.createXMLStreamReader(in);
        try {
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                String uuid = ownUuid(r);
                if (uuid == null) continue;
                Location loc = r.getLocation();
                sink.accept(new RawOccurrence(
                        uuid.toLowerCase(Locale.ROOT),
                        r.getLocalName(),
                        r.getName().toString(),
                        loc.getLineNumber(),
                        loc.getCharacterOffset(),
                        loc.getCharacterOffset() + r.getName().toString().length()));
            }
        } finally {
            r.close();
        }
    }

    private static String ownUuid(XMLStreamReader r) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if ("uuid".equalsIgnoreCase(r.getAttributeLocalName(i))) {
                String v = r.getAttributeValue(i).trim();
                return STRICT_UUID.matcher(v).matches() ? v : null; // невалидный uuid не учитываем
            }
        }
        return null;
    }

    private static XMLInputFactory newFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        setIfSupported(f, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        setIfSupported(f, "javax.xml.stream.isSupportingExternalEntities", Boolean.FALSE);
        setIfSupported(f, XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        setIfSupported(f, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
        return f; // защита от XXE и подстановки сущностей
    }

    private static void setIfSupported(XMLInputFactory f, String prop, Object v) {
        try { f.setProperty(prop, v); } catch (IllegalArgumentException ignored) { }
    }
}
```

**Почему такой разбор корректен:** учитывается только атрибут `uuid` (имя — без учёта регистра), значение проверяется строгой regex, невалидное значение игнорируется. `ref`/`typeId`/текст/комментарии не участвуют.

### 5.3 Сканер `MdoUuidScanner` (адаптер `IFile`)

```java
public final class MdoUuidScanner {
    /** Не бросает CoreException наружу: любая ошибка → ScanResult.failed(...). */
    public ScanResult scan(IFile file) {
        List<UuidOccurrence> out = new ArrayList<>();
        IPath rel = file.getProjectRelativePath();
        try (InputStream in = file.getContents(true)) {
            UuidXmlParser.parse(in, raw -> {
                out.add(new UuidOccurrence(rel, raw.uuid(), raw.elementType(),
                        raw.qualifiedName(), raw.line(), raw.charStart(), raw.charEnd()));
            });
        } catch (CoreException | XMLStreamException e) {
            // CoreException — от getContents; в реальном коде ловить отдельно, чтобы
            // различать «нет доступа» и «битый XML» в поле failure.
            return ScanResult.failed(file, e.getMessage());
        } catch (IOException e) {
            return ScanResult.failed(file, e.getMessage());
        }
        return ScanResult.ok(file, out);
    }
}
```

### 5.4 `ProjectUuidIndex` и `UuidIndexService`

```java
public interface IUuidIndexService {
    IndexSnapshot snapshot();                              // immutable, для UI/экспорта
    void rescan(IProject project, boolean ignoreCache, IProgressMonitor monitor); // блокирующий, из job
    void requestScan(IProject project, boolean ignoreCache); // асинхронно, через ScanScheduler
    void addListener(IUuidIndexListener l);
    void removeListener(IUuidIndexListener l);
    Set<IProject> indexedProjects();
}
```

Доступ из UI — через `UuidCheckPlugin.getIndexService()` и `UuidCheckPlugin.showView()` (статические методы Activator'а).

`ProjectUuidIndex` (не потокобезопасен сам по себе, живёт под write-lock сервиса):

```java
final class ProjectUuidIndex {
    final Map<String, List<UuidOccurrence>> byUuid = new HashMap<>();
    final Map<IPath, FileIndexEntry> byFile = new HashMap<>();

    Set<String> replaceFile(IFile f, List<UuidOccurrence> fresh);  // возвращает затронутые uuid
    Set<IPath> removeFile(IPath path);
    IndexSnapshot snapshot();
}

record FileIndexEntry(long modificationStamp, long size,
                      List<UuidOccurrence> occurrences, String failure) {}
```

**Алгоритм обновления файла (инкрементальный):**

1. `affected = union(old uuids файла, new uuids файла)`.
2. Удалить старые вхождения файла из `byUuid`, вставить новые.
3. Пересчитать `DuplicateGroup` только для `affected` uuid.
4. Обновить маркеры только для затронутых файлов.
5. Уведомить слушателей `IndexChange(project, affectedUuids, changedFiles)`.

**Полный скан:** под write-lock собрать новый `ProjectUuidIndex`, сравнить с текущим, атомарно подменить, обновить маркеры проекта целиком, сохранить кэш.

### 5.5 `PersistedIndexStore` (кэш индекса)

- Путь: `Platform.getStateLocation(bundle)` → `<workspace>/.metadata/.plugins/<symbolicName>/uuid-index-v1.json` (имя каталога — по `Bundle-SymbolicName`; здесь и далее показан placeholder).
- Формат — §B; ключи — проектно-относительные пути.
- Валидация при загрузке: существует ли проект/файл, совпадает ли `modificationStamp` и размер; при расхождении файл пересканируется.
- Запись: атомарно (`tmp` + `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`), throttle (не чаще 1 раза в 5 с или по завершении job).
- Ошибки записи — в лог, не роняют работу (read-only workspace).

### 5.6 `ScanScheduler` (debounce)

- Один фоновый daemon-`ScheduledExecutorService`.
- `request(project)` отменяет предыдущую задачу проекта и планирует новую через `debounceMs` (по умолчанию 750 мс).
- По срабатыванию — `WorkspaceJob` с `setRule(project)` (сериализация с builder'ами проекта), `setUser(true)`, `SubMonitor` с прогрессом и отменой.
- `stop()` в `Activator.stop()` — `shutdownNow()`.

### 5.7 Маркеры

`DuplicateUuidMarker` — константы:

```java
public final class DuplicateUuidMarker {
    public static final String TYPE = "ru.citcer.uuidcheck.duplicateUuidMarker";
    public static final String ATTR_UUID = "uuid";
    public static final String ATTR_ELEMENT_TYPE = "elementType";
    public static final String ATTR_DUPLICATE_FILES = "duplicateFiles"; // '\n'-разделённые пути
    public static final String ATTR_KIND = "kind";                      // CROSS_FILE/INTRA_FILE
}
```

`DuplicateUuidMarkerService.update(project, groups, affectedFiles)`:

- В `IWorkspace.run(..., IWorkspace.AVOID_UPDATE, ...)`:
  - удалить маркеры типа `TYPE` на `affectedFiles` (при полном скане — на проекте);
  - для каждого вхождения создать маркер: `SEVERITY`, `MESSAGE` (с перечнем «других» файлов), `LINE_NUMBER`, `CHAR_START`, `CHAR_END`, `PRIORITY`;
  - при превышении лимита (`maxMarkersPerProject`) — маркеры только для первых N групп + один сводный маркер на проекте.
- Текст: `Дубликат UUID <uuid>: тот же UUID используется в <k> файлах: <path:line>, ...` (обрезать до ~500 символов).

### 5.8 `UuidResourceChangeListener`

- Регистрация в `Activator.start()`: `IResourceChangeEvent.POST_CHANGE`.
- Обход `IResourceDelta`: только файлы с расширениями из настроек; пропуск `isDerived()`, `isTeamPrivateMember()`, `isLinked()` (настраиваемо), исключённых каталогов.
- `ADDED/CHANGED` → `requestScan(project)`; `REMOVED` → удалить из индекса; `MOVED_FROM`/`MOVED_TO` → переиндексация (для v1.0 достаточно `requestScan`).
- Дополнительно `PRE_CLOSE`/`PRE_DELETE` — сброс индекса проекта; `POST_BUILD` — опциональный триггер перепроверки.
- Настройки проекта: `IEclipsePreferences.IPreferenceChangeListener` на `ProjectScope(project)` — при изменении `scan.enabled`/`scan.extensions` выполнить `requestScan(project)`. Настройки, **сохранённые в `.settings/*.prefs` самим плагином**, ресурсную дельту не порождают, поэтому триггера не будет — отсюда явный listener.
- Защита от самозапуска: наши записи — только маркеры (не ресурсы) и кэш вне workspace; per-project настройки пишутся в `.settings/*.prefs` — расширение не входит в `scan.extensions`, поэтому игнорируется.

### 5.9 UI

**`CheckUuidCommandHandler`:**

```java
public Object execute(ExecutionEvent event) {
    IProject project = ProjectSelection.from(event); // Adapters.adapt(selection, IProject.class)
    if (project == null) return null;
    // не блокируем UI: скан уходит в WorkspaceJob через планировщик
    UuidCheckPlugin.getIndexService().requestScan(project, true);
    UuidCheckPlugin.showView();
    return null;
}
```

**`DuplicateUuidView`:**

- `TableViewer` (или `TreeViewer` с группировкой по UUID), колонки: `UUID | Файлов | Вхождений | Тип | Файлы и строки`.
- `ITableLabelProvider`; `ViewerComparator` по колонкам; `FilteredTree`/поле фильтра по UUID/пути.
- Подписка на `IUuidIndexListener`; обновление — `Display.asyncExec`, сравнение `generation` снимка.
- Двойной клик: `IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor", true)` и `ITextEditor.selectAndReveal(offset, length)`; fallback — просто открыть файл.
- Контекстное меню: «Открыть», «Копировать UUID», «Экспорт CSV…», «Экспорт отчёта…».
- Пустое состояние: «Дублей не найдено», счётчик просканированных файлов/ошибок.

### 5.10 Экспорт

- `CsvExporter.export(project, snapshot, Path out, CsvOptions)` — формат §A, опции: BOM для Excel, разделитель, extended-колонки (`line`, `element`).
- `TextReportExporter.export(...)` — формат §A 1:1, включая эмодзи (в коде — `\u`-escape, чтобы не зависеть от кодировки исходников).
- Экспорт вызывается только явно (команда/контекстное меню view), в выбранный путь; в проект ничего не пишем.

---

## 6. Конфигурация бандла и точек расширения

### 6.1 `plugin.xml` (полный набор для v1.0)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?eclipse version="3.4"?>
<plugin>

  <!-- Тип маркера -->
  <extension id="duplicateUuidMarker" name="%marker.name"
             point="org.eclipse.core.resources.markers">
    <super type="org.eclipse.core.resources.problemmarker"/>
    <super type="org.eclipse.core.resources.textmarker"/>
    <persistent value="false"/>
    <attribute name="uuid"/>
    <attribute name="elementType"/>
    <attribute name="duplicateFiles"/>
    <attribute name="kind"/>
  </extension>

  <!-- View -->
  <extension point="org.eclipse.ui.views">
    <category id="ru.citcer.uuidcheck.category" name="%category.name"/>
    <view id="ru.citcer.uuidcheck.views.duplicates"
          name="%view.name"
          category="ru.citcer.uuidcheck.category"
          class="ru.citcer.uuidcheck.ui.DuplicateUuidView"
          icon="icons/duplicates.png"
          restorable="true"/>
  </extension>

  <!-- Команды -->
  <extension point="org.eclipse.ui.commands">
    <command id="ru.citcer.uuidcheck.commands.checkProject"
             name="%command.check.name"/>
    <command id="ru.citcer.uuidcheck.commands.rescanAll"
             name="%command.rescanAll.name"/>
    <command id="ru.citcer.uuidcheck.commands.exportCsv"
             name="%command.exportCsv.name"/>
    <command id="ru.citcer.uuidcheck.commands.exportReport"
             name="%command.exportReport.name"/>
  </extension>

  <!-- Обработчики -->
  <extension point="org.eclipse.ui.handlers">
    <handler commandId="ru.citcer.uuidcheck.commands.checkProject"
             class="ru.citcer.uuidcheck.ui.CheckUuidCommandHandler"/>
    <handler commandId="ru.citcer.uuidcheck.commands.rescanAll"
             class="ru.citcer.uuidcheck.ui.RescanAllHandler"/>
    <handler commandId="ru.citcer.uuidcheck.commands.exportCsv"
             class="ru.citcer.uuidcheck.ui.ExportCsvHandler"/>
    <handler commandId="ru.citcer.uuidcheck.commands.exportReport"
             class="ru.citcer.uuidcheck.ui.ExportReportHandler"/>
  </extension>

  <!-- Меню: контекстное меню Project Explorer -->
  <extension point="org.eclipse.ui.menus">
    <menuContribution locationURI="popup:org.eclipse.ui.popup.any?after=additions">
      <command commandId="ru.citcer.uuidcheck.commands.checkProject"
               label="%menu.check.label">
        <visibleWhen checkEnabled="false">
          <with variable="selection">
            <iterate ifEmpty="false">
              <adapt type="org.eclipse.core.resources.IProject"/>
            </iterate>
          </with>
        </visibleWhen>
      </command>
    </menuContribution>
  </extension>

  <!-- Настройки -->
  <extension point="org.eclipse.ui.preferencePages">
    <page id="ru.citcer.uuidcheck.preferences.page"
          name="%prefs.page.name"
          class="ru.citcer.uuidcheck.ui.UuidCheckPreferencePage"/>
  </extension>
  <extension point="org.eclipse.core.runtime.preferences">
    <initializer class="ru.citcer.uuidcheck.PreferenceInitializer"/>
  </extension>

</plugin>
```

### 6.2 `META-INF/MANIFEST.MF`

```
Manifest-Version: 1.0
Bundle-ManifestVersion: 2
Bundle-Name: %bundleName
Bundle-SymbolicName: ru.citcer.uuidcheck;singleton:=true
Bundle-Version: 1.0.0.qualifier
Bundle-Vendor: %providerName
Bundle-Activator: ru.citcer.uuidcheck.UuidCheckPlugin
Bundle-RequiredExecutionEnvironment: JavaSE-17
Require-Bundle: org.eclipse.core.runtime,
 org.eclipse.core.resources,
 org.eclipse.ui,
 org.eclipse.ui.ide,
 org.eclipse.ui.workbench.texteditor
Import-Package: javax.xml.stream,
 org.eclipse.osgi.util
Automatic-Module-Name: ru.citcer.uuidcheck
Export-Package: ru.citcer.uuidcheck.core
```

> **Проверить на M0:** экспортируется ли `javax.xml.stream` системным бандлом в целевом EDT. Если PDE даёт unresolved — добавить `javax.xml.stream` в системные пакеты target/EE либо перейти на SAX (`javax.xml.parsers`, гарантированно экспортируется). Это единственная «серая зона» зависимостей.

### 6.3 `build.properties`

```
source.. = src/
output.. = bin/
bin.includes = META-INF/,\
               .,\
               plugin.xml,\
               plugin.properties,\
               icons/
```

### 6.4 Настройки (ключи и значения по умолчанию)

| Ключ | Scope | По умолчанию | Назначение |
|---|---|---|---|
| `scan.enabled` | instance/project | `true` | Автосканирование проекта |
| `scan.extensions` | instance/project | `mdo,xml` | Расширения файлов |
| `scan.excludeDirs` | instance | `.git,.settings,out,build,bin,target,node_modules,.idea` | Исключаемые каталоги |
| `scan.includeLinked` | instance | `false` | Сканировать linked-ресурсы |
| `scan.debounceMs` | instance | `750` | Задержка debounce |
| `scan.onStartup` | instance | `true` | Прогрев при старте EDT |
| `scan.dirtyFileThreshold` | instance | `200` | Порог «широкой» дельты: больше — полный скан проекта |
| `scan.reportParseErrors` | instance | `false` | Маркер «файл не разобран» |
| `severity.crossFile` | instance | `WARNING` | Severity межфайловых дублей |
| `markers.max` | instance | `500` | Лимит групп с маркерами |
| `intraFile.mode` | instance | `WARN` | `IGNORE/WARN/ERROR` для внутрифайловых |
| `export.csvBom` | instance | `false` | BOM для Excel |
| `export.csvSeparator` | instance | `;` | Разделитель CSV |
| `export.reportWhenEmpty` | instance | `false` | Писать отчёт-заглушку (`\n`) при отсутствии дублей (для CI) |
| `dialog.onNewDuplicates` | instance | `false` | Всплывающий диалог о новых дублях |

Правило: instance-scope — общие настройки; project-scope (`ProjectScope`, ключ `ru.citcer.uuidcheck`; из кода — `new ProjectScope(project).getNode(BUNDLE)` или `ProjectScope`-store для `AbstractPreferenceInitializer`) — только `scan.enabled`, `scan.extensions`, `intraFile.mode`, чтобы можно было отключить шумный/сгенерированный проект.

### 6.5 Локализация

- `plugin.properties` — `%`-ключи для `plugin.xml`; `Messages.java` (`NLS`) — для кода.
- Базовый язык — русский; задел на `messages_en.properties`.
- Эмодзи в отчёте — `\uD83D\uDCCA` и т.п. в константах, чтобы не зависеть от кодировки `.java` и `.properties` (properties по умолчанию ISO-8859-1).

### 6.6 Target platform, сборка, установка

**Разработка:**

1. Установить **Eclipse IDE for RCP and RAP Developers** версии, совместимой с базовой Eclipse у EDT (определить по `<EDT>/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` — версии `org.eclipse.core.runtime`, `org.eclipse.ui`).
2. Создать target definition `releng/.../*.target` с `<location path="<EDT_HOME>" type="Directory"/>`; активировать.
3. Импортировать три проекта: бандл, tests-фрагмент, target.
4. `Run As → Eclipse Application` на чистом workspace (быстрый цикл; EDT-рантайм не нужен, т.к. `com._1c.*` не используются).
5. Финальная проверка — **в реальном EDT**: экспорт jar в `dropins/` и рестарт (см. ниже).

**Сборка:**

- v1.0: `File → Export → Plug-in Development → Deployable plug-ins and fragments` (jar, qualifier).
- CI (опционально): Tycho 4.x, `packaging=eclipse-plugin`, `target-platform-configuration` c `.target`, p2 update site (`eclipse-repository` + feature).

**Установка:**

- `dropins/ru.citcer.uuidcheck_1.0.0.jar` → рестарт EDT → проверить `Help → About → Installation Details → Plug-ins`.
- Если не подхватилось — один раз запустить EDT с `-clean`.
- Удаление: убрать jar, рестарт; маркеры `persistent=false` исчезнут, кэш можно удалить из `.metadata/.plugins/...` (необязательно).

---

## 7. Этапы реализации

> Зависимости: M0 → M1 → M2 → M3 → (M4 ∥ M5) → M6 → M7. M4 и M5 можно вести параллельно после M3.
> Оценки — в человеко-днях, без буфера.

### M0. Каркас и среда (1 д.)

- [ ] Создать PDE-проект `ru.citcer.uuidcheck` (JavaSE-17), `build.properties`, пустой `plugin.xml`, `Activator`.
- [ ] `.target` на базе Eclipse RCP IDE; проверить резолв `org.eclipse.*` и `javax.xml.stream`.
- [ ] Launch config «Eclipse Application», чистый workspace `runtime-uuidcheck`.
- [ ] Каркас view (пустая таблица), команда-заглушка в контекстном меню.
- [ ] `.gitignore`, README, CHANGELOG, LICENSE.

**Приёмка:** бандл стартует, view открывается, команда видна в меню проекта, в `-consoleLog` нет ошибок.

### M1. Парсер и фикстуры (2 д.)

- [ ] `UuidXmlParser` (чистый Java), `RawOccurrence`, защитные настройки StAX.
- [ ] `MdoUuidScanner`, `UuidOccurrence`, `ScanResult` (ok/failed/cancelled).
- [ ] Обработка BOM/кодировок, битого XML, отмены; лимит «здоровья» (не падать на больших файлах).
- [ ] Фикстуры и unit-тесты (§8.1).
- [ ] Регрессионный тест экспорта на «чистом» наборе (§8.2).

**Приёмка:** все тесты зелёные; на «чистых» фикстурах результат совпадает с эталонными экспортами; ложные случаи (`myuuid`, комментарии, текст) не находятся; битый XML → `failed`, скан продолжается.

### M2. Индекс, анализ, кэш, экспорт (1.5 д.)

- [ ] `ProjectUuidIndex` (`byUuid`, `byFile`, `replaceFile`, `removeFile`, snapshot).
- [ ] `DuplicateAnalyzer` + `DuplicateGroup` (CROSS_FILE/INTRA_FILE).
- [ ] `UuidIndexService` (RW-lock, снимки, `rescan`, уведомления).
- [ ] `PersistedIndexStore` (schema v1, валидация по stamp/size, атомарная запись).
- [ ] `CsvExporter` / `TextReportExporter` (форматы §A) + тесты.
- [ ] Временный «прогон по проекту» через команду/тест-раннер.

**Приёмка:** на тестовом проекте получаем ожидаемые группы; повторный скан использует кэш (лог «пропущено N, пересканировано M»); CSV/отчёт совпадают с эталонными экспортами (кроме задокументированных отличий); при невалидном файле остальные обрабатываются.

### M3. Маркеры (1.5 д.)

- [ ] Тип маркера в `plugin.xml`, константы атрибутов.
- [ ] `DuplicateUuidMarkerService` (создание/удаление, batch через `IWorkspace.run`, лимит, severity).
- [ ] `CheckUuidCommandHandler`: полный скан → маркеры → открыть view.
- [ ] Навигация из Problems по строке (проверить поведение на форме/текстовом редакторе, см. риск R6).
- [ ] Удаление маркеров при исчезновении дублей и при закрытии/удалении проекта.

**Приёмка:** дубли видны в Problems; клик открывает файл; после исправления маркеры исчезают; на проекте без дублей — пусто; лимит не «вешает» UI.

### M4. View (2 д.)

- [ ] `DuplicateUuidView` с `TableViewer`, колонками, сортировкой, фильтром.
- [ ] Подписка на `IUuidIndexListener`, обновление в UI-потоке, индикация «скан идёт».
- [ ] Двойной клик → текстовая вкладка на строке (`selectAndReveal`).
- [ ] Контекстное меню, копирование UUID, пустое состояние, строка статуса.
- [ ] Персистентность раскладки view (`restorable=true`).

**Приёмка:** view отражает индекс, навигация работает на разных типах файлов, нет зависаний и лишних перерисовок.

### M5. Автообновление и кэш (2 д.)

- [ ] `UuidResourceChangeListener` (POST_CHANGE), фильтрация расширений/derived/team-private/linked/исключений.
- [ ] `ScanScheduler` (debounce, `WorkspaceJob` с project rule, отмена, прогресс).
- [ ] Инкрементальное обновление: затронутые uuid → маркеры только затронутых файлов.
- [ ] Тёплый старт: загрузка кэша, фоновая валидация, при необходимости — полный скан.
- [ ] Per-project настройки (`ProjectScope`), отключение для шумных проектов; listener на смену настроек → пересканирование.
- [ ] Обработка нескольких проектов, закрытия/удаления, `stop()` без утечек потоков.
- [ ] «Широкая дельта» → полный скан (порог `dirtyFileThreshold`); в callback — только планирование (§9, R11).

**Приёмка:** правка `.mdo` → обновление после debounce, без полного скана (по логу); перемещение/удаление файла корректно; UI свободен; рестарт EDT не запускает полный скан при неизменных файлах.

### M6. Полировка и надёжность (1.5 д.)

- [ ] Preference page + `PreferenceInitializer`; применение без перезапуска (или явное сообщение).
- [ ] Диалог «новые дубли» (опция, «не показывать»).
- [ ] Экспорт CSV/отчёта из view/команды; опции BOM/разделителя/extended.
- [ ] Агрегированное логирование ошибок; счётчик «файлов с ошибками» в view.
- [ ] Производительность: 5k файлов, профилирование, устранение узких мест.
- [ ] Обработка read-only кэша/workspace; no-op при отсутствии прав.
- [ ] Пользовательская документация (README, скриншоты, FAQ).

**Приёмка:** настройки применяются; perf-цели §9 достигнуты; ошибки видимы пользователю; документация позволяет установить и использовать без разработчика.

### M7. Поставка (1.5 д.)

- [ ] PDE-экспорт jar (или Tycho + p2 update site с feature).
- [ ] Установка и проверка на EDT 2025.x и 2026.x (dropins), проверка `Installation Details`.
- [ ] (Опционально) headless-приложение для CI: те же CSV/отчёт по `-data <ws>` и проекту.
- [ ] CHANGELOG, инструкция по установке/обновлению/удалению.
- [ ] Финальный прогон чек-листа §8.3 и DoD §11.

---

## 8. Тестирование

### 8.1 Unit-тесты парсера (фикстуры)

| Фикстура | Содержимое | Ожидание |
|---|---|---|
| `simple.mdo` | корневой элемент с `uuid` | 1 вхождение, верная строка |
| `nested.mdo` | корень + вложенный элемент с `uuid` | 2 вхождения |
| `uppercase-attr.mdo` | `<x UUID="...">` | 1, нормализован в lower |
| `single-quotes.mdo` | `uuid='...'` | 1 |
| `spaces.mdo` | `uuid = "..."` | 1 |
| `ref-only.mdo` | UUID только в `ref=`/`typeId=` | 0 |
| `comment-uuid.mdo` | UUID в XML-комментарии | 0 |
| `text-uuid.mdo` | UUID в тексте | 0 |
| `myuuid-attr.mdo` | `myuuid="..."` | 0 |
| `malformed.mdo` | обрыв XML | `ScanResult.failed`, без исключения |
| `bom.mdo` | UTF-8 BOM | 1 |
| `cp1251.mdo` | XML-декларация `windows-1251` | 1 (StAX определил кодировку) |
| `empty.mdo` | пустой файл | 0, ok |
| `big.mdo` | 10 000 элементов | 10 000, время < 100 мс |

Для `comment-uuid.mdo`, `text-uuid.mdo`, `myuuid-attr.mdo` ожидается 0 — это осознанное отличие от поиска по тексту.

### 8.2 Регрессионные тесты экспорта

- Ресурс `testdata/parity/` — только «чистые» случаи (без `myuuid`/комментариев/текста), чтобы результат был однозначным.
- Эталонный CSV/отчёт зафиксирован в репозитории как expected (тесты не требуют внешних инструментов в CI).
- Тест: Java-экспорт байт-в-байт (кроме `\r\n`-нормализации и задокументированного `/`-разделителя) сравнивается с expected.
- Отдельный тест «известные отличия»: фикстуры из Д1 — Java пусто, поиск по тексту непусто; тест фиксирует, что это осознанное решение.

### 8.3 Интеграционный чек-лист (ручной, на копии реального проекта)

1. Установка в EDT, открытие проекта с известными дублями → маркеры и view заполнены.
2. Правка файла, создающая дубль → обновление ≤ 1 с, без полного скана.
3. Исправление дубля → маркеры исчезают, view пустеет.
4. Переименование/перемещение файла → вхождения и маркеры корректны.
5. Удаление файла → маркеры исчезают, группа пересчитывается.
6. Закрытие/повторное открытие проекта → тёплый старт без полного скана.
7. Рестарт EDT → маркеры восстанавливаются после фонового скана.
8. Два проекта одновременно → индексы и маркеры не пересекаются.
9. Не-EDT проект → команда доступна, но скан пуст/сообщение; нет маркеров.
10. Проект с невалидным `.mdo` → остальные файлы обработаны, ошибка видна в статусе.
11. Отмена скана → нет частичных маркеров/битого кэша.
12. Отключение автоскана для проекта → правки не триггерят работу, ручная команда работает.
13. Экспорт CSV/отчёта → форматы совпадают с ожидаемыми, файлы открываются (Excel, Блокнот).
14. Большой проект (5k файлов) → UI отзывчив, прогресс/отмена.
15. EDT 2025.x и 2026.x → одинаковое поведение, нет ошибок в логе.

### 8.4 Производительность

| Сценарий | Цель |
|---|---|
| Полный скан 5 000 `.mdo` (~1 КБ) | ≤ 3 с (холодный), ≤ 1.5 с (тёплый) |
| Инкрементальное обновление 1 файла (скан + маркеры) | ≤ 100 мс |
| Память при 100 000 вхождений | ≤ 50 МБ |
| Любая операция в UI-потоке | ≤ 100 мс (без блокировок) |

---

## 9. Производительность и ограничения

- **Поток данных:** парсинг — StAX, O(размер файла); память — O(число вхождений), не O(текст файла).
- **Кэш** убирает повторный парсинг при старте; валидация по `modificationStamp` дешевле чтения.
- **Инкрементальность:** изменение файла → пересчёт только затронутых uuid; маркеры — только затронутых файлов.
- **Debounce** (750 мс) гасит серии событий от «Сохранить всё» и автосборки.
- **Ключевой риск perf (R11) — подписка на `POST_CHANGE`:** при объёмных операциях (checkout/pull на тысячи файлов) событие содержит большие дельты. Правила: не читать содержимое файлов **внутри callback** listener'а (только отметить проект «dirty» и запланировать job); при «широкой» дельте (> `dirtyFileThreshold`, например 200 затронутых файлов сканируемых расширений) переходить на полный скан проекта вместо точечного; отбрасывать дельты по `isDerived()`/`isTeamPrivateMember()` до любых обращений к содержимому.
- **Ограничения:** `.mdo`-редактор EDT может быть form-based — переход по `LINE_NUMBER` из Problems зависит от выбранного редактора (см. R6); маркеры `persistent=false` живут в рамках сессии; сканируются только проекты workspace.

---

## 10. Риски и открытые вопросы

| ID | Риск | Вероятность | Влияние | Смягчение |
|---|---|---|---|---|
| R1 | Точный формат `.mdo` в 2025/2026 отличается (ns, вложенные `uuid`, другие атрибуты) | Средняя | Высокое | M0: проверить на живой копии (§E); атрибут ищется по `localName` без регистра; фикстуры из реальных файлов; конфигурируемый список расширений |
| R2 | `javax.xml.stream` не резолвится в OSGi-среде EDT | Низкая | Среднее | Проверить в M0; fallback — SAX (`javax.xml.parsers`); вынести фабрику в один класс |
| R3 | Ложные дубли из `.xml` (не метаданные) или из копий в `out`/`build` | Средняя | Среднее | Исключения каталогов по умолчанию; возможность отключить `.xml`; per-project настройки |
| R4 | Маркеры на тысячи групп «засоряют» Problems | Средняя | Среднее | Лимит `markers.max` + сводный маркер; view показывает всё |
| R5 | Скан большого проекта блокирует работу | Низкая | Высокое | `WorkspaceJob` + project rule, прогресс, отмена, debounce, кэш |
| R6 | Переход из Problems по строке не работает в form-редакторе `.mdo` | Средняя | Среднее | View открывает `DefaultTextEditor` + `selectAndReveal`; документировать; при необходимости — настройка ассоциации файлов |
| R7 | Правка `.project`/natures при попытке builder'а | — | — | В v1.0 builder не добавляем (ADR-4) |
| R8 | Конфликт версий API между 2025/2026 | Низкая | Среднее | Только публичные API; target на минимальную поддерживаемую; тест на обеих версиях |
| R9 | Кэш повреждён/несовместим после обновления плагина | Низкая | Низкое | `schemaVersion`, атомарная запись, при ошибке — полный скан |
| R10 | `state location` недоступен/read-only | Низкая | Низкое | Работаем без кэша, лог-предупреждение |
| R11 | Широкая `POST_CHANGE`-дельта (checkout/pull) перегружает listener и инкрементальную обработку | Средняя | Высокое | Не читать содержимое в callback; порог → полный скан в job; фильтр derived/team-private (§9) |
| R12 | Конфликт маркеров с другими плагинами по тем же файлам (перезапись/дубли в Problems) | Низкая | Низкое | Уникальный тип маркера; удаляем только свои (`deleteMarkers(TYPE, ...)`), не трогаем чужие |

**Решения по открытым вопросам (зафиксированы 2026-11-06):**

| № | Вопрос | Решение |
|---|---|---|
| 1 | Bundle id / вендор / лицензия | `ru.citcer.uuidcheck`; вендор — Oleg Zitzer (oleg.zitzer@gmail.com); лицензия — **EPL-2.0** |
| 2 | Признак EDT-проекта | `DT-INF/PROJECT.PMF` **ИЛИ** `src/Configuration/Configuration.mdo` **ИЛИ** хотя бы один `.mdo` под `src` (покрывает и конфигурации, и проекты внешних обработок) |
| 3 | Headless/CI-режим | не входит в v1.0; перенесён в v1.1 (Roadmap §12) |
| 4 | Внутрифайловые дубли | `WARNING` по умолчанию, отключается настройкой `intraFile.mode=IGNORE` (instance/project) |
| 5 | Расширения | по умолчанию `mdo,xml` — покрывает `.mdo` и XML-дампы |
| 6 | Builder-триггер | не в v1.0; `POST_CHANGE` + debounce + команда полного скана (ADR-4) |
| 7 | Языки | RU в v1.0; строки через NLS, задел под `messages_en.properties` |
| 8 | Формат поставки | jar в `dropins` + README; p2 update site — задел на v1.1 |
| 9 | Мин. версия | EDT 2025.1+, Java 17 (`Bundle-RequiredExecutionEnvironment: JavaSE-17`) |

**Проверено в окружении (2026-11-06):** EDT 2025.1.5 (Eclipse 4.30) и EDT 2026.2.2 (Eclipse 4.38) установлены в `/opt/1C/1CE/components`; Eclipse RCP 2026‑09 (4.42, PDE) — `~/eclipse/rcp-2026-09`; JUnit 4.13.2 присутствует в обеих целевых платформах; StAX (`javax.xml.stream`) предоставляется JDK через `--add-modules=ALL-SYSTEM` в `1cedt.ini` (риск R2 снят, окончательная проверка — при запуске в M0).

---

## 11. Definition of Done и приёмка

### 11.1 DoD

- [ ] Все критерии M0–M6 выполнены (M7 — по согласованию).
- [ ] Unit-тесты зелёные; покрытие парсера и анализатора ≥ 80 %.
- [ ] Интеграционный чек-лист §8.3 пройден на EDT 2025.x и 2026.x.
- [ ] Perf-цели §8.4 достигнуты и зафиксированы в README.
- [ ] Нет импортов `com._1c.*`; `Require-Bundle` минимален.
- [ ] Нет блокировок UI-потока; все сканы — в job'ах; `stop()` освобождает ресурсы.
- [ ] Ошибки не «проглатываются»: логируются/видны в статусе.
- [ ] README (установка, использование, настройки, ограничения), CHANGELOG.
- [ ] Код-ревью по чек-листу §F; сборка воспроизводима.

### 11.2 Приёмка

1. Демонстрация на копии реального проекта: дубли, маркеры, view, навигация, исправление, исчезновение маркеров.
2. Демонстрация экспорта CSV/отчёта и сравнение с эталонными экспортами.
3. Замер perf на проекте ~5k файлов.
4. Установка «с нуля» на второй машине по README.

---

## 12. Roadmap после v1.0

- Quick Fix: перегенерация UUID с подтверждением (иконка в Problems).
- Headless/CI-режим и экспорт SARIF/JSON для интеграции в пайплайн.
- Интеграция в сборку (opt-in builder/nature) — если будет запрос.
- Поддержка `.cf`/`.dt` через отдельный адаптер.
- Группировка дублей по подсистемам/типам, статистика, «история» находок.
- Экспорт в формате, совместимом с задачами трекера (Jira/YouTrack).

---

## Приложение A. Точные форматы экспорта

### A.1 Текстовый отчёт

```
📊 СТАТИСТИКА POSTHANDLINGS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📁 Всего файлов:       1234
📄 XML файлов:         1100
📄 MDO файлов:         134
🔑 Всего уникальных:    5678
🔥 Дублирующихся:       2

🔥 ДУБЛИРУЮЩИЕ UUID
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

7c9e4a1b-0000-4000-8000-000000000001:
  📄 src/Catalogs/Товары/Товары.mdo
  📄 src/Catalogs/Услуги/Услуги.mdo
```

Правила: строки через `\n`; порядок — UUID по возрастанию, файлы внутри группы по возрастанию; пути относительные; в конце файла — `\n`. Если дублей нет: при включённой опции `export.reportWhenEmpty` пишется **пустой файл (0 байт)**; иначе файл не создаётся. (В v1.0 плана ошибочно указывался 1 байт — исправлено.)

### A.2 CSV

```
UUID;Файл
7c9e4a1b-0000-4000-8000-000000000001;src/Catalogs/Товары/Товары.mdo
7c9e4a1b-0000-4000-8000-000000000001;src/Catalogs/Услуги/Услуги.mdo
```

Правила: UTF-8 без BOM (опция `export.csvBom`); `;` (настраивается); заголовок `UUID;Файл`; сортировка UUID, затем путь; **одна строка на пару (uuid, файл)**. Правила CSV-экранирования (`"` вокруг поля с разделителем/кавычкой) — стандартные (RFC 4180); для типовых путей кавычки не появляются. Расширенный режим (опция) добавляет `Строка;Элемент` после `Файл`.

### A.3 Имена файлов по умолчанию

- `uuid_report.txt` — отчёт.
- `uuid_duplicates_posthandlings.csv` — CSV (для совместимости с posthandling).

---

## Приложение B. Схема кэша индекса (state location)

```json
{
  "schemaVersion": 1,
  "pluginVersion": "1.0.0",
  "project": "МояКонфигурация",
  "generatedAt": "2026-11-03T20:56:55+04:00",
  "files": [
    {
      "path": "src/Configuration/Configuration.mdo",
      "stamp": 1730647015000,
      "size": 2048,
      "failure": null,
      "uuids": [
        { "uuid": "7c9e4a1b-0000-4000-8000-000000000001", "element": "Configuration", "line": 3 }
      ]
    }
  ]
}
```

Правила: `path` — проектно-относительный, `/`; `stamp` — `IResource.getModificationStamp()`; при несовпадении stamp/size или отсутствии файла — пересканировать; `schemaVersion` не совпал — полный скан и перезапись; запись атомарная, с throttle.

---

## Приложение C. Соответствие архитектурному документу

| Раздел архитектуры | Где в плане | Комментарий |
|---|---|---|
| §1 Платформа, RCP/OSGi | §3, §6 | без изменений |
| §2 Общая схема | §3.1 | добавлены кэш и планировщик |
| §3 Структура бандла | §4 | добавлены `export/`, `PersistedIndexStore`, тесты |
| §4 Сканер StAX | §5.2–5.3 | вынесен чистый парсер, защита от XXE |
| §5 Индекс | §5.4 | добавлены stamp/size, snapshot, инкрементальность |
| §6 Триггеры | §5.8, §7 M5, ADR-4 | builder заменён на listener+job (обосновано) |
| §7 Маркеры и view | §5.7, §5.9 | уточнены лимиты, навигация, form-editor |
| §8 Жизненный цикл | §5.4–5.5, §5.8 | кэш, внутрифайловые повторы, исключения |
| §9 Точки расширения | §6.1 | полный `plugin.xml` |
| §10 Производительность | §8.4, §9 | добавлены измеримые цели |
| §11 Развёртывание | §6.6, M7 | PDE + опционально Tycho |
| §12 Риски | §10 | расширены рисками платформы и UI |
| §13 Итог | §0, §7 | MVP = M0–M3 |

---

## Приложение D. Быстрый старт разработчика

1. Установить Eclipse IDE for RCP and RAP Developers (версия базовой Eclipse — как у целевого EDT).
2. Открыть репозиторий, импортировать проекты `ru.citcer.uuidcheck`, `ru.citcer.uuidcheck.tests`, target.
3. Активировать `.target` (Window → Preferences → Plug-in Development → Target Platform).
4. Запустить `Run As → Eclipse Application` с аргументами `-consoleLog -data ${workspace_loc}/../runtime-uuidcheck`.
5. Создать/импортировать тестовый EDT-проект с `.mdo`.
6. Проверить: контекстное меню проекта → «Проверить дубликаты UUID»; view «Дубли UUID»; Problems.
7. Для проверки в реальном EDT: `Export → Deployable plug-ins and fragments` → jar в `<EDT>/dropins/` → рестарт.

---

## Приложение E. Проверка формата `.mdo` на живой копии

Выполнить по каталогу проекта EDT (адаптировать пути):

```bash
# 1. Какие атрибуты вообще встречаются в .mdo
rg -o --glob '*.mdo' '\b[A-Za-z_:]+=' /path/to/project/src \
  | sed 's/.*://; s/=$//' | sort | uniq -c | sort -rn | head -40

# 2. Где стоит uuid: корень или вложенные элементы
rg -n --glob '*.mdo' 'uuid\s*=' /path/to/project/src | head -20

# 3. Есть ли uuid не в атрибуте uuid (ref/main/content/typeId)
rg -n --glob '*.mdo' '\b(ref|typeId|valueTypeId|main|content)="[0-9a-fA-F-]{36}"' /path/to/project/src | head

# 4. Быстрая проверка дублей вручную
rg -o --glob '*.mdo' 'uuid\s*=\s*"[0-9a-fA-F-]{36}"' /path/to/project/src \
  | sed 's/.*uuid\s*=\s*"//; s/"//' | tr 'A-F' 'a-f' | sort | uniq -d | head

# 5. Наличие .xml с uuid
rg -n --glob '*.xml' 'uuid\s*=' /path/to/project | head
```

Что зафиксировать в итоге: точный список расширений, реальные имена атрибутов, есть ли вложенные `uuid`, есть ли `.xml` с метаданными, есть ли служебные копии (`out`, `build`).

---

## Приложение F. Чек-лист код-ревью

- [ ] Ни один метод, работающий с диском/сканом, не вызывается из UI-потока.
- [ ] Все обращения к `ProjectUuidIndex` — под блокировкой сервиса; наружу — только immutable snapshot.
- [ ] `stop()` снимает listener, останавливает scheduler, сохраняет/закрывает кэш.
- [ ] Маркеры создаются/удаляются только через `IWorkspace.run`; нет утечки маркеров после закрытия проекта.
- [ ] Парсер не падает на битом/пустом/огромном файле; нет XXE.
- [ ] Настройки читаются с дефолтами; отсутствие ключа не даёт NPE.
- [ ] Все пути — проектно-относительные в кэше/экспорте; абсолютные — только в сообщениях.
- [ ] Нет `System.out`; всё — через `Platform.getLog`.
- [ ] Нет импортов `com._1c.*`; `Require-Bundle` не расширялся без необходимости.
- [ ] Экспорт CSV/отчёта не меняет состояние индекса, работает на snapshot.
- [ ] NLS-строки вынесены; эмодзи — `\u`-escape.

---

## Приложение G. Отклонения и уточнения, выявленные при реализации (M0–M2)

Зафиксированы по факту проверки на реальном проекте 2026-11-06.

| № | Что | План | Эталонное поведение | Решение в коде |
|---|---|---|---|---|
| G1 | Пустой отчёт | файл из одного `\n` (1 байт) | 0 байт | `TextReportExporter` пишет 0 байт |
| G2 | «Всего уникальных» в отчёте | не расшифровано | число **уникальных UUID**, не файлов | `IndexSnapshot.uniqueUuidCount()` |
| G3 | «Дублирующихся» | число групп | число UUID-дублей | считается по CROSS_FILE-группам |
| G4 | Перевод строки в CSV | «кроме `\r\n`-нормализации» | `\r\n` | `CsvExporter` пишет `\r\n` (побайтовое совпадение) |
| G5 | Пустой файл | 0, ok | 0, ok | StAX-ошибка «Premature end» перехвачена → `ok`, 0 вхождений |
| G6 | Схема кэша | один проект в JSON | — | расширена до массива `projects` (несколько проектов workspace), `schemaVersion=1` |
| G7 | Пути в отчёте/CSV | проектно-относительные | относительные от корня сканирования | `IPath`, разделитель `/`, сортировка по строке |
| G8 | View | колонки без проекта | — | добавлена колонка «Проект» (view показывает все проиндексированные проекты) |
| G9 | Тип маркера | пример с `com.example` | — | фактический id — `ru.citcer.uuidcheck.duplicateUuidMarker` |
| G10 | Статистика XML/MDO | — | считаются все найденные файлы, включая неразобранные | считается по расширениям в `IndexSnapshot` |

**Проверка экспорта (2026-11-06):** на реальном проекте (9 `.mdo`, 17 уникальных UUID,
2 межфайловых дубля) headless-прогон дал CSV и текстовый отчёт, **побайтово совпадающие**
с эталонными экспортами (`diff` пуст). JUnit-тесты парсера и экспорта: 17 тестов, все зелёные.

---

## 13. Статус реализации (2026-11-06)

| Этап | Статус | Чем подтверждено |
|---|---|---|
| M0. Каркас и среда | ✅ выполнен | бандл `ru.citcer.uuidcheck` собран и **активируется в реальном Eclipse 4.42** (headless-диагностика: `state=32 ACTIVE`); target-определения для EDT 2025.1/2026.2 созданы |
| M1. Парсер и фикстуры | ✅ выполнен | 14 unit-тестов парсера (simple/nested/uppercase/single-quotes/spaces/ref/comment/text/myuuid/malformed/BOM/cp1251/empty/big) — зелёные |
| M2. Индекс, анализ, кэш, экспорт | ✅ выполнен | эталонные экспорты на реальном проекте: CSV и текстовый отчёт **побайтово совпадают**; 3 регрессионных теста экспорта; кэш JSON schema 1 |
| M3. Маркеры | ⚙️ реализован | создание/удаление, лимит, сводный маркер, severity из настроек; проверка в GUI — за пользователем |
| M4. View | ⚙️ реализован | таблица, сортировка, фильтр, навигация, копирование UUID, экспорт из view; GUI-проверка — за пользователем |
| M5. Автообновление и кэш | ⚙️ реализован | listener + debounce + WorkspaceJob, проектные настройки, инкрементальный скан по stamp/size; headless-скан проекта подтверждён |
| M6. Полировка | ⚙️ реализован | preference/property pages, NLS (RU), логирование, README/CHANGELOG/LICENSE |
| M7. Поставка | ⏳ не начат | нужен GUI-прогон на EDT 2025.1 и 2026.2 и экспорт jar |

**E2E-проверка (headless, реальный Eclipse 4.42 + Java 25):**
`-application ru.citcer.uuidcheck.diag.app` на проекте из 9 `.mdo` дал
`files=9 unique=17 failed=0 groups=2` → `RESULT=PASS` (exit 0).

**Команды:** `./build.sh` — jar; `./test.sh` — JUnit-тесты (17 шт.).

**Осталось до приёмки:** запуск GUI (`Run As → Eclipse Application` с target EDT),
проверка маркеров/навигации/view в Problems, прогон чек-листа §8.3 на EDT 2025.1 и 2026.2.
