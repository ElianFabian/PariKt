// Node-only imports in Emscripten's universal module are unreachable in browsers.
config.resolve = config.resolve || {};
config.resolve.fallback = { ...config.resolve.fallback, module: false, fs: false, path: false, url: false };
