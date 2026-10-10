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
    var minLeft = 8, maxLeft = size.width - width - 8;
    var minBottom = 8, maxBottom = size.height - height - 8;
    var visible = root.visualViewport;
    if (visible && visible.width > 0 && visible.height > 0) {
      var offsetLeft = Number(visible.offsetLeft) || 0;
      var offsetTop = Number(visible.offsetTop) || 0;
      minLeft = Math.max(minLeft, offsetLeft + 8);
      maxLeft = Math.min(maxLeft, offsetLeft + visible.width - width - 8);
      minBottom = Math.max(minBottom, size.height - (offsetTop + visible.height - 8));
      maxBottom = Math.min(maxBottom, size.height - offsetTop - height - 16);
    }
    if (maxLeft < minLeft) maxLeft = minLeft;
    if (maxBottom < minBottom) maxBottom = minBottom;
    return {
      left: Math.max(minLeft, Math.min(maxLeft, left)),
      bottom: Math.max(minBottom, Math.min(maxBottom, bottom))
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
    if (event.preventDefault) event.preventDefault();
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
    if (!moved) {
      // Handle taps on pointerup rather than the provider-dependent synthetic
      // click that follows it. This makes drag and activation mutually exclusive.
      root.setDola30s(!isEnabled());
      return;
    }
    persistPosition(control);
  }
  function cancelDrag(event, control) {
    if (!drag || (drag.pointerId != null && event.pointerId !== drag.pointerId)) return;
    var moved = drag.moved;
    drag = null;
    try { control.releasePointerCapture(event.pointerId); } catch (_) {}
    // A cancelled pointer sequence is never a tap. Preserve any movement made
    // before cancellation, but do not toggle the setting.
    if (moved) persistPosition(control);
  }
  function persistPosition(control) {
    try { root.localStorage.setItem(PANEL_POS_KEY, JSON.stringify({
      left: parseFloat(control.style.left), bottom: parseFloat(control.style.bottom)
    })); } catch (_) {}
  }
  function keepControlInView(control) {
    if (drag) return;
    var left = parseFloat(control.style.left), bottom = parseFloat(control.style.bottom);
    var position = applyPosition(control, left, bottom);
    if (position.left !== left || position.bottom !== bottom) persistPosition(control);
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
  // Read only: mirror the original Dola control selectors without reconciling
  // local state, clicking controls, editing labels, or touching requests.
  function getDolaPlatformSelectionStatus() {
    if (host !== "dola.com" && !host.endsWith(".dola.com")) {
      return { status: "not_applicable", model: "", duration: "" };
    }
    try {
      var model = root.document.querySelector('[data-input-engine-actionbar-control-key="video-model"]');
      var durationControl = root.document.querySelector('[data-input-engine-actionbar-control-key="video-duration"]');
      function text(element) {
        return String(element.textContent || element.getAttribute("aria-label") || element.getAttribute("title") || "")
          .replace(/[\s✓✔√]/g, "");
      }
      function visible(element) {
        var rect = element && element.getBoundingClientRect && element.getBoundingClientRect();
        var style = element && root.getComputedStyle && root.getComputedStyle(element);
        return !!(element && element.isConnected && rect && rect.width > 1 && rect.height > 1 && style &&
          style.display !== "none" && style.visibility !== "hidden" && Number(style.opacity || 1) > 0.01);
      }
      if (!visible(model) || !visible(durationControl)) {
        return { status: "controls_not_detected", model: "", duration: "" };
      }
      var modelText = text(model), durationText = text(durationControl);
      var model25 = /2\.5|seedance[^\d]*2[^\d]*5/i.test(modelText);
      var duration30 = /^30(?:s|秒)$/i.test(durationText);
      return { status: model25 && duration30 ? "selection_matches_30s" : "detected_not_30s",
        model: modelText.slice(0, 80), duration: durationText.slice(0, 40) };
    } catch (_) {
      return { status: "controls_not_detected", model: "", duration: "" };
    }
  }
  function publish() {
    var on = isEnabled();
    var documentElement = root.document.documentElement;
    if (documentElement) documentElement.setAttribute(STATE_ATTRIBUTE, on ? "1" : "0");
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
      control.addEventListener("pointercancel", function (event) { cancelDrag(event, control); });
      control.addEventListener("click", function (event) {
        // Ignore provider-generated mouse clicks; pointerup owns touch/mouse
        // activation. Keep keyboard activation available for accessibility.
        if (event && event.detail === 0 && event.isTrusted) root.setDola30s(!isEnabled());
      });
      // Match the original panel's keep-in-view behavior when rotation,
      // split-screen, zoom, or the on-screen keyboard changes the viewport.
      function reposition() { keepControlInView(control); }
      if (root.addEventListener) root.addEventListener("resize", reposition);
      if (root.visualViewport && root.visualViewport.addEventListener) {
        root.visualViewport.addEventListener("resize", reposition);
        root.visualViewport.addEventListener("scroll", reposition);
      }
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
      getDolaPlatformSelectionStatus: getDolaPlatformSelectionStatus,
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
    getDolaPlatformSelectionStatus: getDolaPlatformSelectionStatus,
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

