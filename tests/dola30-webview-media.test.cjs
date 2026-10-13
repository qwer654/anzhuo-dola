const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const adapter = require('../app/src/main/assets/dola30-webview-media-v1.6.14.js');

test('limits URLs to HTTPS without credentials/default-port violations', () => {
  assert.equal(adapter.allowedPage(new URL('https://www.dola.com/a')), true);
  assert.equal(adapter.allowedPage(new URL('https://www.dola.com./a')), false);
  assert.equal(adapter.allowedPage(new URL('https://dola.com:444/a')), false);
  assert.equal(adapter.allowedPage(new URL('https://evil-dola.com/a')), false);
  assert.equal(adapter.safeMediaUrl('https://cdn.example/a.mp4?sig=a%2Fb'), 'https://cdn.example/a.mp4?sig=a%2Fb');
  for (const value of ['', 'blob:https://dola.com/a', 'data:image/png,x', 'javascript:alert(1)', 'https://u:p@cdn.example/a.mp4', 'https://cdn.example:444/a.mp4', 'https://cdn.example/' + 'x'.repeat(8200)]) assert.equal(adapter.safeMediaUrl(value), '');
});

test('payload parsing is bounded, media-field-only, and caps state at 200', () => {
  const state = adapter.createState();
  adapter.collectPayload({ ignored_url: 'https://cdn.example/no.mp4', video_url: 'https://cdn.example/yes.mp4', nested: { image_url: 'https://cdn.example/a.jpg' } }, state);
  assert.deepEqual(state.drain().map(x => x.url), ['https://cdn.example/yes.mp4', 'https://cdn.example/a.jpg']);
  for (let i = 0; i < 250; i++) state.add({ url: `https://cdn.example/${i}.jpg`, type: 'image' });
  assert.equal(state.items.length, 200);
  assert.equal(state.drain().length, 200);
  adapter.collectText('x'.repeat(adapter.LIMITS.bodyBytes + 1), state);
  assert.equal(state.items.length, 0);
});

test('page installation has no Chrome shim, starts no requests, and preserves fetch/XHR behavior', async () => {
  const script = fs.readFileSync(require.resolve('../app/src/main/assets/dola30-webview-media-v1.6.14.js'), 'utf8');
  const events = new Map(), sent = [];
  const body = JSON.stringify({ video_url: 'https://cdn.example/chain.mp4?sig=a%2Fb' });
  const response = { headers: { get(name) { return name === 'content-length' ? String(body.length) : 'application/json'; } }, clone() { return { text: async () => body }; } };
  class FakeXhr {
    constructor() { this.responseText = body; }
    addEventListener(name, cb) { events.set(name, cb); }
    open() { return 'open-original'; }
    send() { sent.push('xhr'); events.get('loadend')?.(); return 'send-original'; }
  }
  let nodes = [{ tagName: 'VIDEO', currentSrc: 'https://cdn.example/dom.mp4', poster: 'https://cdn.example/poster.jpg' }];
  const document = { title: 'fixture', readyState: 'complete', documentElement: { getAttribute() { return null; } }, addEventListener() {}, querySelectorAll() { return nodes; } };
  const root = { location: { href: 'https://www.dola.com/chat' }, document, fetch(url) { sent.push(`fetch:${url}`); return Promise.resolve(response); }, XMLHttpRequest: FakeXhr, Promise, URL, Set, WeakMap, TextDecoder };
  vm.runInNewContext(script, { window: root, Promise, URL, Set, WeakMap, TextDecoder, module: undefined });
  assert.equal(root.chrome, undefined, 'must not install a Chrome API shim');
  assert.deepEqual(sent, [], 'installation must not initiate outgoing traffic');
  const fetched = await root.fetch('/im/chain/single');
  assert.equal(fetched, response, 'exact Response is passed through');
  const xhr = new root.XMLHttpRequest(); xhr.open('GET', '/im/chain/single'); assert.equal(xhr.send(), 'send-original');
  await new Promise(resolve => setImmediate(resolve));
  const urls = root.__dolaNewMediaState.drain().map(x => x.url).sort();
  assert.deepEqual(Array.from(urls), ['https://cdn.example/chain.mp4?sig=a%2Fb', 'https://cdn.example/dom.mp4'].sort());
  assert.equal(sent.filter(x => x.startsWith('fetch:')).length, 1, 'only caller fetch is present');
  nodes.push({ tagName: 'IMG', currentSrc: 'https://cdn.example/generated.jpg' });
  root.__dolaNewMediaState.scan();
  assert.deepEqual(Array.from(root.__dolaNewMediaState.drain(), x => x.url), ['https://cdn.example/generated.jpg']);
});

