const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const file = "../app/src/main/assets/dola30-android-adapter.js";
const source = fs.readFileSync(require("node:path").join(__dirname, file), "utf8");

test("Android adapter exposes the local 30S and prompt-tool APIs", () => {
  assert.match(source, /root\.setDola30s\s*=\s*setDola30s/);
  assert.match(source, /root\.getDola30sStatus\s*=\s*getDola30sStatus/);
  assert.match(source, /root\.rewriteDolaTimeline\s*=\s*rewriteDolaTimeline/);
  assert.match(source, /androidWebView\s*:\s*true/);
});

test("Android adapter does not install request interception or Chrome APIs", () => {
  assert.doesNotMatch(source, /(?:window\.)?fetch\s*=\s*/i);
  assert.doesNotMatch(source, /XMLHttpRequest\s*\.prototype/i);
  assert.doesNotMatch(source, /chrome\s*\./i);
  assert.match(source, /networkRewrite\s*:\s*false/);
  assert.match(source, /autoSubmit\s*:\s*false/);
});

test("opt-in adapter preserves the original script's public controls", () => {
  const originalSet = () => "original-set";
  const originalGet = () => "original-get";
  const originalLegacySet = () => "original-legacy-set";
  const originalLegacyGet = () => "original-legacy-get";
  const page = {
    top: null,
    location: { hostname: "www.doubao.com" },
    document: { documentElement: {}, readyState: "loading", addEventListener() {} },
    localStorage: { getItem() { return null; }, setItem() {} },
    __watermarkFreeDuration30Installed: true,
    setDola30s: originalSet,
    getDola30sStatus: originalGet,
    setDoubao30s: originalLegacySet,
    getDoubao30sStatus: originalLegacyGet,
    DolaPromptTools: { rewriteTimeline(text) { return text; } }
  };
  page.top = page;
  vm.runInNewContext(source, { window: page });
  assert.equal(page.setDola30s, originalSet);
  assert.equal(page.getDola30sStatus, originalGet);
  assert.equal(page.setDoubao30s, originalLegacySet);
  assert.equal(page.getDoubao30sStatus, originalLegacyGet);
  assert.equal(page.dola30Android.setDola30s, originalSet);
  assert.equal(page.dola30Android.getDola30sStatus, originalGet);
  assert.equal(page.dola30Android.limitations.networkRewrite, true);
  assert.equal(page.dola30Android.limitations.autoSubmit, false);
  assert.equal(typeof page.dola30Android.getOriginalOptions, "function");
  assert.equal(typeof page.dola30Android.setOriginalOptions, "function");
});

test("default adapter provides the original legacy host aliases and status shape", () => {
  const values = new Map();
  const page = {
    top: null,
    location: { hostname: "www.doubao.com" },
    document: {
      documentElement: { setAttribute() {} },
      readyState: "loading",
      addEventListener() {},
      dispatchEvent() {}
    },
    localStorage: {
      getItem(key) { return values.has(key) ? values.get(key) : null; },
      setItem(key, value) { values.set(key, String(value)); }
    }
  };
  page.top = page;
  vm.runInNewContext(source, { window: page });

  assert.equal(typeof page.setDoubao30s, "function");
  assert.equal(typeof page.getDoubao30sStatus, "function");
  assert.equal(page.dola30Android.setDoubao30s, page.setDoubao30s);
  assert.equal(page.dola30Android.getDoubao30sStatus, page.getDoubao30sStatus);
  assert.equal(page.setDoubao30s(true), true);
  assert.deepEqual(JSON.parse(page.getDoubao30sStatus()), {
    on: true, applied: 0, duration: 30, ready: true
  });
  assert.equal(values.get("intl_doubao_enable_30s_v1"), "1");
  assert.equal(values.get("intl_doubao_target_duration_v1"), "30");
  assert.equal(page.setDoubao30s(false), false);
  assert.deepEqual(JSON.parse(page.getDoubao30sStatus()), {
    on: false, applied: 0, duration: 15, ready: true
  });
});

