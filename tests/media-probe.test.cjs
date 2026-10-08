const test = require('node:test');
const assert = require('node:assert/strict');
const probe = require('../app/src/main/assets/media-probe.js');
const prompts = require('../app/src/main/assets/prompt-tools.js');

test('only exact allowed hosts and subdomains are enabled', () => {
  assert.equal(probe.isAllowedHost('www.doubao.com'), true);
  assert.equal(probe.isAllowedHost('DOLA.COM'), true);
  assert.equal(probe.isAllowedHost('evil-dola.com'), false);
  assert.equal(probe.isAllowedHost('dola.com.attacker.test'), false);
});
test('base64 and URL-safe base64 main_url preserve signed URL bytes', () => {
  const url = 'https://cdn.example/video?id=1&signature=a%2Fb%3D&z=9';
  for (const encoding of ['base64', 'base64url']) {
    const items = probe.parsePayload({ video_list: { main: { main_url: Buffer.from(url).toString(encoding) } } });
    assert.equal(items[0].url, url);
    assert.equal(items[0].type, 'video');
  }
});
test('nested JSON strings, arrays and original image objects', () => {
  const body = JSON.stringify({ data: JSON.stringify({ content: JSON.stringify({ image_ori_raw: { url: 'https://cdn.example/signed?id=77' }, urls: ['https://cdn.example/a.mp4'] }) }) });
  const result = probe.parseText(body, 'fetch', 'Test page');
  assert.equal(result.length, 2);
  assert.deepEqual(result[0], {url:'https://cdn.example/signed?id=77', type:'image', source:'fetch', title:'Test page'});
});
test('deduplicates exact media URL without stripping signatures', () => {
  const result = probe.parsePayload(['https://cdn.example/a.mp4?a=1', 'https://cdn.example/a.mp4?a=1', 'https://cdn.example/a.mp4?a=2']);
  assert.equal(result.length, 2);
  const queue = probe.createCollector();
  queue.add(result); assert.equal(queue.drain().length, 2); queue.add(result); assert.equal(queue.drain().length, 0);
});
test('rejects blob/data/javascript/credentials and skips fallback_api', () => {
  const result = probe.parsePayload({ video_url:'blob:https://dola.com/id', image_url:'data:image/png;base64,abc', main_url:'javascript:alert(1)', fallback_api:'https://cdn.example/a.mp4', image:'https://user:password@cdn.example/a.jpg' });
  assert.deepEqual(result, []);
});
test('oversize text and nested strings are not parsed', () => {
  assert.deepEqual(probe.parseText(' '.repeat(probe.LIMITS.bodyBytes + 1) + 'https://cdn.example/a.jpg'), []);
  assert.deepEqual(probe.parsePayload({image_url:' '.repeat(probe.LIMITS.bodyBytes + 1)}), []);
});
test('queue caps at 300 and drain empties it', () => {
  const queue = probe.createCollector();
  for (let i = 0; i < 400; i++) queue.add([{url:`https://cdn.example/${i}.jpg`, type:'image'}]);
  assert.equal(queue.snapshot().length, 300); assert.equal(queue.drain().length, 300); assert.deepEqual(queue.drain(), []);
});
test('cycles and extremely nested payloads are bounded', () => {
  const payload = {image_url:'https://cdn.example/a.jpg'}; payload.self = payload;
  assert.equal(probe.parsePayload(payload).length, 1);
  let deep = {image_url:'https://cdn.example/a.jpg'};
  for (let i=0; i<30; i++) deep = {inner:deep};
  assert.deepEqual(probe.parsePayload(deep), []);
});
test('SSE/partial response discovers only extension-bearing media URLs', () => {
  const result = probe.parseText('data: {"url":"https://cdn.example/a.mp4?sig=x"}\ndata: [DONE]');
  assert.equal(result[0].url, 'https://cdn.example/a.mp4?sig=x');
});
test('prompt timeline handles timestamps and English/Chinese seconds', () => {
  assert.equal(prompts.rewriteTimeline('00:00-00:05 开场', 20), '全片进度 0%–25% 开场');
  assert.equal(prompts.rewriteTimeline('第十五秒转场，持续半秒', 30), '全片进度约 50% 处转场，持续约占全片 1.7% 的时长');
  assert.equal(prompts.rewriteTimeline('for 2 seconds', 10), 'for 约占全片 20% 的时长');
  assert.equal(prompts.rewriteTimeline('3 to 8 seconds', 10), '全片进度 30%–80%');
  assert.equal(prompts.rewriteTimeline('三秒到八秒', 10), '全片进度 30%–80%');
  assert.equal(prompts.rewriteTimeline('二十九点五秒', 30), '全片进度约 98.3% 处');
});
test('prompt conversion validates duration and does not claim model capability', () => {
  assert.throws(() => prompts.rewriteTimeline('hello', 0), RangeError);
  assert.equal(prompts.rewriteTimeline('Seedance 普通提示词 16:9', 30), 'Seedance 普通提示词 16:9');
  assert.equal(prompts.rewriteTimeline('120秒', 30), '全片进度约 400% 处');
  assert.equal(prompts.rewriteTimeline('-5秒', 30), '-5秒');
});
