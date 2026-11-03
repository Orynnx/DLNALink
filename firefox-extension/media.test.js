const assert = require('node:assert/strict');
const {classifyMedia} = require('./media.js');
assert.equal(classifyMedia('https://example.test/video.m3u8?sig=x'), 'M3U8');
assert.equal(classifyMedia('https://example.test/asset', 'application/dash+xml; charset=utf-8'), 'DASH');
assert.equal(classifyMedia('https://example.test/asset', 'video/mp4'), 'video/mp4');
assert.equal(classifyMedia('https://example.test/article'), null);
assert.equal(classifyMedia('file:///video.mp4'), null);
assert.equal(classifyMedia('https://user:pass@example.test/video.mp4'), null);
console.log('media classifier: 6 passed');
