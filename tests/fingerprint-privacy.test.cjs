const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "../app/src/main/assets/fingerprint-privacy");
const read = name => fs.readFileSync(path.join(root, name), "utf8");
const vm = require("node:vm");

function pageFixture(withHints = true) {
  const prototype = { userAgent: "Original UA", appVersion: "Original version", platform: "Original platform", language: "en-US", languages: ["en-US"], hardwareConcurrency: 12, maxTouchPoints: 5, vendor: "Original vendor" };
  if (withHints) { prototype.userAgentData = { platform: "Original platform" }; prototype.deviceMemory = 8; }
  const navigator = Object.create(prototype);
  Object.defineProperty(navigator, "language", { configurable: true, enumerable: false, value: "ja-JP" });
  function GL() {}
  GL.prototype.getExtension = function (name) { return "native:" + name; };
  const window = { navigator, WebGLRenderingContext: GL, dispatchEvent() {}, addEventListener() {} };
  window.top = window;
  const context = vm.createContext({ window, DOMException, CustomEvent: function (type, options) { this.type = type; this.detail = options.detail; } });
  vm.runInContext(read("content-main.js"), context);
  return { window, navigator, prototype, api: window.__dolaFingerprintPrivacy };
}

function workerFixture() {
  const listeners = {};
  const state = { cleanMode: false };
  const updates = [];
  const event = name => ({ addListener: fn => { listeners[name] = fn; } });
  const chrome = {
    runtime: { id: "local-extension", getURL: name => "chrome-extension://local-extension/" + name, onInstalled: event("installed"), onStartup: event("startup"), onMessage: event("message") },
    storage: { onChanged: event("changed"), local: {
      get: async defaults => ({ ...defaults, ...state }),
      set: async values => { Object.assign(state, values); }
    } },
    declarativeNetRequest: { updateDynamicRules: async rules => { updates.push(JSON.parse(JSON.stringify(rules))); } }
  };
  const context = vm.createContext({ chrome });
  vm.runInContext(read("service-worker.js"), context);
  return { chrome, state, updates, listeners, sync: () => vm.runInContext("syncCleanRules()", context) };
}

test("fingerprint privacy extension declares required MV3 host and network permissions", () => {
  const manifest = JSON.parse(read("manifest.json"));
  assert.equal(manifest.manifest_version, 3);
  assert.ok(manifest.permissions.includes("storage"));
  assert.ok(manifest.permissions.includes("tabs"));
  assert.ok(manifest.permissions.includes("declarativeNetRequestWithHostAccess"));
  assert.deepEqual(manifest.background, { service_worker: "service-worker.js" });
  assert.deepEqual(manifest.host_permissions, ["<all_urls>"]);
  assert.ok(manifest.content_scripts.some(item => item.js.includes("content-main.js") && item.world === "MAIN"));
  assert.ok(manifest.content_scripts.some(item => item.js.includes("bridge.js")));
});

test("fingerprint sources parse and keep declarative network policy in the worker", () => {
  for (const name of ["content-main.js", "bridge.js", "popup.js", "service-worker.js"]) {
    new Function(read(name));
    const source = read(name);
    assert.doesNotMatch(source, /(?:window\.)?fetch\s*=\s*/i, name);
    assert.doesNotMatch(source, /XMLHttpRequest\s*\.prototype/i, name);
    if (name !== "service-worker.js") assert.doesNotMatch(source, /chrome\.(webRequest|debugger|declarativeNetRequest)/i, name);
  }
});

test("fingerprint module is explicit about local-only behavior", () => {
  assert.match(read("content-main.js"), /localOnly:\s*true/);
  assert.match(read("README.md"), /不上传网络/);
  assert.match(read("README.md"), /固定的隐私化 UA/);
  assert.match(read("content-main.js"), /blockAudioFingerprint/);
  assert.match(read("content-main.js"), /blockWebRTC/);
  assert.match(read("content-main.js"), /cleanMode/);
  assert.match(read("service-worker.js"), /updateDynamicRules/);
  assert.match(read("service-worker.js"), /sec-ch-ua/);
  assert.doesNotMatch(read("content-main.js"), /Math\.random|crypto\.getRandomValues/);
  assert.match(read("popup.js"), /strict/);
});

