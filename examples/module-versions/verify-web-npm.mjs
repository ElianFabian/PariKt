import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync, realpathSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { createHash } from 'node:crypto';

const [buildDirectory, webpackVersion, ...apps] = process.argv.slice(2);
const json = file => JSON.parse(readFileSync(file, 'utf8'));
const hash = file => createHash('sha256').update(readFileSync(file)).digest('hex');
for (const target of ['js', 'wasm']) {
  const root = resolve(buildDirectory, target);
  const workspace = json(join(root, 'package.json'));
  const packages = workspace.workspaces.map(path => {
    const file = resolve(root, path, 'package.json');
    return { file, value: json(file) };
  });
  for (const selection of apps) {
    const [app, version] = selection.split('=');
    const candidates = packages.filter(({ value }) => value.name.startsWith(`${workspace.name}-${app}`) && !value.name.endsWith('-test'));
    assert.equal(candidates.length, 1, `Expected one ${app} ${target} workspace`);
    const { file, value } = candidates[0];
    const id = '@parikt/engine-' + version.toLowerCase().replaceAll('.', '-');
    assert.deepEqual(Object.keys(value.dependencies).filter(name => name.startsWith('@parikt/engine-')), [id]);
    const requireApp = createRequire(file);
    if (target === 'js') {
      const cli = createRequire(requireApp.resolve('webpack-cli/package.json'));
      const cliWebpack = realpathSync(cli.resolve('webpack'));
      assert.equal(json(cli.resolve('webpack/package.json')).version, webpackVersion);
      assert.equal(realpathSync(requireApp.resolve('webpack')), cliWebpack, `${app} uses a different webpack instance`);
    }
    const module = realpathSync(requireApp.resolve(id));
    const expected = realpathSync(resolve(buildDirectory, 'parikt/web-engines', version, 'parikt.mjs'));
    assert.equal(module, expected, `${app} resolved another engine location`);
    assert.equal(hash(resolve(module, '../parikt-engine.wasm')), hash(resolve(buildDirectory, 'parikt/web-engines', version, 'parikt-engine.wasm')));
    console.log(`PASS ${app} ${target} npm: one ${id} dependency, canonical engine path/hash`);
  }
}
