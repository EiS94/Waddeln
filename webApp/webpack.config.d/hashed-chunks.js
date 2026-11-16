// The extra JavaScript files (Skia glue, date library) get a content hash in their name like the
// .wasm files, so browsers may keep them for good and still get a new one after every change.
config.output = config.output || {};
config.output.chunkFilename = '[name].[contenthash].js';
