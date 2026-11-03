// Emscripten's Node branches are not used in a browser; retain its real Node imports for Node runs.
config.resolve = config.resolve || {};
config.resolve.fallback = { ...config.resolve.fallback, module: false, fs: false, path: false, url: false };
