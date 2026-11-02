#include "bridge.h"
#include <stddef.h>
#include <pthread.h>
#include <pari/pari.h>
#include "paridroid.h"

static int initialized;
static pthread_t owner;
static const char *commands[16384];
static int command_count;

int32_t parikt_native_is_owner_thread(void) {
    /* Coroutine dispatchers can move between OS threads; PARI's stack state cannot. */
    return !initialized || pthread_equal(owner, pthread_self());
}

int32_t parikt_native_init(int64_t stack_bytes) {
    if (initialized) return parikt_native_is_owner_thread();
    if (stack_bytes <= 0 || (sizeof(long) < sizeof(int64_t) && stack_bytes > INT32_MAX)) return 0;
    paridroid_init((long) stack_bytes);
    owner = pthread_self();
    initialized = 1;
    return 1;
}

int32_t parikt_native_set_tmpdir(const char *path) {
    return parikt_native_is_owner_thread() && paridroid_set_tmpdir(path);
}

const char *parikt_native_eval(const char *input) {
    char *output = NULL;
    if (!initialized || !parikt_native_is_owner_thread() || !input) return NULL;
    /* The existing wrapper reads the last byte; it cannot accept an empty string. */
    if (!input[0]) return "";
    paridroid_eval(input, &output);
    return output;
}

int32_t parikt_native_command_count(void) {
    const char **buffer = commands;
    if (!initialized || !parikt_native_is_owner_thread()) return -1;
    paridroid_getcommands(&buffer, 16384, &command_count);
    return command_count == 16384 ? -1 : command_count;
}

const char *parikt_native_command(int32_t index) {
    return initialized && parikt_native_is_owner_thread() && index >= 0 && index < command_count
        ? commands[index] : NULL;
}

int32_t parikt_native_version(void) { return PARI_VERSION_CODE; }

void parikt_native_close(void) {
    if (!initialized || !parikt_native_is_owner_thread()) return;
    paridroid_close();
    initialized = 0;
    command_count = 0;
}
