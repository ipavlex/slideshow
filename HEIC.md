# HEIC / HEIF — изыскания и план

Статус: **реализовано (0.9.0)** — ветка B: сборка libheif+libde265 из вендоренных исходников через NDK.
Дата изысканий: 2026-10-10. Решение принято: подключаем **`libheif`**.

## 1. Что уже есть в приложении

HEIC/HEIF **распознаётся**, но декодирование идёт через системный декодер:

- Расширения `heic`/`heif` уже в списках:
  - `data/source/MediaFileTypes.kt` (`IMAGE_EXTENSIONS`)
  - `data/source/LocalFolderSource.kt` (свой дублирующий набор + MIME `image/*`)
  - `data/source/YandexDiskSource.kt` (по MIME `image/*`)
- Декодирование:
  - `playback/BitmapLoader.kt` → `ImageDecoder.createSource(resolver, uri)` (полный экран)
  - `playback/Thumbnails.kt` → `ImageDecoder.createSource(file)` (миниатюры)
  - `ImageDecoder` — это **системный HEIF-декодер** (доступен с API 28).

## 2. Симптом на устройстве

Пользователь подтвердил: **пустая миниатюра / слайд пропускается**. Значит на устройстве
нет системного HEIF/HEVC-декодера → `ImageDecoder` возвращает `null`. Аппаратный баг в коде
не подтверждён — это платформенное ограничение.

## 3. Что такое HEIC (важно для реализации)

- Контейнер **HEIF** (ISOBMFF) с кодеком **HEVC (H.265)**.
- iPhone хранит фото как **grid из тайлов 512×512** (grid — derived image item, ссылается на тайлы
  через `iref`/`dimg`), плюс `ispe` (размеры), `hvcC` (config HEVC), `pixi` и др.
- Бренды у реальных файлов пользователя: major `heic`, compatible `mif1`, `MiHE`.

## 4. Проверенные варианты библиотек (итог — ничего готового)

- **Coil 3** (`io.coil-kt.coil3`): артефакта `coil-heif` **нет** (проверено по Maven и репозиторию).
- **Coil 2** (`io.coil-kt`): `coil-heif` **не существует** (Maven `numFound=0`; в репо 2.7.0 модуля нет).
- **Sketch 4** (`io.github.panpf.sketch4`): единственный HEIF-модуль — `sketch-animated-heif`,
  и он **поверх `ImageDecoder`** (класс `ImageDecoderAnimatedHeifDecoder`) → системный предел, не помогает.
- **awxkee** (`io.github.awxkee`): `avif-coder:3.0.0` — только AV1 (`libcoder.so` + `libdav1d.so`),
  `jxl-coder` есть; `heif-coder`/`gif-coder` **не существуют**.
- **ffmpeg-kit** (`com.arthenica:*`): **удалён с Maven Central** (проверено: "no versions available").
  Нашёлся только через зеркало (Aliyun/Huawei). JitPack-артефакт `com.github.arthenica:ffmpeg-kit:5.1` —
  **только Java-классы, без нативных `.so`**. `ffmpeg-kit-min:6.0-2` (через зеркало) содержит нативы
  для 4 ABI, но это **FFmpeg 6.0**.
- **libheif**: готового Maven/JitPack-артефакта **нет** (проверены `com.github.strukturag:libheif`,
  `com.github.libheif:libheif`, `com.github.nokiatech:heif`, `io.github.awxkee:heif-coder` — все FAILED).
- **bytedeco** (`org.bytedeco:ffmpeg`): нативы для Android есть, включая `:8.1.2-1.5.14:android-arm64`,
  но это JavaCPP (интеграция на Android рискованная/сложная).

## 5. Эмпирические тесты FFmpeg (ключевой результат)

Тестировал на **реальных HEIC пользователя** через bytedeco+JavaCPP на JVM (тот же майор FFmpeg):

- **FFmpeg 6.1.1** (и, значит, ffmpeg-kit 6.0): HEIC **не открывает** →
  `moov atom not found` → `Invalid data found when processing input`. **Не подходит.**
- **FFmpeg 8.1.2**: HEIC **открывает**, но отдаёт как **61 отдельный поток HEVC 512×512**
  (`codec_id=173`, `extradata_size=104`), без склейки grid. Готовой картинки не даёт — grid
  всё равно пришлось бы собирать вручную.

Вывод: FFmpeg (даже 8.x) — неудобный инструмент для HEIC; правильный — **`libheif`** (сам собирает grid).

## 6. Решение и план

Подключаем `libheif` (лицензия LGPL, ~1–3 МБ) как нативный AAR.

Ветка A (выбранная пользователем): **готовый `libheif` AAR** кладётся в `app/libs/`.

Шаги:
1. Положить AAR в `app/libs/` (папка уже создана).
2. Распаковать AAR: проверить ABIs (`arm64-v8a`, желательно `armeabi-v7a`) и **API обёртки**
   (JNI-класс с методом декодирования в `Bitmap`). Если в AAR только «сырые» `libheif.so`/`libde265.so`
   без JNI-моста — понадобится NDK, чтобы написать свой мост.
