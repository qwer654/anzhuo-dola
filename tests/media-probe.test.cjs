const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const probe = require('../app/src/main/assets/media-probe.js');
const prompts = require('../app/src/main/assets/prompt-tools.js');

test('only exact allowed hosts and subdomains are enabled', () => {
  assert.equal(probe.isAllowedHost('www.doubao.com'), true);
  assert.equal(probe.isAllowedHost('DOLA.COM'), true);
  assert.equal(probe.isAllowedHost('evil-dola.com'), false);
  assert.equal(probe.isAllowedHost('dola.com.attacker.test'), false);
});
test('recognized media URL extension wins over a misleading resource initiator hint', () => {
  assert.equal(probe.mediaType('https://cdn.example/poster.jpg?sig=x', 'video'), 'image');
  assert.equal(probe.mediaType('https://cdn.example/clip.mp4?sig=x', 'image'), 'video');
  assert.equal(probe.mediaType('https://cdn.example/opaque?id=1', 'video'), 'video');
});
test('video channel normalizer preserves order/signatures and rejects invalid or duplicate alternatives', () => {
  assert.deepEqual(probe.normalizeVideoChannels([
    'https://cdn.example/a.mp4?sig=a%2Fb', 'blob:https://cdn.example/id',
    'https://user:pass@cdn.example/private.mp4', 'https://cdn.example/a.mp4?sig=a%2Fb'
  ], { querySelectorAll() { return [
    { src: 'https://cdn.example/b.webm?token=x%3Dy' },
    { src: 'https://cdn.example/c.mp4' }
  ]; } }), ['https://cdn.example/a.mp4?sig=a%2Fb', 'https://cdn.example/b.webm?token=x%3Dy']);
  assert.deepEqual(probe.normalizeVideoChannels([], null), []);
});
test('srcset parser collects bounded HTTPS image candidates without changing signed queries', () => {
  assert.deepEqual(probe.parseSrcset('https://cdn.example/a.webp 1x, https://cdn.example/b?id=2&sig=a%2Fb 2x'), [
    'https://cdn.example/a.webp',
    'https://cdn.example/b?id=2&sig=a%2Fb'
  ]);
  assert.deepEqual(probe.parseSrcset('data:image/png;base64,AAAA 1x, javascript:alert(1) 2x'), []);
  assert.deepEqual(probe.parseSrcset('https://cdn.example/one.jpg, https://cdn.example/two.jpg'), [
    'https://cdn.example/one.jpg', 'https://cdn.example/two.jpg'
  ]);
  assert.deepEqual(probe.parseSrcset('x'.repeat(probe.LIMITS.bodyBytes + 1)), []);
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
test('video collector preserves a validated poster and emits a late poster update only once', () => {
  const queue = probe.createCollector();
  const video = { url:'https://cdn.example/movie.mp4?sig=a%2Fb', type:'video', source:'dom', title:'page' };
  queue.add([video]);
  assert.equal(queue.drain()[0].posterUrl, undefined);
  queue.add([{...video, posterUrl:'https://cdn.example/poster.jpg?sig=c%2Fd'}]);
  const update = queue.drain();
  assert.equal(update.length, 1);
  assert.equal(update[0].posterUrl, 'https://cdn.example/poster.jpg?sig=c%2Fd');
  queue.add([{...video, posterUrl:'https://user:pw@cdn.example/poster.jpg'}]);
  assert.deepEqual(queue.drain(), []);
  queue.add([{...video, posterUrl:'http://cdn.example/poster.jpg'}]);
  assert.deepEqual(queue.drain(), []);
});
test('video collector keeps at most two distinct validated channel URLs', () => {
  const queue = probe.createCollector();
  queue.add([{ url:'https://cdn.example/a.mp4?sig=a%2Fb', type:'video', channels:[
    'https://cdn.example/a.mp4?sig=a%2Fb', 'https://cdn.example/b.webm?sig=c%2Fd',
    'https://cdn.example/c.mp4?sig=e%2Ff'
  ] }]);
  assert.deepEqual(queue.drain()[0].channels, [
    'https://cdn.example/a.mp4?sig=a%2Fb', 'https://cdn.example/b.webm?sig=c%2Fd'
  ]);
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
test('live observer keeps fetch/XHR responses untouched while collecting readable media URLs', async () => {
  const script = fs.readFileSync(require.resolve('../app/src/main/assets/media-probe.js'), 'utf8');
  const fetchBody = JSON.stringify({ video_url: 'https://cdn.example/fetch.mp4?sig=a%2Fb' });
  const xhrBody = JSON.stringify({ image_ori_raw: { url: 'https://cdn.example/xhr.jpg?sig=c%3Dd' } });
  const response = {
    ok: true,
    status: 200,
    headers: { get(name) { return name === 'content-type' ? 'application/json' : String(fetchBody.length); } },
    clone() {
      let read = false;
      return { body: { getReader() { return { async read() {
        if (read) return { done: true };
        read = true;
        return { done: false, value: new TextEncoder().encode(fetchBody) };
      }, cancel: async () => {} }; } } };
    }
  };
  const listeners = new Map();
  let domNodes = [];
  class FakeXhr {
    constructor() { this.responseText = xhrBody; this.responseType = ''; }
    addEventListener(name, callback) { listeners.set(name, callback); }
    removeEventListener(name) { listeners.delete(name); }
    getResponseHeader(name) { return name === 'content-length' ? String(xhrBody.length) : null; }
    send() { listeners.get('loadend')?.(); return 'original-send-result'; }
  }
  const root = {
    location: { hostname: 'www.dola.com', origin: 'https://www.dola.com' },
    document: { title: 'observer fixture', readyState: 'loading', addEventListener() {}, querySelectorAll() { return domNodes; }, documentElement: null },
    setTimeout() { return 1; },
    fetch() { return Promise.resolve(response); },
    XMLHttpRequest: FakeXhr
  };
  root.top = root;
  vm.runInNewContext(script, { window: root, TextDecoder, TextEncoder, Promise, URL, Uint8Array, Map, Set });

  const fetched = await root.fetch('/fixture');
  assert.equal(fetched, response, 'fetch must return the exact original Response object');
  const xhr = new root.XMLHttpRequest();
  assert.equal(xhr.send(), 'original-send-result', 'XHR send return value must be preserved');
  assert.equal(xhr.responseText, xhrBody, 'XHR response body must remain unchanged');
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  const items = Array.from(root.__dolaAndroidDrain(), item => item.url);
  assert.deepEqual(items.sort(), [
    'https://cdn.example/fetch.mp4?sig=a%2Fb',
    'https://cdn.example/xhr.jpg?sig=c%3Dd'
  ].sort());
  assert.equal(Array.from(root.__dolaAndroidDrain()).length, 0);
  const altSource = { tagName: 'SOURCE', parentElement: { tagName: 'VIDEO' }, src: 'https://cdn.example/movie-alt.webm?sig=o%2Fp' };
  domNodes = [{ tagName: 'IMG', currentSrc: 'https://cdn.example/dom.jpg?sig=e%2Ff', src: '',
    srcset: 'https://cdn.example/dom.jpg?sig=e%2Ff 1x, https://cdn.example/dom2.jpg?sig=g%2Fh 2x' },
  { tagName: 'SOURCE', parentElement: { tagName: 'PICTURE' }, src: '',
    srcset: 'https://cdn.example/picture.avif?sig=i%2Fj 1x' },
  { tagName: 'VIDEO', currentSrc: 'https://cdn.example/movie.mp4?sig=k%2Fl', src: '', poster: 'https://cdn.example/poster.jpg?sig=m%2Fn',
    querySelectorAll() { return [altSource]; } }, altSource];
  domNodes.push({ tagName: 'IMG', currentSrc: 'data:image/png;base64,placeholder', src: '', getAttribute(name) {
    return name === 'src' ? 'https://cdn.example/dom-attribute.jpg?sig=q%2Fr' : null;
  } });
  root.__dolaAndroidScan();
  const domItems = Array.from(root.__dolaAndroidDrain());
  assert.equal(domItems.find(item => item.type === 'video').posterUrl, 'https://cdn.example/poster.jpg?sig=m%2Fn');
  assert.deepEqual(Array.from(domItems.find(item => item.type === 'video').channels), [
    'https://cdn.example/movie.mp4?sig=k%2Fl', 'https://cdn.example/movie-alt.webm?sig=o%2Fp'
  ]);
  assert.deepEqual(domItems.map(item => item.url).sort(), [
    'https://cdn.example/dom.jpg?sig=e%2Ff',
    'https://cdn.example/dom2.jpg?sig=g%2Fh',
    'https://cdn.example/picture.avif?sig=i%2Fj',
    'https://cdn.example/movie.mp4?sig=k%2Fl',
    'https://cdn.example/poster.jpg?sig=m%2Fn',
    'https://cdn.example/dom-attribute.jpg?sig=q%2Fr'
  ].sort());
});
test('iframe media relay requires a top-origin nonce challenge and rejects forged or stale replies', () => {
  const script = fs.readFileSync(require.resolve('../app/src/main/assets/media-probe.js'), 'utf8');
  let request;
  const childWindow = {};
  const frame = { isConnected: true, contentWindow: childWindow,
    getAttribute(name) { return name === 'src' ? 'https://media.dola.com/frame-fixture' : null; },
    hasAttribute() { return false; }, addEventListener() {} };
  const top = {
    location: { hostname: 'www.dola.com', origin: 'https://www.dola.com' },
    crypto: { getRandomValues(bytes) { bytes.fill(7); return bytes; } },
    document: { title: 'top', baseURI:'https://www.dola.com/', readyState: 'complete', querySelectorAll(selector) { return selector === 'iframe' ? [frame] : []; } },
    addEventListener(name, listener) { if (name === 'message') this.onMessage = listener; },
    setTimeout(callback) { callback(); return 1; }
  };
  top.top = top;
  top.postMessage = (data, targetOrigin) => { child.reply = { data, targetOrigin }; };
  vm.runInNewContext(script, { window: top, URL, Map, Set, Uint8Array, Promise });
  const image = { tagName: 'IMG', currentSrc: 'https://cdn.example/frame.jpg?id=x&sig=a%2Fb', srcset: '' };
  const child = {
    top,
    parent: top,
    addEventListener(name, listener) { if (name === 'message') this.onMessage = listener; },
    location: { hostname: 'media.dola.com', origin: 'https://media.dola.com' },
    document: { title: 'same-origin frame', readyState: 'complete', querySelectorAll() { return [image]; } },
    setTimeout(callback) { callback(); return 1; }
  };
  vm.runInNewContext(script, { window: child, URL, Map, Set, Uint8Array, Promise });
  // Fake the browser's target-origin checked WindowProxy delivery.
  childWindow.postMessage = (data, targetOrigin) => {
    child.lastRequest = { data, targetOrigin };
    child.onMessage({ origin:'https://www.dola.com', source:top, data });
  };
  // BrowserActivity's periodic scanner drives this drain entry point.
  top.__dolaAndroidDrain();
  request = child.lastRequest;
  assert.ok(request, 'top document should send a challenge');
  assert.equal(request.targetOrigin, 'https://media.dola.com');
  assert.equal(request.data.type, 'scan-request');
  assert.equal(request.data.channel, 'dola.android.media');
  assert.equal(request.data.version, 1);
  assert.equal(child.reply.targetOrigin, 'https://www.dola.com');
  assert.equal(child.reply.data.type, 'scan-response');
  top.onMessage({ origin: 'https://media.dola.com', source: childWindow, data: child.reply.data });
  assert.deepEqual(Array.from(top.__dolaAndroidDrain(), item => item.url), ['https://cdn.example/frame.jpg?id=x&sig=a%2Fb']);

  const stale = child.reply.data;
  top.onMessage({ origin:'https://evil.example', source:childWindow, data:stale });
  top.onMessage({ origin:'https://media.dola.com', source:{}, data:stale });
  top.onMessage({ origin:'https://media.dola.com', source:childWindow, data:{...stale, nonce:'forged'} });
  top.onMessage({ origin:'null', source:childWindow, data:stale });
  top.onMessage({ origin:'https://media.dola.com', source:childWindow, data:stale }); // already consumed nonce
  assert.deepEqual(Array.from(top.__dolaAndroidDrain()), []);
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
test('prompt guard is opt-in and preserves the original markers without auto-submit', () => {
  const plain = prompts.rewriteTimeline('00:00-00:05 开场', 30);
  assert.equal(plain, '全片进度 0%–16.7% 开场');
  assert.doesNotMatch(plain, /【(?:单片生成|直接生成)控制】/);
  const guarded = prompts.rewriteTimeline('00:00-00:05 开场', 30, {promptGuard:true});
  assert.match(guarded, /^【直接生成控制】/);
  assert.match(guarded, /【单片生成控制】/);
  assert.match(guarded, /全片进度 0%–16.7% 开场/);
  assert.equal((guarded.match(/【直接生成控制】/g)||[]).length, 1);
  assert.equal((guarded.match(/【单片生成控制】/g)||[]).length, 1);
  const directOnly = prompts.rewriteTimeline('16:9 雨夜街景', 30, {promptGuard:true});
  assert.match(directOnly, /^【直接生成控制】/);
  assert.doesNotMatch(directOnly, /【单片生成控制】/);
});

