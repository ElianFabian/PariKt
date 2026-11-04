/* Web ABI for the shared PARI wrapper. Returned strings remain owned by PARI. */
#include <stddef.h>
#include <pari/pari.h>
#include "paridroid.h"

static int initialized;
static const char *commands[16384];
static int command_count;

int parikt_web_init(int stack_bytes) {
    if (initialized) return 1;
    if (stack_bytes <= 0 || !paridroid_set_tmpdir("/tmp")) return 0;
    if (!paridroid_init(stack_bytes)) return 0;
    initialized = 1;
    return 1;
}

const char *parikt_web_eval(const char *input) {
    char *output = NULL;
    if (!initialized || !input) return NULL;
    paridroid_eval(input, &output);
    return output;
}

int parikt_web_command_count(void) {
    const char **buffer = commands;
    if (!initialized) return -1;
    paridroid_getcommands(&buffer, 16384, &command_count);
    /* Report capacity exhaustion instead of silently presenting a truncated list. */
    return command_count >= 16384 ? -1 : command_count;
}

const char *parikt_web_command(int index) {
    return initialized && index >= 0 && index < command_count ? commands[index] : NULL;
}

int parikt_web_version(void) { return PARI_VERSION_CODE; }

void parikt_web_close(void) {
    if (!initialized) return;
    paridroid_close();
    initialized = 0;
    command_count = 0;
}
