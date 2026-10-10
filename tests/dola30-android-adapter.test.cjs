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
  const page = {
    top: null,
    location: { hostname: "www.doubao.com" },
    document: { documentElement: {}, readyState: "loading", addEventListener() {} },
    localStorage: { getItem() { return null; }, setItem() {} },
    __watermarkFreeDuration30Installed: true,
    setDola30s: originalSet,
    getDola30sStatus: originalGet,
    DolaPromptTools: { rewriteTimeline(text) { return text; } }
  };
  page.top = page;
  vm.runInNewContext(source, { window: page });
  assert.equal(page.setDola30s, originalSet);
  assert.equal(page.getDola30sStatus, originalGet);
  assert.equal(page.dola30Android.setDola30s, originalSet);
  assert.equal(page.dola30Android.getDola30sStatus, originalGet);
  assert.equal(page.dola30Android.limitations.networkRewrite, true);
  assert.equal(page.dola30Android.limitations.autoSubmit, false);
  assert.equal(typeof page.dola30Android.getOriginalOptions, "function");
  assert.equal(typeof page.dola30Android.setOriginalOptions, "function");
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

