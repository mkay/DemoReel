/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * The handful of answers LAME's own configure script would have worked out, written down instead.
 *
 * LAME ships an autoconf build; the NDK builds with CMake and never runs it, so this stands in for
 * the generated config.h. Everything here is true of every Android ABI this app is built for
 * (arm64-v8a, armeabi-v7a, x86_64 — all little-endian, all with a C99 library), which is what makes
 * one fixed file honest rather than a guess.
 *
 * Deliberately minimal: the decoder (HAVE_MPGLIB), the analysis frontend and the vector/i386
 * assembly are all left out, because this app only ever encodes 16-bit PCM to MP3.
 */

#ifndef LAME_ANDROID_CONFIG_H
#define LAME_ANDROID_CONFIG_H

#define STDC_HEADERS 1
#define HAVE_LIMITS_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_MEMORY_H 1
#define HAVE_UNISTD_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_LINUX_SOUNDCARD_H 0

#define HAVE_STRCHR 1
#define HAVE_MEMCPY 1

/*
 * Left undefined rather than defined to 0: LAME tests these with #ifdef, so a "0" would turn the
 * SSE paths on and hand an x86 intrinsic to an ARM compiler.
 */
#undef HAVE_XMM_INTRIN
#undef HAVE_XMMINTRIN_H
#undef HAVE_NASM

/* Sizes are the same on every ABI here except the pointer, which LAME does not ask about. */
#define SIZEOF_SHORT 2
#define SIZEOF_INT 4
#define SIZEOF_LONG 4
#define SIZEOF_LONG_LONG 8
#define SIZEOF_FLOAT 4
#define SIZEOF_DOUBLE 8

#define HAVE_IEEE754_FLOAT32_T 1
#define HAVE_IEEE754_FLOAT64_T 1
typedef float ieee754_float32_t;
typedef double ieee754_float64_t;
typedef long double ieee854_float80_t;

/* Little-endian throughout; no ABI Android ships for is anything else. */
#undef WORDS_BIGENDIAN

#define PACKAGE "lame"
#define VERSION "3.100"
#define URL "https://lame.sourceforge.io/"

#endif /* LAME_ANDROID_CONFIG_H */
