/**
 *-----------------------------------------------
 * paridroid.h - PARI/GP wrapper for multiplatform.
 *-----------------------------------------------
 *
 * Copyright (C) 2011 Charles Boyd
 *               2014, 2015, 2019, 2020, 2021, 2023, 2024 Andreas Enge <andreas.enge@inria.fr>
 *               2015 Bill Allombert <bill.allombert@math.u-bordeaux.fr>
 *
 * This file is part of PariKt.
 *
 * PariKt is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PariKt is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

#ifndef _PARIDROID_H
#define _PARIDROID_H

// Platform-specific logging
#if defined(__ANDROID__)

#include <android/log.h>

#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "libparidroid", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "libparidroid", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "libparidroid",  __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "libparidroid", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "libparidroid", __VA_ARGS__)
#else
#include <stdio.h>
#define LOGV(...) fprintf(stderr, __VA_ARGS__)
#define LOGD(...) fprintf(stderr, __VA_ARGS__)
#define LOGI(...) fprintf(stderr, __VA_ARGS__)
#define LOGW(...) fprintf(stderr, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

void paridroid_init(long parisize);

int paridroid_set_tmpdir(const char *path);

void paridroid_eval(const char *in, char **out);

void paridroid_close(void);

void paridroid_getcommands(const char ***commands, int size, int *len);

void paridroid_read(const int file, char **out);

#endif /*_PARIDROID_H */
