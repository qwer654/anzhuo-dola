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

test("read-only page-selection status separates displayed Dola controls from local state", () => {
  function fixture(host, modelText, durationText, shown = true) {
    const attrs = [];
    const values = new Map();
    const model = { textContent: modelText, isConnected: shown, getBoundingClientRect() { return {width:100,height:24}; } };
    const duration = { textContent: durationText, isConnected: shown, getBoundingClientRect() { return {width:60,height:24}; } };
    const page = {
      top: null,
      location: { hostname: host },
      getComputedStyle() { return {display:shown?"block":"none",visibility:"visible",opacity:"1"}; },
      document: {
        documentElement: { setAttribute(k,v) { attrs.push([k,v]); } },
        readyState:"loading", addEventListener(){}, dispatchEvent(){},
        querySelector(selector) { return selector.includes("video-model") ? model : duration; }
      },
      localStorage: { getItem(key){return values.has(key)?values.get(key):null;}, setItem(key,value){values.set(key,String(value));} }
    };
    page.top = page;
    vm.runInNewContext(source, { window:page, Event:function Event(){} });
    return {page,attrs,model,duration};
  }
  const match=fixture("www.dola.com","Seedance 2.5","30s");
  assert.equal(match.page.dola30Android.getDolaPlatformSelectionStatus().status,"selection_matches_30s");
  const different=fixture("www.dola.com","Seedance 2.0","15秒");
  assert.equal(different.page.dola30Android.getDolaPlatformSelectionStatus().status,"detected_not_30s");
  const hidden=fixture("www.dola.com","Seedance 2.5","30s",false);
  assert.equal(hidden.page.dola30Android.getDolaPlatformSelectionStatus().status,"controls_not_detected");
  const doubao=fixture("www.doubao.com","Seedance 2.5","30s");
  assert.equal(doubao.page.dola30Android.getDolaPlatformSelectionStatus().status,"not_applicable");
  match.page.setDola30s(true);
  assert.deepEqual(match.attrs.at(-1),["data-watermark-free-duration-30","1"]);
  assert.equal(match.model.textContent,"Seedance 2.5");
  assert.equal(match.duration.textContent,"30s");
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
  const elements = new Map();
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
      getElementById(id) { return elements.get(id) || null; },
      createElement() {
        return { id: "", style: {}, offsetWidth: 160, offsetHeight: 40,
          setAttribute() {}, addEventListener(name, callback) { if (this.id === "dola30-android-control") listeners.set(name, callback); },
          setPointerCapture() {}, releasePointerCapture() {} };
      },
      body: { appendChild(element) { elements.set(element.id, element); if (element.id === "dola30-android-control") button = element; } }
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
  listeners.get("pointerdown")({ button: 0, pointerId: 7, clientX: 80, clientY: 500, preventDefault() {} });
  listeners.get("pointermove")({ pointerId: 7, clientX: 90, clientY: 495 });
  listeners.get("pointercancel")({ pointerId: 7 });
  assert.deepEqual(JSON.parse(values.get("intl_doubao_media_panel_pos_v3")), { left: 78, bottom: 127 }, "movement before cancellation must be persisted");
  assert.equal(page.dola30Android.getDola30sStatus().includes('"on":false'), true, "cancelled movement must not toggle 30S");
});

test("floating Android control restores saved position and clamps it to viewport", () => {
  const values = new Map([["intl_doubao_media_panel_pos_v3", JSON.stringify({ left: 310, bottom: 610 })]]);
  const elements = new Map();
  let button;
  const page = {
    top: null, innerWidth: 360, innerHeight: 640,
    location: { hostname: "www.dola.com" },
    localStorage: {
      getItem(key) { return values.has(key) ? values.get(key) : null; },
      setItem(key, value) { values.set(key, String(value)); }
    },
    setTimeout() {}, Event: function Event() {}, CustomEvent: function CustomEvent() {}, MutationObserver: null,
    document: {
      readyState: "complete", documentElement: { setAttribute() {} }, dispatchEvent() {},
      getElementById(id) { return elements.get(id) || null; },
      createElement() {
        return { id: "", style: {}, offsetWidth: 160, offsetHeight: 40, setAttribute() {},
          addEventListener() {}, setPointerCapture() {}, releasePointerCapture() {} };
      },
      body: { appendChild(element) { elements.set(element.id, element); if (element.id === "dola30-android-control") button = element; } }
    }
  };
  page.top = page;
  vm.runInNewContext(source, { window: page });
  assert.equal(button.style.left, "192px");
  assert.equal(button.style.bottom, "592px");
  assert.deepEqual(JSON.parse(values.get("intl_doubao_media_panel_pos_v3")), { left: 310, bottom: 610 }, "restoring must not overwrite saved coordinates");
});

