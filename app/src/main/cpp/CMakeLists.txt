# SPDX-License-Identifier: GPL-3.0-only
#
# The one native piece in this app: an MP3 encoder.
#
# Android decodes MP3 everywhere and encodes it nowhere — MediaCodec offers AAC and AMR and that is
# the list — so writing an .mp3 means carrying an encoder. LAME is vendored under lame/ (see the
# README there) and built as a static library, with our own JNI glue in mp3.c linked on top of it.

cmake_minimum_required(VERSION 3.22.1)
project(demoreel_mp3 C)

# vector/ is here only because fft.c includes its header unconditionally; the SSE code inside it is
# behind HAVE_XMMINTRIN_H, which config.h leaves undefined, so on every ABI built here it compiles
# to an empty object.
file(GLOB LAME_SOURCES
        "${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame/*.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame/vector/*.c")

add_library(mp3lame STATIC ${LAME_SOURCES})

target_include_directories(mp3lame PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}/lame"
        "${CMAKE_CURRENT_SOURCE_DIR}/lame/include"
        "${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame")

target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H STDC_HEADERS)

# Upstream's warnings are upstream's business: this tree is vendored unmodified, and a wall of them
# on every build would bury anything our own code has to say.
target_compile_options(mp3lame PRIVATE -w -O3 -ffast-math -fPIC)

add_library(demoreel_mp3 SHARED mp3.c)
target_link_libraries(demoreel_mp3 mp3lame)
