/**
 *------------------------------------------------------
 * PariNative.c - JNI Callbacks from libparidroid.
 *------------------------------------------------------
 * Copyright (C) 2011 Charles Boyd
 * Copyright (C) 2014, 2019, 2020, 2021 Andreas Enge <andreas.enge@inria.fr>
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
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

#include <stdlib.h>
#include <limits.h>
#include <string.h>
#include <jni.h>
#include <pari/paricfg.h>
#include "paridroid.h"

jint
Java_com_elianfabian_parikt_PariNative_pariVersionCode(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return (jint) PARI_VERSION_CODE;
}

void
Java_com_elianfabian_parikt_PariNative_nativeInit(JNIEnv *env, jclass cls, jlong parisize) {
    (void) cls;
    if (parisize <= 0 || parisize > LONG_MAX) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (error) (*env)->ThrowNew(env, error, "PARI stack size must be positive and fit a native long.");
        return;
    }
    paridroid_init((long) parisize);
}

jboolean
Java_com_elianfabian_parikt_PariNative_nativeSetTemporaryDirectory(JNIEnv *env, jclass cls,
                                                                   jstring path) {
    const char *tmpdir;
    int succeeded;
    (void) cls;

    tmpdir = (*env)->GetStringUTFChars(env, path, NULL);
    if (tmpdir == NULL) return JNI_FALSE;
    succeeded = paridroid_set_tmpdir(tmpdir);
    (*env)->ReleaseStringUTFChars(env, path, tmpdir);
    return succeeded ? JNI_TRUE : JNI_FALSE;
}

void
Java_com_elianfabian_parikt_PariNative_paridroidClose(JNIEnv *env, jclass cls) {
    paridroid_close();
}

jstring
Java_com_elianfabian_parikt_PariNative_eval(JNIEnv *env, jclass cls, jstring input) {
    const char *cmd;
    char *output;
    jstring res;

    // Turn the jstring into a C string.
    cmd = (*env)->GetStringUTFChars(env, input, NULL);

    // Check if JVM has already thrown out of memory error.
    if (cmd == NULL) {
        return NULL;
    }

    // Evaluate the command; returnsgnil is 1 or 0 depending on whether or not
    // the return value of the command was gnil.
    paridroid_eval(cmd, &output);

    // Free the Java objects we no longer need.
    (*env)->ReleaseStringUTFChars(env, input, cmd);

    // Encode the native string into a jstring object.
    res = (*env)->NewStringUTF(env, output);

    return res;
}

jobjectArray
Java_com_elianfabian_parikt_PariNative_getCommands(JNIEnv *env, jclass cls) {
    /* returns the list of supported gp commands */
    const char **commands = NULL;
    int len, actual, i;
    jobjectArray res = NULL;
    jclass stringClass;

    (void) cls;
    paridroid_getcommands(NULL, 0, &len);
    if (len < 0) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (error) (*env)->ThrowNew(env, error, "Unable to enumerate PARI commands.");
        return NULL;
    }
    if (len) {
        commands = (const char **) malloc((size_t) len * sizeof(*commands));
        if (!commands) {
            jclass error = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
            if (error) (*env)->ThrowNew(env, error, "Unable to allocate the PARI command list.");
            return NULL;
        }
        paridroid_getcommands(&commands, len, &actual);
        if (actual != len) {
            jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
            if (error) (*env)->ThrowNew(env, error, "PARI command list changed during enumeration.");
            free(commands);
            return NULL;
        }
    }
    stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) { free(commands); return NULL; }
    res = (*env)->NewObjectArray(env, (jsize) len,
                                 stringClass,
                                 NULL);
    (*env)->DeleteLocalRef(env, stringClass);
    if (!res) { free(commands); return NULL; }
    for (i = 0; i < len; i++) {
        jstring s = (*env)->NewStringUTF(env, commands[i]);
        if (!s) { free(commands); return NULL; }
        (*env)->SetObjectArrayElement(env, res, i, s);
        (*env)->DeleteLocalRef(env, s);
        if ((*env)->ExceptionCheck(env)) { free(commands); return NULL; }
    }
    free(commands);

    return res;
}
