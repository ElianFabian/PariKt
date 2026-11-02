import createEngine from './parikt-engine.mjs';

// Each factory call owns one independent Wasm memory and PARI global state.
export async function createPari(options = {}) {
    const engine = await createEngine(options);
    const call = (name, result, types, args) => engine.ccall(name, result, types, args);
    let initialized = false;
    const requireReady = () => {
        if (!initialized) throw new Error('Engine not initialized. Call init() first.');
    };
    return {
        init(stackBytes = 4_000_000) {
            if (!Number.isInteger(stackBytes) || stackBytes < 1 || stackBytes > 0x7fffffff) {
                throw new RangeError('PARI stack size must be an integer between 1 and 2147483647 bytes.');
            }
            if (!call('parikt_web_init', 'number', ['number'], [stackBytes])) {
                throw new Error('Unable to initialize PARI and its virtual temporary directory.');
            }
            initialized = true;
        },
        eval(command) {
            requireReady();
            if (typeof command !== 'string' || command.includes('\0')) {
                throw new TypeError('GP command must be a string without NUL characters.');
            }
            return call('parikt_web_eval', 'string', ['string'], [command]);
        },
        getSupportedCommands() {
            requireReady();
            const count = call('parikt_web_command_count', 'number', [], []);
            if (count < 0) throw new Error('Unable to enumerate GP commands.');
            return Array.from({ length: count }, (_, i) =>
                call('parikt_web_command', 'string', ['number'], [i]));
        },
        getVersionCode() { return call('parikt_web_version', 'number', [], []); },
        close() {
            call('parikt_web_close', null, [], []);
            initialized = false;
        },
    };
}

export default createPari;
