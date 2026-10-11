# TV Слайдшоу

Приложение-слайдшоу для Android TV (Xiaomi Mi Box и совместимые устройства).

Показывает фотографии и видео из локальной папки или с Яндекс.Диска с эффектами
«Кен Бёрнс» и «Классика», управляется с пульта.

## Возможности

- Выбор источника: локальная папка (SAF / `ACTION_OPEN_DOCUMENT_TREE`)
  или Яндекс.Диск — файлы, коллекция альбомов (`_albums`) или публичный
  альбом по ссылке (`disk.yandex.ru/a/…`, вводится только ID)
- Коллекции альбомов: спец-папка `_albums` в корне Диска с ID/ссылками
  альбомов — сетка с обложками, слайдшоу по альбому или по всем сразу
- Фото: режимы **Ken Burns** (медленный pan+zoom) и **Классика** (статичное фото)
- Видео: Media3 ExoPlayer, смешанный плейлист фото+видео, перемотка
- Плавные кроссфейд-переходы между слайдами
- Циклическое воспроизведение, порядок (имя / дата / случайно), перемешивание
- Возобновление: последний показанный слайд запоминается, при повторном
  запуске предлагается «Продолжить с последнего слайда» или «С начала»
- Настройки: длительность фото, рекурсия подпапок
- OSD-оверлей и MediaSession («Now Playing» на Android TV)

## Управление с пульта

| Кнопка | Действие |
|---|---|
| LEFT / RIGHT | Предыдущий / следующий слайд |
| UP / DOWN | Скорость (быстрее / медленнее) |
| OK (DPAD_CENTER) / MEDIA_PLAY_PAUSE | Пауза / воспроизведение |
| MEDIA_REWIND / MEDIA_FAST_FORWARD | Перемотка внутри видео (seek ±10 с) |
| BACK | Выход |

## Требования

- Android 9 (API 28) и выше
- Android TV / Google TV

## Сборка

### Настройка секретов

Для входа в Яндекс.Диск нужен `client_id` приложения, зарегистрированного на
https://oauth.yandex.ru/. Значение хранится в отдельном файле
`app/secrets.properties` (не входит в git) и читается на этапе сборки:

```properties
YANDEX_CLIENT_ID=ваш_client_id
YANDEX_CLIENT_SECRET=ваш_client_secret
```

Без этого файла сборка пройдёт, но авторизация Яндекс.Диска не заработает.

Секрет нужен, если приложение на oauth.yandex.ru зарегистрировано как
confidential (например, тип «Для доступа к API или отладки») — такой тип
требует `client_secret` в запросах токена. Для public/нативного приложения
ключ можно не указывать.

### Android Studio

1. Откройте папку проекта в Android Studio.
2. Создайте `app/secrets.properties` (см. выше).
3. Дождитесь синхронизации Gradle.
4. `Build → Build APK(s)` или запустите на устройстве.

### Командная строка

```bash
./gradlew assembleDebug      # отладочный APK
./gradlew assembleRelease    # release-APK (собственная подпись, см. ниже)
```

Если системной Java нет (macOS + Homebrew), путь к JDK задаётся переменной:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
```

Путь к Android SDK берётся из `local.properties` (`sdk.dir`, например
`/opt/homebrew/share/android-commandlinetools` — устанавливается через
Homebrew: `sdkmanager`, `ndk`, `cmake`, `platform-tools`).

APK: `app/build/outputs/apk/.../tvslideshow-<версия>.apk` (например `tvslideshow-0.5.0.apk`).

### Установка по adb (Android TV по сети)

На ТВ должна быть включена «Отладка по USB» / ADB по сети. adb — в
`platform-tools` SDK (при установке через Homebrew: `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`).

```bash
adb connect 192.168.0.130:5555          # подключение к ТВ по сети
adb devices                             # статус: device
adb -s 192.168.0.130:5555 install -r app/build/outputs/apk/release/tvslideshow-<версия>.apk
```

`-r` — переустановка поверх с сохранением данных (та же подпись). При смене
подписи (например, с debug-ключа на релизный keystore) нужен `adb uninstall`
перед установкой — данные приложения при этом сбрасываются.
Альтернатива: скопировать APK на устройство (USB/флешка) и установить вручную.

### Релизная подпись

Release подписывается собственным keystore `app/keystore/tvslideshow-release.jks`
(RSA 4096, срок 10000 дней). Параметры — в `app/secrets.properties` (gitignored):

```properties
RELEASE_STORE_FILE=keystore/tvslideshow-release.jks
RELEASE_STORE_PASSWORD=…
RELEASE_KEY_ALIAS=tvslideshow
RELEASE_KEY_PASSWORD=…
```

`build.gradle.kts` подключает подпись автоматически; если конфига нет
(например, у другого разработчика), release подписывается debug-ключом.
Keystore и пароли не коммитятся — храните резервную копию: потеря ключа
лишает возможности обновлять приложение поверх установленного.

## Структура проекта

```
app/src/main/java/<package>/
├── data/
│   ├── albums/       # локальный кэш данных публичных альбомов (AlbumStore)
│   ├── auth/         # OAuth Яндекс (device-code), хранение токенов
│   ├── cache/        # файловый кэш (LRU) для Яндекс.Диска
│   ├── settings/     # настройки (SharedPreferences)
│   └── source/       # источники: LocalFolderSource (SAF), YandexDiskSource,
│                     # AlbumSource (публичные альбомы, YandexPublicAlbum)
├── domain/           # SlideshowEngine, PlaylistBuilder, SlideMode
├── playback/         # KenBurnsView, SlideshowView, BitmapLoader
└── ui/               # MainActivity, SlideshowActivity, Yandex*, Album*,
                      # SettingsActivity
```
