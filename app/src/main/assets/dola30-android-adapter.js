/*
 * Dola30 Android WebView adapter.
 *
 * This is a platform adapter, not a rewrite of the supplied MV3 sources. The
 * original files remain byte-for-byte in dola30-original/. This adapter only
 * exposes the local 15/30-second state and the prompt timeline helper to a
 * trusted page. It never patches fetch/XHR, changes a request, auto-submits,
 * or claims that the page/backend supports a particular model capability.
 */
(function (root) {
  "use strict";
  if (!root || !root.document || root.top !== root) return;
  var host = String(root.location && root.location.hostname || "").toLowerCase();
  function allowed(name) {
    return name === "doubao.com" || name.slice(-10) === ".doubao.com" ||
      name === "dola.com" || name.slice(-9) === ".dola.com";
  }
  if (!allowed(host) || root.__dola30AndroidAdapterInstalled) return;
  root.__dola30AndroidAdapterInstalled = true;

  var ENABLE_KEY = "intl_doubao_enable_30s_v1";
  var TARGET_KEY = "intl_doubao_target_duration_v1";
  var STATE_ATTRIBUTE = "data-watermark-free-duration-30";
  var STATE_EVENT = "watermark-free-duration-30-change";
  var CONTROL_ID = "dola30-android-control";

  function storageGet(key, fallback) {
    try {
      var value = root.localStorage.getItem(key);
      return value == null ? fallback : value;
    } catch (_) { return fallback; }
  }
  function storageSet(key, value) {
    try { root.localStorage.setItem(key, value); } catch (_) {}
  }
  function isEnabled() {
    return storageGet(ENABLE_KEY, "0") === "1" && storageGet(TARGET_KEY, "15") === "30";
  }
  function duration() { return isEnabled() ? 30 : 15; }
  function statusObject() {
    return {
      on: isEnabled(),
      duration: duration(),
      ready: true,
      androidWebView: true,
      networkRewrite: false,
      autoSubmit: false,
      platformCapability: "page-controlled"
    };
  }
  function publish() {
    var on = isEnabled();
    var documentElement = root.document.documentElement;
    if (documentElement) documentElement.setAttribute(STATE_ATTRIBUTE, on ? "true" : "false");
    try { root.document.dispatchEvent(new Event(STATE_EVENT)); } catch (_) {}
  }
  function render() {
    if (!root.document.body) return;
    var control = root.document.getElementById(CONTROL_ID);
    if (!control) {
      control = root.document.createElement("button");
      control.id = CONTROL_ID;
      control.type = "button";
      control.setAttribute("aria-label", "Android WebView 30 秒工具");
      control.style.cssText = "position:fixed;right:12px;bottom:12px;z-index:2147483647;" +
        "border:1px solid rgba(255,255,255,.35);border-radius:18px;padding:8px 12px;" +
        "background:#202638;color:#fff;font:13px sans-serif;box-shadow:0 2px 8px rgba(0,0,0,.25);";
      control.addEventListener("click", function () { root.setDola30s(!isEnabled()); });
      root.document.body.appendChild(control);
    }
    control.textContent = isEnabled() ? "30S 已启用" : "30S 工具（当前 15S）";
    control.title = "仅保存页面本地状态；不会拦截请求或自动发送";
  }
  function setDola30s(on) {
    var enabled = !!on;
    storageSet(ENABLE_KEY, enabled ? "1" : "0");
    storageSet(TARGET_KEY, enabled ? "30" : "15");
    publish();
    render();
    try { root.dispatchEvent(new CustomEvent(STATE_EVENT, { detail: statusObject() })); } catch (_) {}
    return statusObject();
  }
  function getDola30sStatus() { return JSON.stringify(statusObject()); }
  function rewriteDolaTimeline(text, totalSeconds, includeControlInstructions) {
    if (!root.DolaPromptTools || typeof root.DolaPromptTools.rewriteTimeline !== "function") {
      throw new Error("prompt-tools.js 尚未加载");
    }
    return root.DolaPromptTools.rewriteTimeline(text, totalSeconds, {
      promptGuard: includeControlInstructions === true
    });
  }

  root.rewriteDolaTimeline = rewriteDolaTimeline;
  if (root.__watermarkFreeDuration30Installed && typeof root.setDola30s === "function" &&
      typeof root.getDola30sStatus === "function") {
    // Keep the supplied page script's controls/request patcher intact. Android
    // adds only the local timeline preview helper alongside that opt-in script.
    root.dola30Android = Object.freeze({
      setDola30s: root.setDola30s,
      getDola30sStatus: root.getDola30sStatus,
      rewriteDolaTimeline: rewriteDolaTimeline,
      limitations: Object.freeze({ networkRewrite: true, autoSubmit: false, watermarkBypass: false })
    });
    return;
  }
  // Default WebView mode exposes local helpers under the original public names.
  root.setDola30s = setDola30s;
  root.getDola30sStatus = getDola30sStatus;
  root.dola30Android = Object.freeze({
    setDola30s: setDola30s,
    getDola30sStatus: getDola30sStatus,
    rewriteDolaTimeline: rewriteDolaTimeline,
    limitations: Object.freeze({ networkRewrite: false, autoSubmit: false, watermarkBypass: false })
  });

  publish();
  if (root.document.readyState === "loading") {
    root.document.addEventListener("DOMContentLoaded", render, { once: true });
  } else render();
  if (root.MutationObserver && root.document.documentElement) {
    var pending = false;
    new MutationObserver(function () {
      if (pending) return;
      pending = true;
      root.setTimeout(function () { pending = false; render(); }, 250);
    }).observe(root.document.documentElement, { childList: true, subtree: true });
  }
})(typeof window === "undefined" ? null : window);