test('real streamed Response is passively decoded and overflow streams are cancelled', async () => {
  const script = fs.readFileSync(require.resolve('../app/src/main/assets/dola30-webview-media-v1.6.14.js'), 'utf8');
  const text = JSON.stringify({ image_ori_raw: { url: 'https://cdn.example/streamed.jpg?sig=x' } });
  const bytes = new TextEncoder().encode(text), pieces = [bytes.slice(0, 8), bytes.slice(8)];
  const body = new ReadableStream({ pull(controller) { const part = pieces.shift(); if (part) controller.enqueue(part); else controller.close(); } });
  const response = new Response(body, { headers: { 'content-type': 'application/json' } });
  let outgoing = 0, cancelled = false;
  // Response.clone() is tee-based, so cancelling its branch does not cancel the
  // caller's original Response.  This clone fixture verifies the observer calls
  // cancel on the bounded clone reader when a stream exceeds the cap.
  const over = {
    headers: { get() { return null; } },
    clone() {
      return { body: { getReader() {
        return {
          read: async () => ({ done: false, value: new Uint8Array(adapter.LIMITS.bodyBytes + 1) }),
          cancel: async () => { cancelled = true; }
        };
      } } };
    }
  };
  const document = { title: 'stream', documentElement: { getAttribute() { return null; } }, addEventListener() {}, querySelectorAll() { return []; } };
  const root = { location: { href: 'https://dola.com/chat' }, document, fetch() { outgoing++; return Promise.resolve(outgoing === 2 ? over : response); }, Promise, URL, Set, WeakMap, TextDecoder, setTimeout };
  vm.runInNewContext(script, { window: root, Promise, URL, Set, WeakMap, TextDecoder, ReadableStream, Uint8Array, module: undefined });
  const same = await root.fetch('/im/chain/single');
  assert.equal(same, response, 'caller receives the exact response object');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(Array.from(root.__dolaNewMediaState.drain(), x => x.url), ['https://cdn.example/streamed.jpg?sig=x']);
  await root.fetch('/im/chain/single');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(cancelled, true);
  assert.equal(outgoing, 2, 'observer issued no requests');
});

test('debounced mutation observer scans media generated after installation', () => {
  const script = fs.readFileSync(require.resolve('../app/src/main/assets/dola30-webview-media-v1.6.14.js'), 'utf8');
  let nodes = [], observer;
  class FakeObserver { constructor(callback) { this.callback = callback; observer = this; } observe() {} }
  const document = { title: 'mutation', documentElement: { getAttribute() { return null; } }, addEventListener() {}, querySelectorAll() { return nodes; } };
  const root = { location: { href: 'https://www.doubao.com/chat' }, document, MutationObserver: FakeObserver, setTimeout(callback) { callback(); return 1; }, Promise, URL, Set, WeakMap, TextDecoder };
  vm.runInNewContext(script, { window: root, Promise, URL, Set, WeakMap, TextDecoder, MutationObserver: FakeObserver, module: undefined });
  nodes = [{ tagName: 'AUDIO', currentSrc: 'https://cdn.example/later.mp3' }];
  observer.callback([]);
  assert.deepEqual(Array.from(root.__dolaNewMediaState.drain(), x => x.url), ['https://cdn.example/later.mp3']);
});

test('known media path on an untrusted origin cannot be observed', () => {
  const script = fs.readFileSync(require.resolve('../app/src/main/assets/dola30-webview-media-v1.6.14.js'), 'utf8');
  let called = 0;
  const root = { location: { href: 'https://evil-dola.com/chat' }, document: { querySelectorAll() { return []; } }, fetch() { called++; return Promise.resolve({}); }, Promise, URL, Set, WeakMap, TextDecoder };
  vm.runInNewContext(script, { window: root, Promise, URL, Set, WeakMap, TextDecoder, module: undefined });
  assert.equal(root.__dolaNewMediaState, undefined);
  root.fetch('https://evil-dola.com/im/chain/single');
  assert.equal(called, 1, 'the page call passes through and no observer performs another request');
});

test('fallback candidates are passive, bounded, drainable and nested JSON is recognized', () => {
 const state=adapter.createState();
 adapter.collectPayload({payload:JSON.stringify({fallback_api:'https://api.example.test/query?sig=a%2Bb'})},state);
 assert.deepEqual(state.drainFallbacks(),['https://api.example.test/query?sig=a%2Bb']);
 for(let i=0;i<20;i++)state.addFallback('https://api.example.test/'+i);
 assert.equal(state.fallbackApis.length,8);
 assert.equal(state.drainFallbacks().length,8);
 for(const value of ['http://api.example.test/','https://u:p@api.example.test/','https://api.example.test/#token','/relative'])assert.equal(state.addFallback(value),false);
 assert.equal(state.addFallback('https://api.example.test/0'),false);
});
