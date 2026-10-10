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
    return name === "doubao.com" || name.endsWith(".doubao.com") ||
      name === "dola.com" || name.endsWith(".dola.com");
  }
  if (!allowed(host) || root.__dola30AndroidAdapterInstalled) return;
  root.__dola30AndroidAdapterInstalled = true;

  var ENABLE_KEY = "intl_doubao_enable_30s_v1";
  var TARGET_KEY = "intl_doubao_target_duration_v1";
  // These keys are consumed by the supplied duration-main.js source. Keep its
  // implementation untouched; expose its existing optional switches to the
  // Android-native settings UI when that original script is opted in.
  var PROMPT_GUARD_KEY = "intl_doubao_prompt_guard_inject_v1";
  var TIMELINE_REWRITE_KEY = "intl_doubao_timeline_rewrite_v1";
  var STATE_ATTRIBUTE = "data-watermark-free-duration-30";
  var STATE_EVENT = "watermark-free-duration-30-change";
  var CONTROL_ID = "dola30-android-control";
  // Reuse the supplied panel's position key so moving the Android control
  // remains compatible with the original content-panel.js state.
  var PANEL_POS_KEY = "intl_doubao_media_panel_pos_v3";
  var drag = null;
  var suppressClickUntil = 0;

  function viewportSize() {
    return {
      width: Math.max(1, Number(root.innerWidth) || 360),
      height: Math.max(1, Number(root.innerHeight) || 640)
    };
  }
  function clampPosition(left, bottom, control) {
    var size = viewportSize();
    var width = Math.max(1, control.offsetWidth || 160);
    var height = Math.max(1, control.offsetHeight || 40);
    return {
      left: Math.max(8, Math.min(size.width - width - 8, left)),
      bottom: Math.max(8, Math.min(size.height - height - 8, bottom))
    };
  }
  function applyPosition(control, left, bottom) {
    var position = clampPosition(left, bottom, control);
    control.style.left = position.left + "px";
    control.style.bottom = position.bottom + "px";
    control.style.right = "auto";
    return position;
  }
  function restorePosition(control) {
    var saved = null;
    try { saved = JSON.parse(storageGet(PANEL_POS_KEY, "null")); } catch (_) {}
    if (saved && Number.isFinite(saved.left) && Number.isFinite(saved.bottom)) {
      applyPosition(control, saved.left, saved.bottom);
    } else {
      var size = viewportSize();
      applyPosition(control, size.width - (control.offsetWidth || 160) - 12, 12);
    }
  }
  function beginDrag(event, control) {
    if (event.button != null && event.button !== 0) return;
    var size = viewportSize();
    var left = parseFloat(control.style.left);
    var bottom = parseFloat(control.style.bottom);
    drag = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      left: Number.isFinite(left) ? left : size.width - (control.offsetWidth || 160) - 12,
      bottom: Number.isFinite(bottom) ? bottom : 12,
      moved: false
    };
    try { control.setPointerCapture(event.pointerId); } catch (_) {}
  }
  function moveDrag(event, control) {
    if (!drag || (drag.pointerId != null && event.pointerId !== drag.pointerId)) return;
    var dx = event.clientX - drag.startX;
    var dy = event.clientY - drag.startY;
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) drag.moved = true;
    if (drag.moved) applyPosition(control, drag.left + dx, drag.bottom - dy);
  }
  function finishDrag(event, control) {
    if (!drag || (drag.pointerId != null && event.pointerId !== drag.pointerId)) return;
    var moved = drag.moved;
    drag = null;
    try { control.releasePointerCapture(event.pointerId); } catch (_) {}
    if (!moved) return;
    // Suppress the click synthesized by a completed pointer drag. Use a short
    // deadline instead of a zero-delay timer: WebView may dispatch click after
    // the pointer callback stack has unwound.
    suppressClickUntil = Date.now() + 500;
    try { root.localStorage.setItem(PANEL_POS_KEY, JSON.stringify({
      left: parseFloat(control.style.left), bottom: parseFloat(control.style.bottom)
    })); } catch (_) {}
  }

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
  function getOriginalOptions() {
    return {
      promptGuard: storageGet(PROMPT_GUARD_KEY, "0") === "1",
      timelineRewrite: storageGet(TIMELINE_REWRITE_KEY, "1") !== "0"
    };
  }
  function setOriginalOptions(options) {
    options = options && typeof options === "object" ? options : {};
    storageSet(PROMPT_GUARD_KEY, options.promptGuard === true ? "1" : "0");
    storageSet(TIMELINE_REWRITE_KEY, options.timelineRewrite === false ? "0" : "1");
    return getOriginalOptions();
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
        "background:#202638;color:#fff;font:13px sans-serif;box-shadow:0 2px 8px rgba(0,0,0,.25);" +
        "touch-action:none;user-select:none;cursor:move;";
      restorePosition(control);
      control.addEventListener("pointerdown", function (event) { beginDrag(event, control); });
      control.addEventListener("pointermove", function (event) { moveDrag(event, control); });
      control.addEventListener("pointerup", function (event) { finishDrag(event, control); });
      control.addEventListener("pointercancel", function (event) { finishDrag(event, control); });
      control.addEventListener("click", function (event) {
        if (Date.now() < suppressClickUntil) {
          if (event && event.preventDefault) event.preventDefault();
          if (event && event.stopPropagation) event.stopPropagation();
          return;
        }
        root.setDola30s(!isEnabled());
      });
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
  // Compatibility for the legacy WebView2/C# host. `applied` stays 0 because
  // this WebView adapter only stores local state and does not control the site.
  function setDoubao30s(on) { return setDola30s(!!on).on; }
  function getDoubao30sStatus() {
    try {
      var status = JSON.parse(getDola30sStatus());
      return JSON.stringify({ on: !!status.on, applied: 0, duration: status.duration, ready: status.ready });
    } catch (_) { return JSON.stringify({ on: false, applied: 0 }); }
  }
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
      getOriginalOptions: getOriginalOptions,
      setOriginalOptions: setOriginalOptions,
      limitations: Object.freeze({ networkRewrite: true, autoSubmit: false, watermarkBypass: false })
    });
    return;
  }
  // Default WebView mode exposes local helpers under the original public names.
  root.setDola30s = setDola30s;
  root.getDola30sStatus = getDola30sStatus;
  root.setDoubao30s = setDoubao30s;
  root.getDoubao30sStatus = getDoubao30sStatus;
  root.dola30Android = Object.freeze({
    setDola30s: setDola30s,
    getDola30sStatus: getDola30sStatus,
    setDoubao30s: setDoubao30s,
    getDoubao30sStatus: getDoubao30sStatus,
    rewriteDolaTimeline: rewriteDolaTimeline,
    getOriginalOptions: getOriginalOptions,
    setOriginalOptions: setOriginalOptions,
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

