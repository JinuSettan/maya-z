const { getDefaultConfig } = require('expo/metro-config');

// Keep Metro on its default asset list. The GGUF is downloaded at runtime and
// must never be imported or bundled into JavaScript.
module.exports = getDefaultConfig(__dirname);