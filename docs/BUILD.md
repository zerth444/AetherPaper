# Сборка AetherPaper

Для игры используйте готовые файлы из `dist/`. Сборка нужна при изменении кода/данных.
Требования: JDK 25, Python 3.10+, доступ к Maven Central/Paper Maven и Gradle.
В `plugin/` включён Gradle Wrapper 9.8.0; отдельная установка Gradle не нужна.

## Только Java-плагин

Все сгенерированные runtime-данные уже находятся в `plugin/src/main/resources`.
Локальный Paper-сервер для компиляции не нужен; Netty и Paper API скачиваются Maven.

```sh
cd plugin
# Windows: gradlew.bat build
sh gradlew build
```

Результат: `plugin/build/libs/Aether-1.0.0.jar`. Плагин компилируется с API
26.2.build.129-stable и имеет адаптацию загрузки достижений к рабочему build 82.
После изменения runtime-кода проверьте поведение на используемом Paper.

## Полная сборка данных и ресурспака

Оригинальный снимок, использованный для генерации, сохранён в
`vendor/the-aether-source.zip`; `tools/prepare_sources.py` проверит хеш и распакует его
в игнорируемую Git папку `aether-mod/`. Уже существующие исходники не перезаписываются.

```sh
python -m venv .venv
# Windows: .venv\Scripts\activate
# Linux/macOS: . .venv/bin/activate
python -m pip install -r tools/requirements-build.txt
python tools/prepare_sources.py
```

Потребуется установленный клиент Minecraft **26.2**: конвертер использует его
ванильные модели, шрифты и реестры. Сам клиент не запускается. Стандартные каталоги
Minecraft определяются автоматически; для нестандартной установки задайте путь.

Windows / PowerShell:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:AETHER_CLIENT_JAR = 'C:\path\to\.minecraft\versions\26.2\26.2.jar'
& tools/build_release.ps1
```

Linux/macOS:

```sh
export JAVA_HOME=/path/to/jdk-25
export AETHER_CLIENT_JAR=/path/to/.minecraft/versions/26.2/26.2.jar
python tools/build_all.py
(cd plugin && sh gradlew build)
cp plugin/build/libs/Aether-1.0.0.jar dist/Aether-1.0.0.jar
python tools/write_release_manifest.py
```

Если нужен отдельный серверный референс реестров, задайте `AETHER_SERVER_JAR`
на распакованный runtime-JAR `versions/26.2/paper-26.2.jar`. При его отсутствии
чистая сборка использует ванильные реестры из указанного клиентского JAR.

Полная сборка проверяет JSON, модели/текстуры, структуру прогрессии и полноту
страниц книги. Если меняете конвертеры, сопоставьте результаты с рабочими
артефактами и проведите локальные серверные проверки. Успешная компиляция и
статические проверки не подтверждают визуальное поведение в клиенте.

## Проверки и сохранённые отчёты

```sh
python tools/check_item_defs.py
python tools/check_pack_models.py
python tools/check_lore_gui.py
```

Последняя команда также использует распакованные результаты полной сборки в
`build/`. `tools/qa/` содержит диагностические Java-плагины и локальный протокольный
стенд прежних ревизий; их пути запуска и размещение собранных ревизий задаются
в `tools/qa/run_revision14.py`. Это диагностические исходники, а не клиентские моды.
Никогда не кладите их JAR в рабочий сервер.

Готовые отчёты находятся в `docs/verification/`. Состав r18: 144 статьи,
98 скрытых недоступных статей, 242 исходных описания переведены на русский.
