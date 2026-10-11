# Архитектура — TV Слайдшоу

Справочник по архитектуре и принятым решениям проекта.

- Что умеет приложение и как им пользоваться — [README.md](README.md).
- Идеи по развитию и открытые вопросы — [BACKLOG.md](BACKLOG.md).

## 1. Цель

Приложение-слайдшоу для Android TV с двумя источниками контента:

1. **Локальная папка** — выбор через системный SAF.
2. **Яндекс.Диск** — выбор папки на удалённом хранилище.

Несколько режимов показа («Кен Бернс» и «Классика»), управление перемоткой и скоростью с пульта.

## 2. Зафиксированные решения

| Вопрос | Решение |
|---|---|
| Стек / фреймворк | Kotlin + Leanback (классические Views) |
| Тип контента | Фото + видео |
| Выбор локальной папки | SAF — `ACTION_OPEN_DOCUMENT_TREE` |
| Авторизация Я.Диск | WebDAV + Basic auth (логин + пароль приложения) |
| Min SDK | API 28 (Android 9) |
| Распространение | Sideload APK (release-сборка с подписью) |

## 3. Технологический стек

| Слой | Технология | Назначение |
|---|---|---|
| Язык | Kotlin | — |
| UI | `androidx.leanback` (Views) | Навигация по ТВ, фокус, кастомный плеер |
| Видео | `androidx.media3:media3-exoplayer` + `media3-session` | Проигрывание видео, MediaSession |
| Фото | `ImageDecoder` (`BitmapLoader`) | Декодирование с уменьшением, EXIF, защита от OOM |
| Локальный источник | SAF / `DocumentFile` | Чтение папки, персистентное разрешение |
| Я.Диск | `OkHttp` (WebDAV: PROPFIND/GET) | Листинг и скачивание файлов |
| Хранение креденшелов | `SharedPreferences` (`WebDavStore`) | Логин + пароль приложения (на будущее — EncryptedSharedPreferences) |
| Настройки | `SharedPreferences` (`SettingsStore`) | Порядок, перемешивание, длительность, рекурсия |
| Асинхронность | `Coroutines` | Загрузка/прекэш |
| Min SDK / target | 28 / 34 | |

## 4. Архитектура

Слоистая (Clean-ish) архитектура, без переусложнения:

```
ui (Leanback Activities)
        │
     domain (SlideshowEngine, PlaylistBuilder, состояние)
        │
     data (MediaSource, Repository, кэш)
        │
  platform (SAF, WebDAV Яндекс.Диска, Media3)
```

### 4.1. Пакетная структура

```
app/src/main/java/com/pzarubin/tvslideshow/
├── MainActivity.kt            # главный экран: выбор источника
├── data/
│   ├── auth/
│   │   └── WebDavStore.kt      # логин + пароль приложения (WebDAV, Basic auth)
│   ├── cache/
│   │   └── SlideCache.kt      # файловый кэш для Яндекс.Диска
│   ├── albums/
│   │   └── AlbumStore.kt      # локальный кэш данных публичных альбомов (JSON)
│   ├── settings/
│   │   ├── SettingsStore.kt   # настройки показа
│   │   └── ResumeStore.kt     # последний показанный слайд по источнику
│   └── source/
│       ├── MediaSource.kt     # интерфейс источника
│       ├── MediaItem.kt       # модель слайда (фото/видео)
│       ├── LocalFolderSource.kt   # SAF
│       ├── YandexDiskSource.kt    # WebDAV
│       ├── WebDavClient.kt      # низкоуровневый WebDAV-клиент Диска (PROPFIND/GET)
│       ├── YandexPublicAlbum.kt   # веб-галерея публичных альбомов (недок. API)
│       └── AlbumSource.kt     # источник: публичные альбомы Я.Диска
├── domain/
│   ├── SlideshowEngine.kt     # машина состояний, таймер, перемотка
│   ├── PlaylistBuilder.kt     # сортировка/перемешивание/фильтр
│   └── SlideMode.kt           # KEN_BURNS / CLASSIC
├── playback/
│   ├── SlideshowView.kt       # контейнер, оркестрация слайдов
│   │                          #  (видео — ExoPlayer + TextureView: rotation
│   │                          #  метаданные вертикальных видео применяются)
│   ├── KenBurnsView.kt        # кастомная view: pan+zoom (drawBitmap,
│   │                          #  pan клампится по запасу оси — без дёрганья краёв)
│   └── BitmapLoader.kt        # декодирование изображений
└── ui/
    ├── SlideshowActivity.kt   # полноэкранный показ
    ├── YandexLoginActivity.kt # форма входа: логин + пароль приложения
    ├── YandexBrowseActivity.kt# хаб Яндекс.Диска: «Альбомы» / «Файлы» /
    │                          # «Ссылка на альбом»; «Файлы» — браузер папок
    │                          # и фото-файлов Диска
    ├── AlbumCollectionActivity.kt # сетка альбомов из спец-папки `_albums`
    └── SettingsActivity.kt    # настройки
```

### 4.2. Ключевая абстракция — `MediaSource`

Единый интерфейс для обоих сценариев:

```kotlin
interface MediaSource {
    /** Список элементов (фото/видео) в папке. */
    suspend fun list(): List<MediaItem>

    /** Стрим/файл для показа элемента (локальный или скачанный в кэш). */
    suspend fun open(item: MediaItem): MediaHandle

    /** Локальный кэш: прогресс/готовность. */
    fun preload(item: MediaItem)
}

data class MediaItem(
    val id: String,              // стабильный id (uri/path)
    val kind: Kind,              // PHOTO | VIDEO
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedAt: Long?,
)
```

