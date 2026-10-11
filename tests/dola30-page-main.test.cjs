const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assetRoot = path.resolve(__dirname, '../app/src/main/assets/dola30-user-source-v1.6.9');
const skillSource = fs.readFileSync(path.join(assetRoot, 'skill-pack-main.js'), 'utf8');
const mediaSource = fs.readFileSync(path.join(assetRoot, 'media-upload-main.js'), 'utf8');

class Element {
  constructor(tag = 'div') { this.tagName = tag.toUpperCase(); this.attributes = {}; this.dataset = {}; this.textContent = ''; this.files = []; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null; }
  removeAttribute(name) { delete this.attributes[name]; }
  dispatchEvent(event) { return this.ownerDocument?.dispatchEvent(event); }
  remove() {}
  addEventListener() {}
  click() { this.clicked = true; }
}

class DocumentStub {
  constructor() { this.documentElement = new Element('html'); this.documentElement.ownerDocument = this; this.listeners = new Map(); this.nodes = new Map(); }
  addEventListener(name, fn) { const list = this.listeners.get(name) || []; list.push(fn); this.listeners.set(name, list); }
  dispatchEvent(event) { for (const fn of this.listeners.get(event.type) || []) fn.call(this, event); return true; }
  querySelector(selector) { return this.nodes.get(selector) || null; }
  querySelectorAll() { return []; }
  setNode(selector, node) { node.ownerDocument = this; this.nodes.set(selector, node); }
}

class XHRStub {
  constructor() { this._responseText = ''; this._response = ''; this.listeners = new Map(); this.openCalls = []; }
  open(method, url) { this.openCalls.push([method, url]); this.method = method; this.url = url; }
  addEventListener(type, fn) { const list = this.listeners.get(type) || []; list.push(fn); this.listeners.set(type, list); }
  emit(type) { for (const fn of this.listeners.get(type) || []) fn.call(this, { type, target: this }); }
  get responseText() { return this._responseText; }
  get response() { return this._response; }
}

function makeContext() {
  const document = new DocumentStub();
  const calls = [];
  class IsolatedXHR {
    constructor() { this._responseText = ''; this._response = ''; this.listeners = new Map(); this.openCalls = []; }
    open(method, url) { this.openCalls.push([method, url]); this.method = method; this.url = url; }
    addEventListener(type, fn) { const list = this.listeners.get(type) || []; list.push(fn); this.listeners.set(type, list); }
    emit(type) { for (const fn of this.listeners.get(type) || []) fn.call(this, { type, target: this }); }
    get responseText() { return this._responseText; }
    get response() { return this._response; }
  }
  const local = new Map();
  const context = {
    document, location: { href: 'https://www.dola.com/chat' }, XMLHttpRequest: IsolatedXHR, URL, Headers, Response, Request,
    Event, CustomEvent, URLSearchParams, setTimeout, clearTimeout,
    localStorage: { getItem: key => local.get(key) ?? null, setItem: (key, val) => local.set(key, String(val)) },
    console, calls
  };
  context.window = context;
  context.fetch = function(input, init) {
    calls.push({ input, init });
    return Promise.resolve(new Response(JSON.stringify({ ordinary: true }), { headers: { 'content-type': 'application/json' } }));
  };
  context.globalThis = context;
  context.performance = { getEntriesByType: () => [] };
  return context;
}

function runScripts(context) {
  vm.runInNewContext(skillSource, context, { filename: 'skill-pack-main.js' });
  vm.runInNewContext(mediaSource, context, { filename: 'media-upload-main.js' });
}

async function jsonResponse(response) { return JSON.parse(await response.text()); }

test('raw page-main scripts install in manifest order without chrome and do not eagerly upload/send', async () => {
  const ctx = makeContext();
  runScripts(ctx);
  assert.equal(ctx.__dolaNative30SkillPackInstalledV150, true);
  assert.equal(ctx.__DOLA_TOOLKIT_MEDIA_UPLOAD_BRIDGE_V1__, true);
  assert.equal(ctx.document.documentElement.getAttribute('data-dola-native-30-ready'), null);
  assert.equal(ctx.document.documentElement.getAttribute('data-dola-toolkit-media-upload-ready'), '1');
  assert.deepEqual(ctx.calls, []);
  assert.equal(ctx.chrome, undefined);
});