3. Написать `HeifDecoder` (Kotlin) в проекте.
4. Подключить как фолбэк: сначала быстрый `ImageDecoder`, если `null` и файл HEIF — программный libheif:
   - `playback/Thumbnails.kt` → `image(file)`
   - `playback/BitmapLoader.kt` → `decode(resolver, uri, w, h)`
   - детект HEIF по расширению и/или magic-байтам (`ftyp` + бренд `heic`/`heix`/`mif1`/…).
5. Собрать debug, проверить. Обновить `CHANGELOG.md` + версия.

### 6.1. Что сделано фактически (0.9.0) — ветка B

Готового AAR не существует (см. §4), поэтому реализована ветка B — сборка из исходников:

- **Вендоренные исходники**: `app/src/main/cpp/third_party/libde265` (v1.0.16) и
  `app/src/main/cpp/third_party/libheif` (v1.19.8), без `.git`/`.github`.
- **`app/src/main/cpp/CMakeLists.txt`**: статические `de265` + `heif` (все кодеки кроме
  libde265 выключены, плагины выключены), JNI-мост `heif_jni` (shared).
  Хитрость: `find_package(LIBDE265)` в libheif подсовывается наша цель через
  предустановку кэш-переменных `LIBDE265_INCLUDE_DIR`/`LIBDE265_LIBRARY`.
- **`app/src/main/cpp/heif_jni.c`**: `getSize(bytes)` и `decodeInto(bytes, bitmap)` —
  декод в RGBA (`heif_chroma_interleaved_RGBA`, irot/imir применяются) → копирование
  в `ARGB_8888` Bitmap через `AndroidBitmap_lockPixels`.
  Внимание: в libheif 1.19 `heif_context_new` переименован в `heif_context_alloc`;
  размеры картинки — `heif_image_get_primary_width/height` (не `heif_image_get_width(img, chroma)`).
- **Kotlin**: `playback/HeifDecoderNative.kt` (JNI-обёртка, ленивая загрузка библиотеки),
  `playback/HeifDecoder.kt` (детект по расширению `heic/heif/hif` + magic-байтам `ftyp`,
  декод + масштабирование).
- **Фолбэк**: `Thumbnails.decodeImage` и `BitmapLoader.decode` — сначала системный
  `ImageDecoder`, при неудаче и HEIF-магии → libheif.
- **Gradle**: `externalNativeBuild` (CMake 3.22.1), `abiFilters` arm64-v8a + armeabi-v7a.
- **Проверка на хосте**: тот же путь декодирования собран на macOS (`heiftest`) и прогнан
  на реальных HEIC пользователя — 4032×3024 и 3024×4032 (grid из тайлов iPhone) декодируются
  корректно, ориентация и цвета правильные.
- **APK**: release ~7.2 МБ (libheif_jni.so: 2.3 МБ arm64 + 1.6 МБ armv7, стрипнуто).
- Осталось: проверить на устройстве (эмулятор/TV без системного HEIF).

Ветка B (альтернатива): собрать libheif+libde265 из исходников через NDK (`externalNativeBuild` + CMake,
`FetchContent` тянет исходники при сборке). Нужна сеть при первой сборке.
Исходники: `github.com/strukturag/libheif`, `github.com/strukturag/libde265`.

Ветка C (без внешних зависимостей): свой парсер HEIF (meta/iloc/iref/iprp/grid + hvcC) + декод
HEVC-тайлов через `MediaCodec` + сборка grid. Много кода, нужен тест на устройстве.

## 7. Изменения в репозитории (уже сделаны)

- Создана папка `app/libs/` (пустая).
- В `app/build.gradle.kts` добавлен автоподхват локальных AAR/JAR:
  `implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))`.
- Функционально поведение не изменилось (HEIC — как и раньше, только на устройствах с системным HEIF).
- Временные пробы (ffmpeg-kit, зеркала, JitPack) — откачены.

## 8. Как воспроизвести проверки

- резолв зависимостей: `./gradlew :app:dependencies --configuration debugRuntimeClasspath`
  (с добавлением координаты и `--refresh-dependencies`).
- эмпирика FFmpeg: временный JVM-проект на bytedeco (`org.bytedeco:ffmpeg:8.1.2-1.5.14`
  + `:macosx-arm64` + `org.bytedeco:javacpp:1.5.14:macosx-arm64`), `avformat_open_input` на `.heic`.
  (Проект был в temp-каталоге `.../opencode/heictest`, можно пересоздать.)

## 9. Инфра

- NDK/CMake сейчас **не установлены** (`sdkmanager` есть).
- `local.properties`: `sdk.dir=/opt/homebrew/share/android-commandlinetools`.
- Сборка: `JAVA_HOME=/opt/homebrew/opt/openjdk@17`.