test("floating Android control reclamps and persists after viewport resize", () => {
  const values = new Map([["intl_doubao_media_panel_pos_v3", JSON.stringify({ left: 188, bottom: 12 })]]);
  const listeners = new Map();
  const elements = new Map();
  let button;
  const page = {
    top: null, innerWidth: 360, innerHeight: 640,
    location: { hostname: "www.dola.com" },
    addEventListener(name, callback) { listeners.set(name, callback); },
    localStorage: {
      getItem(key) { return values.has(key) ? values.get(key) : null; },
      setItem(key, value) { values.set(key, String(value)); }
    },
    setTimeout() {}, Event: function Event() {}, CustomEvent: function CustomEvent() {}, MutationObserver: null,
    document: {
      readyState: "complete", documentElement: { setAttribute() {} }, dispatchEvent() {},
      getElementById(id) { return elements.get(id) || null; },
      createElement() {
        return { id: "", style: {}, offsetWidth: 160, offsetHeight: 40, setAttribute() {},
          addEventListener(name, callback) { if (this.id === "dola30-android-control") listeners.set(name, callback); }, setPointerCapture() {}, releasePointerCapture() {} };
      },
      body: { appendChild(element) { elements.set(element.id, element); if (element.id === "dola30-android-control") button = element; } }
    }
  };
  page.top = page;
  vm.runInNewContext(source, { window: page });
  page.innerWidth = 100;
  listeners.get("resize")();
  assert.equal(button.style.left, "8px");
  assert.deepEqual(JSON.parse(values.get("intl_doubao_media_panel_pos_v3")), { left: 8, bottom: 12 });
});

test("floating Android control respects visual viewport offsets and keyboard bounds", () => {
  const values = new Map([["intl_doubao_media_panel_pos_v3", JSON.stringify({ left: 188, bottom: 12 })]]);
  const listeners = new Map();
  const elements = new Map();
  let button;
  const visualViewport = {
    offsetLeft: 30, offsetTop: 0, width: 240, height: 350,
    addEventListener(name, callback) { listeners.set("visual:" + name, callback); }
  };
  const page = {
    top: null, innerWidth: 360, innerHeight: 640, visualViewport,
    location: { hostname: "www.dola.com" },
    addEventListener(name, callback) { listeners.set(name, callback); },
    localStorage: {
      getItem(key) { return values.has(key) ? values.get(key) : null; },
      setItem(key, value) { values.set(key, String(value)); }
    },
    setTimeout() {}, Event: function Event() {}, CustomEvent: function CustomEvent() {}, MutationObserver: null,
    document: {
      readyState: "complete", documentElement: { setAttribute() {} }, dispatchEvent() {},
      getElementById(id) { return elements.get(id) || null; },
      createElement() {
        return { id: "", style: {}, offsetWidth: 160, offsetHeight: 40, setAttribute() {},
          addEventListener(name, callback) { listeners.set(name, callback); }, setPointerCapture() {}, releasePointerCapture() {} };
      },
      body: { appendChild(element) { elements.set(element.id, element); if (element.id === "dola30-android-control") button = element; } }
    }
  };
  page.top = page;
  vm.runInNewContext(source, { window: page });
  assert.equal(button.style.left, "102px");
  assert.equal(button.style.bottom, "298px", "control should remain above the keyboard-sized visual viewport");
  visualViewport.offsetLeft = 100;
  visualViewport.width = 200;
  visualViewport.height = 640;
  listeners.get("visual:scroll")();
  assert.equal(button.style.left, "108px");
  assert.deepEqual(JSON.parse(values.get("intl_doubao_media_panel_pos_v3")), { left: 108, bottom: 298 });
});

test("floating 30S control minimizes, persists the supplied key, and expands without changing duration", () => {
  const values = new Map();
  const elements = new Map();
  const listeners = new Map();
  const page = {
    top: null, innerWidth: 360, innerHeight: 640,
    location: { hostname: "www.dola.com" },
    localStorage: { getItem(key) { return values.has(key) ? values.get(key) : null; }, setItem(key, value) { values.set(key, String(value)); } },
    setTimeout() {}, Event: function Event() {}, CustomEvent: function CustomEvent() {}, MutationObserver: null,
    document: {
      readyState: "complete", documentElement: { setAttribute() {} }, dispatchEvent() {},
      getElementById(id) { return elements.get(id) || null; },
      createElement() { return { id: "", style: {}, offsetWidth: 160, offsetHeight: 40, setAttribute() {},
        addEventListener(name, callback) { listeners.set(this.id + ":" + name, callback); }, setPointerCapture() {}, releasePointerCapture() {} }; },
      body: { appendChild(element) { elements.set(element.id, element); } }
    }
  };
  page.top = page;
  vm.runInNewContext(source, { window: page });
  const control = elements.get("dola30-android-control");
  const minimize = elements.get("dola30-android-control-minimize");
  assert.equal(page.dola30Android.isPanelMinimized(), false);
  assert.equal(minimize.style.display, "block");
  listeners.get("dola30-android-control-minimize:click")();
  assert.equal(values.get("intl_doubao_media_panel_minimized_v1"), "1");
  assert.equal(control.textContent, "30S");
  assert.equal(control.style.display, undefined);
  assert.equal(minimize.style.display, "none");
  assert.equal(page.dola30Android.getDola30sStatus().includes('"on":false'), true);
  listeners.get("dola30-android-control:pointerdown")({ button: 0, pointerId: 2, clientX: 300, clientY: 620, preventDefault() {} });
  listeners.get("dola30-android-control:pointerup")({ pointerId: 2 });
  assert.equal(values.get("intl_doubao_media_panel_minimized_v1"), "0");
  assert.equal(control.textContent, "30S 工具（当前 15S）");
  assert.equal(page.dola30Android.getDola30sStatus().includes('"on":false'), true);
});