test("clean identity enables twice and restores exact own/inherited properties", async () => {
  const { navigator, prototype, api } = pageFixture();
  const original = Object.getOwnPropertyDescriptors(navigator);
  for (let i = 0; i < 2; i++) {
    api.applySettings({ cleanMode: true });
    api.applySettings({ cleanMode: true });
    assert.match(navigator.userAgent, /Android 13/);
    assert.equal(navigator.appVersion, navigator.userAgent.slice(8));
    assert.deepEqual(Array.from(navigator.languages), ["zh-CN", "zh", "en"]);
    assert.ok(Object.isFrozen(navigator.languages));
    assert.ok(Object.isFrozen(navigator.userAgentData.brands[0]));
    const hints = await navigator.userAgentData.getHighEntropyValues(["model", "platformVersion", "unknown"]);
    assert.equal(hints.model, "");
    assert.equal(hints.mobile, true);
    assert.equal(hints.platform, "Android");
    assert.ok(hints.brands);
    assert.equal(hints.platformVersion, "13.0.0");
    assert.equal("uaFullVersion" in hints, false);
    assert.equal("unknown" in hints, false);
    api.applySettings({ cleanMode: false });
    assert.deepEqual(Object.getOwnPropertyDescriptors(navigator), original);
    assert.equal(navigator.userAgent, prototype.userAgent);
    assert.equal(navigator.userAgentData, prototype.userAgentData);
  }
});

test("clean identity does not add unsupported client hints or memory API", () => {
  const { navigator, api } = pageFixture(false);
  api.applySettings({ cleanMode: true });
  assert.equal("userAgentData" in navigator, false);
  assert.equal("deviceMemory" in navigator, false);
});

test("WebGL reduction can be removed and re-enabled; cached wrappers still work", () => {
  const { window, api } = pageFixture();
  const gl = new window.WebGLRenderingContext();
  const cached = gl.getExtension;
  assert.equal(gl.getExtension("WEBGL_debug_renderer_info"), null);
  api.applySettings({ hideWebglDebugInfo: false });
  assert.equal(gl.getExtension("WEBGL_debug_renderer_info"), "native:WEBGL_debug_renderer_info");
  assert.equal(cached.call(gl, "OTHER"), "native:OTHER");
  api.applySettings({ hideWebglDebugInfo: true });
  assert.equal(gl.getExtension("WEBGL_debug_renderer_info"), null);
  assert.equal(gl.getExtension("OTHER"), "native:OTHER");
});

test("network enable/disable touches only owned rules, preserves auth, matches JS identity", async () => {
  const worker = workerFixture();
  const { navigator, api } = pageFixture();
  worker.state.cleanMode = true;
  api.applySettings({ cleanMode: true });
  assert.equal((await worker.sync()).ok, true);
  const headers = worker.updates[0].addRules.flatMap(rule => rule.action.requestHeaders);
  assert.equal(headers.find(h => h.header === "user-agent").value, navigator.userAgent);
  const languages = headers.find(h => h.header === "accept-language").value.split(",").map(v => v.split(";")[0]);
  assert.deepEqual(languages, Array.from(navigator.languages));
  assert.ok(headers.some(h => h.header === "sec-ch-ua-model" && h.operation === "remove"));
  assert.ok(!headers.some(h => /cookie|authorization|referer/i.test(h.header)));
  worker.state.cleanMode = false;
  assert.equal((await worker.sync()).enabled, false);
  assert.deepEqual(worker.updates[1], { removeRuleIds: [31001, 31002, 31003, 31004], addRules: [] });
});

