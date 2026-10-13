/*
 * Read-only WebView media observer for the v1.6.14 page scripts.
 * This is deliberately not a Chrome-extension compatibility layer.
 */
(function (root, factory) {
  var api = factory(root || (typeof window !== "undefined" ? window : globalThis));
  if (typeof module === "object" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : globalThis, function (root) {
  "use strict";
  var LIMITS = Object.freeze({ bodyBytes: 2 * 1024 * 1024, items: 200, depth: 6, nodes: 600 });
  var UNSUPPORTED = Object.freeze([
    "Chrome debugger", "extension panel", "background service worker", "CDP response body", "fallback API fetching"
  ]);
  var MEDIA_FIELDS = /(?:^|_)(?:video|videos|video_url|play_url|main_url|download_url|audio|audio_url|image|images|image_url|image_ori_raw|origin_image|original_image|cover_url|poster|thumbnail_url)(?:$|_)/i;
  var VIDEO_FIELDS = /(?:video|play_url|main_url|download_url)/i;
  var AUDIO_FIELDS = /(?:audio)/i;
  var IMAGE_FIELDS = /(?:image|cover|poster|thumbnail)/i;
  var endpointByXhr = typeof WeakMap === "function" ? new WeakMap() : null;

  function pageUrl(value) {
    try { return new URL(String(value || ""), root.location && root.location.href); } catch (_) { return null; }
  }
  function allowedPage(url) {
    if (!url || url.protocol !== "https:" || url.port) return false;
    // A trailing dot is deliberately not normalized: the allowed-origin contract is exact.
    var host = String(url.hostname || "").toLowerCase();
    return host === "dola.com" || /\.dola\.com$/.test(host) || host === "doubao.com" || /\.doubao\.com$/.test(host);
  }
  function activePage() { return allowedPage(pageUrl(root.location && root.location.href)); }
  function safeMediaUrl(value) {
    if (typeof value !== "string" || !value || value.length > 8192) return "";
    try {
      var url = new URL(value, root.location && root.location.href);
      if (url.protocol !== "https:" || url.port || url.username || url.password) return "";
      return url.href;
    } catch (_) { return ""; }
  }
  function isKnownEndpoint(value) {
    var target = pageUrl(value), here = pageUrl(root.location && root.location.href);
    if (!target || !here || !allowedPage(here) || target.origin !== here.origin) return false;
    var path = target.pathname.replace(/\/$/, "");
    return path === "/im/chain/single";
  }
  function typeFor(url, hint) {
    var path = String(url).split("?")[0].toLowerCase();
    if (/\.(?:mp4|m4v|mov|webm|m3u8)(?:$|#)/.test(path) || VIDEO_FIELDS.test(hint)) return "video";
    if (/\.(?:mp3|wav|ogg|m4a|aac)(?:$|#)/.test(path) || AUDIO_FIELDS.test(hint)) return "audio";
    if (/\.(?:png|jpe?g|webp|gif|avif|bmp)(?:$|#)/.test(path) || IMAGE_FIELDS.test(hint)) return "image";
    return "";
  }
  function title() {
    var value = root.document && root.document.title;
    return typeof value === "string" ? value.slice(0, 200) : "";
  }
  function createState() {
    var items = [], seen = new Set();
    var state = {
      installed: true,
      items: items,
      unsupported: UNSUPPORTED.slice(),
      limits: { bodyBytes: LIMITS.bodyBytes, items: LIMITS.items },
      drain: function () { var out = items.splice(0, items.length); return out; }
    };
    state.add = function (candidate) {
      if (!candidate || items.length >= LIMITS.items) return false;
      var url = safeMediaUrl(candidate.url), kind = candidate.type || typeFor(url, candidate.hint || "");
      if (!url || !kind || seen.has(url)) return false;
      seen.add(url);
      if (seen.size > LIMITS.items * 4) seen.delete(seen.values().next().value);
      var item = { url: url, type: kind, title: String(candidate.title || title()).slice(0, 200) };
      var poster = safeMediaUrl(candidate.posterUrl);
      if (kind === "video" && poster) item.posterUrl = poster;
      items.push(item);
      return true;
    };
    return state;
  }
  function readResponseText(response) {
    if (!response || !response.clone) return Promise.resolve("");
    var length = Number(response.headers && response.headers.get && response.headers.get("content-length"));
    if (Number.isFinite(length) && length > LIMITS.bodyBytes) return Promise.resolve("");
    var clone;
    try { clone = response.clone(); } catch (_) { return Promise.resolve(""); }
    if (!clone.body || !clone.body.getReader) return clone.text ? clone.text().then(function (text) {
      return typeof text === "string" && text.length <= LIMITS.bodyBytes ? text : "";
    }, function () { return ""; }) : Promise.resolve("");
    var reader = clone.body.getReader(), decoder = typeof TextDecoder === "function" ? new TextDecoder() : null;
    var chunks = [], size = 0;
    function next() { return reader.read().then(function (part) {
      if (part.done) return true;
      size += (part.value && part.value.byteLength) || 0;
      if (size > LIMITS.bodyBytes) { if (reader.cancel) reader.cancel(); return false; }
      chunks.push(part.value); return next();
    }, function () { return ""; }); }
    return next().then(function (complete) {
      if (complete !== true || !decoder) return "";
      var text = "";
      for (var i = 0; i < chunks.length; i++) text += decoder.decode(chunks[i], { stream: true });
      return text + decoder.decode();
    });
  }
  function collectPayload(payload, state) {
    var nodes = 0, visited = new Set();
    function walk(value, depth, key, mediaContext) {
      if (++nodes > LIMITS.nodes || depth > LIMITS.depth) return;
      if (typeof value === "string") {
        if (value.length > LIMITS.bodyBytes || (!MEDIA_FIELDS.test(key || "") && !(mediaContext && /^(?:url|uri|src)$/i.test(key)))) return;
        state.add({ url: value, type: typeFor(value, mediaContext || key), hint: mediaContext || key });
        return;
      }
      if (!value || typeof value !== "object" || visited.has(value)) return;
      visited.add(value);
      var keys = Object.keys(value);
      for (var i = 0; i < keys.length && i < LIMITS.nodes; i++) {
        var child = keys[i], context = MEDIA_FIELDS.test(child) ? child : mediaContext;
        walk(value[child], depth + 1, child, context);
      }
    }
    walk(payload, 0, "", "");
  }
  function collectText(text, state) {
    if (typeof text !== "string" || text.length > LIMITS.bodyBytes) return;
    try { collectPayload(JSON.parse(text), state); } catch (_) { /* Known endpoint did not provide JSON. */ }
  }
  function scanDom(state) {
    if (!activePage() || !root.document || !root.document.querySelectorAll) return;
    var nodes = root.document.querySelectorAll("video,audio,img,source");
    for (var i = 0; i < nodes.length && i < LIMITS.items; i++) {
      var node = nodes[i], tag = String(node.tagName || "").toLowerCase();
      var kind = tag === "video" ? "video" : tag === "audio" ? "audio" : tag === "img" ? "image" : "";
      if (!kind && tag === "source") kind = typeFor(node.src || "", "");
      if (!kind) continue;
      state.add({ url: node.currentSrc || node.src || (node.getAttribute && node.getAttribute("src")) || "", type: kind,
        posterUrl: kind === "video" ? node.poster : "" });
    }
  }
  function collectSkillPackEvent(state) {
    var element = root.document && root.document.documentElement;
    if (!element || !element.getAttribute) return;
    var raw = element.getAttribute("data-dola-media-fallback-apis");
    if (!raw || raw.length > LIMITS.bodyBytes) return;
    try {
      var urls = JSON.parse(raw);
      if (!Array.isArray(urls)) return;
      for (var i = 0; i < urls.length && i < LIMITS.items; i++) {
        var url = safeMediaUrl(urls[i]);
        // This event normally contains fallback API endpoints.  It is only useful here
        // when a page script has already emitted an actual direct media URL.
        var kind = typeFor(url, "");
        if (kind) state.add({ url: url, type: kind });
      }
    } catch (_) {}
  }
  function install() {
    if (!activePage()) return null;
    if (root.__dolaNewMediaState && root.__dolaNewMediaState.installed) return root.__dolaNewMediaState;
    var state = createState(); root.__dolaNewMediaState = state;
    state.scan = function () { scanDom(state); collectSkillPackEvent(state); return state.items.length; };
    state.scan();
    if (root.document && root.document.addEventListener) {
      root.document.addEventListener("DOMContentLoaded", function () { state.scan(); }, { once: true });
      root.document.addEventListener("dola-media-fallback-apis", function () { collectSkillPackEvent(state); });
    }
    // Generated result cards may arrive after DOMContentLoaded.  Coalesce mutations;
    // this observes only page nodes and neither edits them nor causes network activity.
    var Observer = root.MutationObserver;
    if (typeof Observer === "function" && root.document && root.document.documentElement) {
      var scheduled = false;
      try {
        new Observer(function () {
          if (scheduled) return;
          scheduled = true;
          var run = function () { scheduled = false; state.scan(); };
          if (typeof root.setTimeout === "function") root.setTimeout(run, 80); else run();
        }).observe(root.document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ["src", "poster"] });
      } catch (_) {}
    }
    var originalFetch = root.fetch;
    if (typeof originalFetch === "function") root.fetch = function observedMediaFetch(input, init) {
      var result = originalFetch.apply(this, arguments), raw = typeof input === "string" ? input : input && input.url;
      if (isKnownEndpoint(raw)) Promise.resolve(result).then(function (response) {
        return readResponseText(response).then(function (text) { collectText(text, state); });
      }).catch(function () {});
      return result;
    };
    var Xhr = root.XMLHttpRequest;
    if (Xhr && Xhr.prototype && endpointByXhr) {
      var open = Xhr.prototype.open, send = Xhr.prototype.send;
      if (typeof open === "function") Xhr.prototype.open = function observedMediaOpen(method, url) {
        endpointByXhr.set(this, isKnownEndpoint(url)); return open.apply(this, arguments);
      };
      if (typeof send === "function") Xhr.prototype.send = function observedMediaSend() {
        var xhr = this;
        if (endpointByXhr.get(xhr) && xhr.addEventListener) xhr.addEventListener("loadend", function () {
          try { var body = typeof xhr.responseText === "string" ? xhr.responseText : ""; collectText(body, state); } catch (_) {}
        }, { once: true });
        return send.apply(this, arguments);
      };
    }
    return state;
  }
  var exported = { LIMITS: LIMITS, allowedPage: allowedPage, safeMediaUrl: safeMediaUrl, isKnownEndpoint: isKnownEndpoint,
    typeFor: typeFor, createState: createState, collectPayload: collectPayload, collectText: collectText, install: install };
  if (root && root.document) install();
  return exported;
});