test("adapter reads and writes only the original script's existing option keys", () => {
  const values = new Map();
  const page = {
    top: null,
    location: { hostname: "www.dola.com" },
    document: {
      documentElement: { setAttribute() {} },
      readyState: "loading",
      addEventListener() {},
      dispatchEvent() {}
    },
    localStorage: {
      getItem(key) { return values.has(key) ? values.get(key) : null; },
      setItem(key, value) { values.set(key, String(value)); }
    }
  };
  page.top = page;
  vm.runInNewContext(source, { window: page });
  assert.deepEqual(JSON.parse(JSON.stringify(page.dola30Android.getOriginalOptions())), {
    promptGuard: false,
    timelineRewrite: true
  });
  const saved = page.dola30Android.setOriginalOptions({ promptGuard: true, timelineRewrite: false });
  assert.deepEqual(JSON.parse(JSON.stringify(saved)), { promptGuard: true, timelineRewrite: false });
  assert.equal(values.get("intl_doubao_prompt_guard_inject_v1"), "1");
  assert.equal(values.get("intl_doubao_timeline_rewrite_v1"), "0");
  assert.deepEqual(JSON.parse(JSON.stringify(page.dola30Android.setOriginalOptions({}))), {
    promptGuard: false,
    timelineRewrite: true
  });
});

test("adapter allows exact Dola/Doubao subdomains but not lookalike domains", () => {
  function createPage(hostname) {
    const page = {
      top: null,
      location: { hostname },
      document: {
        documentElement: { setAttribute() {} },
        readyState: "loading",
        addEventListener() {}
      },
      localStorage: { getItem() { return null; }, setItem() {} }
    };
    page.top = page;
    vm.runInNewContext(source, { window: page });
    return page;
  }
  assert.ok(createPage("www.doubao.com").dola30Android);
  assert.ok(createPage("chat.dola.com").dola30Android);
  assert.equal(createPage("notdoubao.com").dola30Android, undefined);
  assert.equal(createPage("dola.com.attacker.test").dola30Android, undefined);
});

test("floating Android control is draggable and persists the original panel position key", () => {
  const values = new Map();
  const listeners = new Map();
  let button;
  const page = {
    top: null,
    innerWidth: 360,
    innerHeight: 640,
    location: { hostname: "www.dola.com" },
    localStorage: {
      getItem(key) { return values.has(key) ? values.get(key) : null; },
      setItem(key, value) { values.set(key, String(value)); }
    },
    setTimeout() {},
    Event: function Event() {},
    CustomEvent: function CustomEvent() {},
    MutationObserver: null,
    document: {
      readyState: "complete",
      documentElement: { setAttribute() {} },
      dispatchEvent() {},
      getElementById() { return button || null; },
      createElement() {
        button = {
          style: {}, offsetWidth: 160, offsetHeight: 40,
          setAttribute() {},
          addEventListener(name, callback) { listeners.set(name, callback); },
          setPointerCapture() {}, releasePointerCapture() {}
        };
        return button;
      },
      body: { appendChild() {} }
    }
  };
  page.top = page;
  vm.runInNewContext(source, { window: page });
  assert.equal(button.style.left, "188px");
  assert.equal(button.style.bottom, "12px");
  listeners.get("pointerdown")({ button: 0, pointerId: 4, clientX: 200, clientY: 610 });
  listeners.get("pointermove")({ pointerId: 4, clientX: 80, clientY: 500 });
  listeners.get("pointerup")({ pointerId: 4 });
  assert.equal(button.style.left, "68px");
  assert.equal(button.style.bottom, "122px");
  assert.deepEqual(JSON.parse(values.get("intl_doubao_media_panel_pos_v3")), { left: 68, bottom: 122 });
  listeners.get("click")({ detail: 1, isTrusted: true });
  assert.equal(page.dola30Android.getDola30sStatus().includes('"on":false'), true);
  listeners.get("pointerdown")({ button: 0, pointerId: 5, clientX: 80, clientY: 500, preventDefault() {} });
  listeners.get("pointerup")({ pointerId: 5 });
  assert.equal(page.dola30Android.getDola30sStatus().includes('"on":true'), true, "tap must toggle 30S");
  page.setDola30s(false);
  listeners.get("pointerdown")({ button: 0, pointerId: 6, clientX: 80, clientY: 500, preventDefault() {} });
  listeners.get("pointercancel")({ pointerId: 6 });
  assert.equal(page.dola30Android.getDola30sStatus().includes('"on":false'), true, "cancelled pointer must not count as a tap");
});

