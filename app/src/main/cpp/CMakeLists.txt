# Нативная поддержка HEIC/HEIF: libheif + libde265 (статические) + JNI-мост.
# Исходники вендорены в third_party/ (libheif v1.19.8, libde265 v1.0.16, LGPL).
cmake_minimum_required(VERSION 3.22.1)
project(tvslideshow_heif LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Статические библиотеки линкуются в shared heif_jni — нужен PIC.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- libde265 (HEVC-декодер) ---
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_DECODER ON CACHE BOOL "" FORCE)
set(ENABLE_ENCODER OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/libde265)

# --- libheif ---
set(WITH_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(WITH_GDK_PIXBUF OFF CACHE BOOL "" FORCE)
set(ENABLE_PLUGIN_LOADING OFF CACHE BOOL "" FORCE)
set(WITH_LIBSHARPYUV OFF CACHE BOOL "" FORCE)
set(WITH_UNCOMPRESSED_CODEC OFF CACHE BOOL "" FORCE)
# Кодеки: нужен только libde265 (декодирование HEVC), остальное выключено.
set(WITH_LIBDE265 ON CACHE BOOL "" FORCE)
set(WITH_LIBDE265_PLUGIN OFF CACHE BOOL "" FORCE)
set(WITH_X265 OFF CACHE BOOL "" FORCE)
set(WITH_KVAZAAR OFF CACHE BOOL "" FORCE)
set(WITH_OpenH264_DECODER OFF CACHE BOOL "" FORCE)
set(WITH_DAV1D OFF CACHE BOOL "" FORCE)
set(WITH_AOM_DECODER OFF CACHE BOOL "" FORCE)
set(WITH_AOM_ENCODER OFF CACHE BOOL "" FORCE)
set(WITH_JPEG_DECODER OFF CACHE BOOL "" FORCE)
set(WITH_JPEG_ENCODER OFF CACHE BOOL "" FORCE)
set(WITH_OpenJPEG_ENCODER OFF CACHE BOOL "" FORCE)
set(WITH_OpenJPEG_DECODER OFF CACHE BOOL "" FORCE)
set(WITH_SvtEnc OFF CACHE BOOL "" FORCE)
set(WITH_RAV1E OFF CACHE BOOL "" FORCE)
set(WITH_UVG266 OFF CACHE BOOL "" FORCE)
set(WITH_VVDEC OFF CACHE BOOL "" FORCE)
set(WITH_VVENC OFF CACHE BOOL "" FORCE)
set(WITH_FFMPEG_DECODER OFF CACHE BOOL "" FORCE)
set(WITH_OPENJPH_ENCODER OFF CACHE BOOL "" FORCE)

# Подсовываем find_package(LIBDE265) нашу цель из этого же дерева.
set(LIBDE265_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libde265 CACHE PATH "" FORCE)
set(LIBDE265_LIBRARY de265 CACHE FILEPATH "" FORCE)

add_subdirectory(third_party/libheif)

# de265-version.h генерируется в бинарной директории libde265.
target_include_directories(heif PRIVATE
    ${CMAKE_BINARY_DIR}/third_party/libde265
    ${CMAKE_BINARY_DIR}/third_party/libde265/libde265
)

# --- JNI-мост ---
add_library(heif_jni SHARED heif_jni.c)
target_include_directories(heif_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libheif/libheif/api
    ${CMAKE_BINARY_DIR}/third_party/libheif
    ${CMAKE_BINARY_DIR}/third_party/libde265
    ${CMAKE_BINARY_DIR}/third_party/libde265/libde265
)
find_library(log-lib log)
target_link_libraries(heif_jni PRIVATE heif de265 jnigraphics ${log-lib})