test("concurrent network updates serialize so disable finishes last", async () => {
  const worker = workerFixture();
  let release, started;
  const begin = new Promise(resolve => { started = resolve; });
  let active = 0, maxActive = 0;
  worker.chrome.declarativeNetRequest.updateDynamicRules = async rules => {
    maxActive = Math.max(maxActive, ++active);
    worker.updates.push(JSON.parse(JSON.stringify(rules)));
    if (worker.updates.length === 1) { started(); await new Promise(resolve => { release = resolve; }); }
    active--;
  };
  worker.state.cleanMode = true;
  const enable = worker.sync();
  await begin;
  worker.state.cleanMode = false;
  const disable = worker.sync();
  release();
  await Promise.all([enable, disable]);
  assert.equal(maxActive, 1);
  assert.equal(worker.updates[0].addRules.length, 4);
  assert.equal(worker.updates[1].addRules.length, 0);
  assert.equal(worker.state.cleanModeStatus.enabled, false);
});

test("network failure is reported and queue recovers on retry", async () => {
  const worker = workerFixture();
  worker.state.cleanMode = true;
  const original = worker.chrome.declarativeNetRequest.updateDynamicRules;
  worker.chrome.declarativeNetRequest.updateDynamicRules = async () => { throw new Error("unsupported browser"); };
  const failed = await worker.sync();
  assert.equal(failed.ok, false);
  assert.equal(failed.enabled, null);
  assert.match(worker.state.cleanModeStatus.error, /unsupported browser/);
  worker.chrome.declarativeNetRequest.updateDynamicRules = original;
  assert.equal((await worker.sync()).ok, true);
});

test("worker acknowledges extension UI and ignores page/content-script requests", async () => {
  const worker = workerFixture();
  assert.equal(worker.listeners.message({ type: "sync-clean-mode" }, { id: "other" }, () => assert.fail()), undefined);
  assert.equal(worker.listeners.message({ type: "sync-clean-mode" }, { id: "local-extension", tab: { id: 1 } }, () => assert.fail()), undefined);
  const response = await new Promise(resolve => {
    assert.equal(worker.listeners.message({ type: "sync-clean-mode" }, { id: "local-extension", url: "chrome-extension://local-extension/popup.html", tab: { id: 2 } }, resolve), true);
  });
  assert.equal(response.ok, true);
});

function popupFixture() {
  const elements = Object.fromEntries(["clean", "canvas", "webgl", "audio", "webrtc", "devices", "output", "save", "strict", "report"].map(id => [id, {
    checked: false, disabled: false, textContent: "", addEventListener(type, fn) { this[type] = fn; }
  }]));
  let load, acknowledge;
  const chrome = {
    storage: { local: { get: () => new Promise(resolve => { load = resolve; }), set: async () => {} } },
    runtime: { sendMessage: () => new Promise(resolve => { acknowledge = resolve; }) }
  };
  vm.runInNewContext(read("popup.js"), { chrome, document: {
    getElementById: id => elements[id], querySelectorAll: () => Object.values(elements).filter(e => e !== elements.output)
  } });
  return { elements, load: () => load({ cleanMode: true }), acknowledge: status => acknowledge(status) };
}

test("popup disables controls until settings load and waits for rule acknowledgement", async () => {
  const popup = popupFixture();
  assert.equal(popup.elements.save.disabled, true);
  popup.load();
  await Promise.resolve();
  assert.equal(popup.elements.clean.checked, true);
  assert.equal(popup.elements.save.disabled, false);
  const saving = popup.elements.save.click();
  await Promise.resolve();
  assert.equal(popup.elements.save.disabled, true);
  assert.doesNotMatch(popup.elements.output.textContent, /已保存/);
  popup.acknowledge({ ok: true, enabled: true });
  await saving;
  assert.equal(popup.elements.save.disabled, false);
  assert.match(popup.elements.output.textContent, /规则已启用/);
});

test("popup does not claim success when network rules fail", async () => {
  const popup = popupFixture();
  popup.load();
  await Promise.resolve();
  const saving = popup.elements.save.click();
  await Promise.resolve();
  popup.acknowledge({ ok: false, error: "unsupported browser" });
  await saving;
  assert.match(popup.elements.output.textContent, /未确认完整生效/);
  assert.match(popup.elements.output.textContent, /unsupported browser/);
  assert.match(popup.elements.output.textContent, /旧请求头规则可能仍存在/);
  assert.equal(popup.elements.save.disabled, false);
});