**`LocalFolderSource` (SAF):**
- Пользователь выбирает дерево через `ACTION_OPEN_DOCUMENT_TREE`.
- Сохраняем `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`.
- Листинг через `DocumentFile.fromTreeUri` (рекурсивно опционально).
- Фильтр по MIME: `image/*`, `video/*`.
- Показ напрямую по `Uri` (фото — декодер, видео — Media3). Кэш не обязателен.

**`YandexDiskSource`:**
- Вход: логин + «пароль приложения» (Basic auth, `WebDavStore`).
- Листинг: WebDAV `PROPFIND` Depth:1 на `webdav.yandex.ru` (разбор 207-multistatus).
- Загрузка: `GET` файла напрямую в локальный кэш с Authorization-заголовком.
- Обязательный локальный кэш (`SlideCache`) — стримить фото с Ken Burns по сети нельзя.

**`AlbumSource` (публичные альбомы):**
- Виртуальные альбомы недоступны через официальный REST API — используется
  внутренний API веб-галереи (`YandexPublicAlbum`): bootstrap страницы
  альбома (store-prefetch), сессионный `sk` + куки, повтор при `wrongSk`,
  постраничный `fetch-album-list`. OAuth не нужен.
- Фото отдаются готовыми preview-ссылками (xxxl), видео — скачиванием
  оригинала через `album-download-url`. Всё кэшируется в `SlideCache`.
- Два сценария: один альбом по ссылке (кнопка на главном экране, ссылка
  запоминается в `SettingsStore`) и коллекция — спец-папка `_albums` в
  корне Диска, в подпапках ID/ссылки альбомов; данные коллекции кэшируются
  в `AlbumStore` (JSON в filesDir), «Обновить» — принудительный рескан.
- Эндпоинт недокументирован: возможны капча и изменения формата — ошибки
  показываются тостом, один недоступный альбом не ломает скан коллекции.

## 5. Модель слайдшоу

### 5.1. Режимы показа

| Режим | Поведение |
|---|---|
| **Кен Бернс** | Каждое фото: медленный pan+zoom (случайное направление/угол), кадрирование с заполнением экрана, плавный кроссфейд. Видео проигрывается как обычно. |
| **Классика** | Статичное фото на весь экран (fit, без потерь), простой кроссфейд, фиксированная длительность. |

### 5.2. `SlideshowEngine` (машина состояний)

```
IDLE → LOADING → PLAYING ⇄ PAUSED → (END/LOOP)
```

- Держит плейлист + индекс текущего слайда.
- Длительность слайда = базовая / множитель скорости.
- События: `onSlideChanged`, `onStateChanged`, `onProgress`.

### 5.3. Управление с пульта

| Кнопка пульта | Действие |
|---|---|
| `DPAD_LEFT` / `DPAD_RIGHT` | Предыдущий / следующий слайд |
| `DPAD_UP` / `DPAD_DOWN` | Изменение скорости (быстрее/медленнее) |
| `DPAD_CENTER` (OK) / `MEDIA_PLAY_PAUSE` | Play / Pause |
| `MEDIA_REWIND` / `MEDIA_FAST_FORWARD` | Перемотка внутри видео (seek ±10 с) |
| `BACK` | Выход в меню |

Скорость — множитель интервала между слайдами: `0.25× / 0.5× / 1× / 1.5× / 2× / 4×`.

### 5.4. Медиа-сессия

`MediaSession` (media3-session) — корректная обработка медиа-кнопок на TV и «Now Playing» карточка. Слайдшоу позиционируется как плейлист медиа.

## 6. Построение плейлиста

`PlaylistBuilder`:
- Фильтр: только фото/видео (по MIME/расширению).
- Сортировка: по имени / по дате изменения / случайно.
- Опции: рекурсивный обход подпапок, перемешивание.
- Пропуск битых/нечитаемых файлов (graceful skip + лог).

## 7. Сборка и подпись (sideload)

- **Gradle Kotlin DSL**, один модуль `app`.
- `minSdk 28`, `targetSdk 34`, `compileSdk 34`.
- Release-подпись: собственный keystore `app/keystore/tvslideshow-release.jks`
  (параметры в `app/secrets.properties`, gitignored); без конфига — фолбэк на debug-ключ.
- `buildTypes.release { minifyEnabled true (R8) }`.
- Целевая команда: `./gradlew assembleRelease` → `app/build/outputs/apk/release/tvslideshow-<версия>.apk` (имя формируется из названия проекта и `versionName`).
- Флаг `android.leanback` + `LEANBACK_LAUNCHER` intent-filter, `android.hardware.touchscreen` not required.
- Banner для TV-лаунчера (`android:banner`).
- Ключей Яндекс.Диска в сборке нет (`buildConfig = false`): вход — по WebDAV
  с логином и паролем приложения, вводится на ТВ. В `app/secrets.properties`
  (gitignored) — только параметры релизной подписи.

## 8. Риски

| Риск | Митигация |
|---|---|
| SAF-пикер на TV неудобен | Свой браузер папок для Диска (`YandexBrowseActivity`) |
| Плавность Ken Burns на слабом GPU | Аппаратное декодирование, ограничение размера декодируемых Bitmap (`BitmapLoader`); заморозка уходящего слоя на время кроссфейда; клампинг pan по запасу оси |
| Большие видео (4K) на Mi Box | Прогрессивная загрузка, ограничение разрешения при необходимости |
| Ввод пароля приложения с пульта | Экран входа — форма с текстовыми полями; креденшелы в `SharedPreferences` (`WebDavStore`) |
| Отзыв SAF-разрешения / перезагрузка | Пере-валидация URI при старте, повторный выбор папки |
