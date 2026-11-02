// The same Emscripten module supports Node; browser execution never enters its Node branch.
config.resolve = config.resolve || {};
config.resolve.fallback = { ...config.resolve.fallback, module: false, fs: false, path: false, url: false };
