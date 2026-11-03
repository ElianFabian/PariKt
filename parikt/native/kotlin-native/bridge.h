#ifndef PARIKT_NATIVE_BRIDGE_H
#define PARIKT_NATIVE_BRIDGE_H

#include <stdint.h>

/* Fixed-width C ABI. Strings are borrowed and must be copied before the next call.
 * An initialized engine belongs to its initializing OS thread. */
int32_t parikt_native_init(int64_t stack_bytes);
int32_t parikt_native_is_owner_thread(void);
int32_t parikt_native_set_tmpdir(const char *path);
const char *parikt_native_default_tmpdir(void);
const char *parikt_native_eval(const char *input);
int32_t parikt_native_command_count(void);
const char *parikt_native_command(int32_t index);
int32_t parikt_native_version(void);
void parikt_native_close(void);

#endif
