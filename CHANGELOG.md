# Changelog

Все значимые изменения проекта задокументированы здесь.

Формат основан на [Keep a Changelog](https://keepachangelog.com/ru/1.0.0/),
версионирование — [Semantic Versioning](https://semver.org/lang/ru/).

## [Unreleased]

### Планируется
- Фаза 2: поддержка видео (Media3 ExoPlayer).
- Фаза 3: расширенное управление пультом и OSD-оверлей.
- Фаза 4: источник Яндекс.Диск (OAuth device-code, кэш).
- Фаза 5: экран настроек, R8, релизная подпись.

## [0.1.0] — 2026-10-09

### Добавлено
- Каркас Android TV приложения (Kotlin, Leanback, minSdk 28, sideload-подпись).
- Выбор локальной папки через SAF (`ACTION_OPEN_DOCUMENT_TREE`) с персистентным разрешением.
- Листинг и фильтрация изображений (`LocalFolderSource`).
- Движок слайдшоу (`SlideshowEngine`): таймер, цикл, перемотка, скорость (0.25×–4×).
- Режимы показа: **Ken Burns** (`KenBurnsView`) и **Классика**.
- Кроссфейд-переходы между слайдами (`SlideshowView`).
- Управление с пульта: LEFT/RIGHT — слайды, UP/DOWN — скорость, OK — пауза, BACK — выход.
- Декодирование изображений через `ImageDecoder` (EXIF, защита от OOM).
- План проекта (`PLAN.md`).
