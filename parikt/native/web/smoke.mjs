// Direct engine check, independent of Kotlin's JS/Wasm compiler and bundler.
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const directory = process.argv[2];
assert.ok(directory, 'Usage: node smoke.mjs <generated-web-directory> [expected-version]');
const { default: createPari } = await import(pathToFileURL(path.resolve(directory, 'parikt.mjs')));
const pari = await createPari();
assert.throws(() => pari.eval('2+2'), /not initialized/);
assert.throws(() => pari.init(0), RangeError);
assert.throws(() => pari.init(2 ** 31), RangeError);
assert.throws(() => pari.init(1.5), RangeError);
pari.init();
try {
    const code = pari.getVersionCode();
    const version = `${code >>> 16}.${(code >>> 8) & 255}.${code & 255}`;
    if (process.argv[3]) assert.equal(version, process.argv[3]);
    console.log(`PARI ${version}`);
    assert.match(pari.eval('2+2'), /(?:=\s*|^)4\s*$/);
    assert.match(pari.eval('factor(123456)==[2,6;3,1;643,1]'), /1\s*$/);
    pari.eval('parikt_web_test_value=41;');
    assert.match(pari.eval('parikt_web_test_value+1'), /42\s*$/);
    assert.equal(pari.eval(''), '');
    const error = pari.eval('1/0');
    assert.match(error, /division|inverse|zero/i);
    assert.match(pari.eval('2+2'), /4\s*$/);
    assert.ok(pari.getSupportedCommands().includes('factor'));
    // New modules must have independent PARI global state.
    const second = await createPari();
    second.init();
    try { assert.match(second.eval('type(parikt_web_test_value)'), /t_POL/); }
    finally { second.close(); }
    console.log('PASS: arithmetic, factors, state, error recovery, commands, instance isolation');
} finally { pari.close(); }
pari.close();
assert.throws(() => pari.eval('2+2'), /not initialized/);
console.log('PASS: close and readiness guards');