test('skill-pack fetch patches duration/model config and passes unrelated responses through', async () => {
  const ctx = makeContext();
  const ordinaryResponse = new Response(JSON.stringify({ ordinary: true }), { headers: { 'content-type': 'application/json' } });
  ctx.window.fetch = async function(input, init) {
    ctx.calls.push({ input, init });
    const url = typeof input === 'string' ? input : input.url;
    if (url.includes('/samantha/skill/pack')) {
      return new Response(JSON.stringify({ data: { duration: { value: 'duration', options: [{ value: '10', show_name: '10s' }] }, model_capability: { "seedance_v2.5": { supported_durations: ['10'] } } } }), { headers: { 'content-type': 'application/json', 'content-length': '10' } });
    }
    return ordinaryResponse;
  };
  runScripts(ctx);
  const patched = await ctx.window.fetch('https://www.dola.com/samantha/skill/pack');
  const json = await jsonResponse(patched);
  const options = json.data.duration.options;
  assert.deepEqual(options.map(x => x.value), ['10', '15', '30']);
  assert.equal(options.find(x => x.value === '30').is_default, true);
  assert.deepEqual(json.data.model_capability["seedance_v2.5"].supported_durations, ['10', '15', '30']);
  assert.equal(ctx.document.documentElement.getAttribute('data-dola-native-30-ready'), '1');
  const ordinary = await ctx.window.fetch('https://www.dola.com/api/ordinary');
  assert.strictEqual(ordinary, ordinaryResponse);
  assert.equal(ctx.calls.length, 2);
  assert.ok(ctx.calls.every(call => !String(call.input).includes('/upload')));
});

test('skill-pack XHR transforms only config and observes chain fallback URLs', async () => {
  const ctx = makeContext();
  runScripts(ctx);
  const xhr = new ctx.XMLHttpRequest();
  xhr.open('GET', '/samantha/skill/pack');
  xhr._responseText = JSON.stringify({ value: 'duration', options: [{ value: '10' }] });
  assert.deepEqual(JSON.parse(xhr.responseText).options.map(x => x.value), ['10', '15', '30']);
  const chain = new ctx.XMLHttpRequest();
  chain.open('GET', '/im/chain/single');
  chain._responseText = JSON.stringify({ fallback_api: 'https://vod-example.bytevcloudapi.com/?x=1' });
  chain.emit('load');
  assert.deepEqual(JSON.parse(ctx.document.documentElement.getAttribute('data-dola-media-fallback-apis')), ['https://vod-example.bytevcloudapi.com/?x=1']);
});

test('media upload URL normalizer rewrites only upload host and event guard performs no upload', async () => {
  const ctx = makeContext();
  const seen = [];
  ctx.window.fetch = (input, init) => { seen.push({ input, init }); return Promise.resolve(new Response('ok')); };
  const bridge = new Element('input');
  const result = new Element('pre');
  ctx.document.setNode('#dola-toolkit-media-upload-bridge', bridge);
  ctx.document.setNode('#dola-toolkit-media-upload-result', result);
  runScripts(ctx);
  await ctx.window.fetch('https://www.dola.com/chat/vod-demo.bytevcloudapi.com/?token=1');
  await ctx.window.fetch('https://vod-demo.bytevcloudapi.com/?token=2');
  assert.equal(seen[0].input, 'https://vod-demo.bytevcloudapi.com/?token=1');
  assert.equal(seen[1].input, 'https://vod-demo.bytevcloudapi.com/?token=2');
  ctx.document.dispatchEvent(new Event('dola-toolkit-media-upload-request'));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(result.getAttribute('data-ready'), '1');
  assert.match(result.textContent, /没有读到待上传素材/);
  const xhr = new ctx.XMLHttpRequest();
  xhr.open('POST', 'https://www.dola.com/chat/vod-demo.bytevcloudapi.com/?token=3');
  assert.equal(xhr.openCalls[0][1], 'https://vod-demo.bytevcloudapi.com/?token=3');
});




