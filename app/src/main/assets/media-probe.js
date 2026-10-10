/* Dola Android passive media observer. No request/response rewriting or downloads. */
(function (root, factory) {
  "use strict";
  var api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
    return;
  }
  if (!root || !root.document || !api.isAllowedHost(root.location.hostname)) return;
  if (root.__dolaAndroidProbeInstalled) return;
  var isTop = root.top === root;
  var origin = String(root.location.origin || "");
  if (!/^https?:\/\//i.test(origin)) return;
  root.__dolaAndroidProbeInstalled = true;
  var collector = api.createCollector();
  var document = root.document;
  var bodyJobs = 0;
  var pendingScan = false;
  var pendingFrames = new Map();
  var frameRequests = new WeakMap();
  var frameLoadListeners = new WeakSet();
  function title() { return String(document.title || "").slice(0, 200); }
  function trustedOrigin(raw) {
    try {
      var url = new URL(raw);
      if (url.protocol !== "https:" || !url.hostname || url.username || url.password || (url.port && url.port !== "443") || !api.isAllowedHost(url.hostname)) return "";
      return url.origin;
    } catch (_) { return ""; }
  }
  function frameOrigin(frame) {
    if (!frame || !frame.getAttribute || frame.hasAttribute && frame.hasAttribute("srcdoc")) return "";
    var raw = frame.getAttribute("src");
    if (!raw || !raw.trim()) return "";
    try { return trustedOrigin(new URL(raw, document.baseURI || root.location.href).href); }
    catch (_) { return ""; }
  }
  function requestFrameScans() {
    if (!isTop || !document.querySelectorAll || !root.crypto || typeof root.crypto.getRandomValues !== "function") return;
    var frames;
    try { frames = document.querySelectorAll("iframe"); } catch (_) { return; }
    for (var i = 0; i < Math.min(frames.length, 20); i++) {
      var frame = frames[i], targetOrigin = frameOrigin(frame), source = frame && frame.contentWindow;
      if (!targetOrigin || !source || frame.isConnected === false) continue;
      if (!frameLoadListeners.has(frame) && frame.addEventListener) {
        frameLoadListeners.add(frame);
        frame.addEventListener("load", function () { requestFrameScans(); });
      }
      var last = frameRequests.get(frame) || 0;
      if (Date.now() - last < 750) continue;
      frameRequests.set(frame, Date.now());
      var bytes = new Uint32Array(4);
      try { root.crypto.getRandomValues(bytes); } catch (_) { continue; }
      var nonce = Array.from(bytes).map(function (n) { return n.toString(16); }).join("");
      pendingFrames.set(nonce, { frame: frame, source: source, origin: targetOrigin, expires: Date.now() + 5000 });
      try { source.postMessage({ channel: "dola.android.media", version: 1, type: "scan-request", nonce: nonce }, targetOrigin); }
      catch (_) { pendingFrames.delete(nonce); }
    }
  }
  function receive(items) {
    collector.add(items);
    // Child frames only send data in response to a top-frame nonce challenge.
  }
  if (isTop && typeof root.addEventListener === "function") {
    root.addEventListener("message", function (event) {
      var data = event && event.data;
      if (!event || !event.source || !data || data.channel !== "dola.android.media" || data.version !== 1 ||
          data.type !== "scan-response" || typeof data.nonce !== "string" || data.nonce.length > 64 ||
          !Array.isArray(data.items) || data.items.length > 50) return;
      var pending = pendingFrames.get(data.nonce);
      pendingFrames.delete(data.nonce);
      if (!pending || pending.expires < Date.now() || event.source !== pending.source || event.origin !== pending.origin ||
          !pending.frame || pending.frame.isConnected === false || pending.frame.contentWindow !== event.source || frameOrigin(pending.frame) !== event.origin) return;
      try { if (JSON.stringify(data).length > 65536) return; } catch (_) { return; }
      var items = [];
      for (var i = 0; i < data.items.length; i++) {
        var item = data.items[i];
        if (!item || (item.type !== "image" && item.type !== "video") ||
            !api.normalizeMediaUrl(item.url) || typeof item.url !== "string" ||
            item.url.length > api.LIMITS.urlLength) continue;
        items.push({ url: item.url, type: item.type,
          posterUrl: item.type === "video" ? api.normalizeMediaUrl(item.posterUrl) || "" : "",
          source: "frame:" + String(item.source || "page").slice(0, 200),
          title: String(item.title || "").slice(0, 200) });
      }
      collector.add(items);
    });
  }
  function scanDom() {
    var nodes = document.querySelectorAll("img,video,source");
    for (var i = 0; i < Math.min(nodes.length, 300); i++) {
      var node = nodes[i];
      var tag = node.tagName.toLowerCase();
      var parentTag = node.parentElement && node.parentElement.tagName.toLowerCase();
      if (tag === "source" && parentTag !== "video" && parentTag !== "picture") continue;
      var type = tag === "img" || (tag === "source" && parentTag === "picture") ? "image" : "video";
      // Keep both the selected currentSrc and authored src where they differ
      // (notably <picture><source srcset> + <img src="fallback">).
      var urls = [], candidates = [node.currentSrc || "", node.src || "", node.getAttribute && node.getAttribute("src") || ""];
      for (var u = 0; u < candidates.length; u++) {
        var candidate = api.normalizeMediaUrl(candidates[u]);
        if (candidate && urls.indexOf(candidate) < 0) urls.push(candidate);
      }
      var poster = tag === "video" ? api.normalizeMediaUrl(node.poster || (node.getAttribute && node.getAttribute("poster")) || "") : "";
      for (var m = 0; m < urls.length; m++) receive([{ url: urls[m], type: type, posterUrl: poster, source: "dom", title: title() }]);
      // Responsive images may expose several useful candidates while currentSrc
      // reports only the one selected for this viewport. Parse candidates without
      // rewriting their URL bytes; descriptors are intentionally ignored.
      if (type === "image" && (tag === "img" || tag === "source")) {
        var srcset = node.srcset || (node.getAttribute && node.getAttribute("srcset")) || "";
        var candidates = api.parseSrcset(srcset);
        for (var c = 0; c < candidates.length; c++) {
          receive([{ url: candidates[c], type: "image", source: "dom:srcset", title: title() }]);
        }
      }
      if (tag === "video") {
        if (poster) receive([{ url: poster, type: "image", source: "dom:poster", title: title() }]);
      }
    }
  }
  function scheduleScan() {
    if (pendingScan) return;
    pendingScan = true;
    root.setTimeout(function () { pendingScan = false; scanDom(); }, 400);
  }
  if (!isTop && root.parent && typeof root.addEventListener === "function") {
    root.addEventListener("message", function (event) {
      var data = event && event.data;
      if (!event || event.source !== root.parent || !trustedOrigin(event.origin) ||
          !data || data.channel !== "dola.android.media" || data.version !== 1 ||
          data.type !== "scan-request" || typeof data.nonce !== "string" || !/^[0-9a-f]{1,64}$/.test(data.nonce)) return;
      scanDom();
      var items = collector.drain().slice(0, 50);
      var response = { channel: "dola.android.media", version: 1, type: "scan-response", nonce: data.nonce, items: items };
      // Bound the serialized response too, not only item count and URL length.
      while (items.length && JSON.stringify(response).length > 65536) items.pop();
      try { root.parent.postMessage(response, event.origin); }
      catch (_) { /* Detached or navigated frame: ignore. */ }
    });
  }
  root.__dolaAndroidScan = function () { scanDom(); requestFrameScans(); return collector.snapshot(); };
  root.__dolaAndroidDrain = function () { requestFrameScans(); return collector.drain(); };

  async function observeResponse(response, source) {
    // Only inspect readable JSON/text copies. Never consume or replace the page's response.
    if (!response || bodyJobs >= 3 || response.type === "opaque") return;
    var contentType = response.headers.get("content-type") || "";
    if (!/(?:json|text|javascript)/i.test(contentType)) return;
    var length = Number(response.headers.get("content-length") || 0);
    if (length > api.LIMITS.bodyBytes) return;
    bodyJobs++;
    var reader;
    try {
      var copy = response.clone();
      if (!copy.body || typeof copy.body.getReader !== "function") return;
      reader = copy.body.getReader();
      var chunks = [], total = 0;
      while (true) {
        var part = await reader.read();
        if (part.done) break;
        total += part.value.byteLength;
        if (total > api.LIMITS.bodyBytes) { reader.cancel().catch(function () {}); return; }
        chunks.push(part.value);
      }
      var bytes = new Uint8Array(total), offset = 0;
      for (var i = 0; i < chunks.length; i++) { bytes.set(chunks[i], offset); offset += chunks[i].byteLength; }
      var text = new TextDecoder("utf-8").decode(bytes);
      receive(api.parseText(text, source, title()));
    } catch (_) { /* Observability must never affect page behavior. */ }
    finally { bodyJobs--; }
  }

  if (typeof root.fetch === "function") {
    var originalFetch = root.fetch;
    root.fetch = function () {
      var promise = originalFetch.apply(this, arguments);
      promise.then(function (response) { observeResponse(response, "fetch"); }, function () {});
      return promise;
    };
  }
  if (root.XMLHttpRequest) {
    var originalSend = root.XMLHttpRequest.prototype.send;
    root.XMLHttpRequest.prototype.send = function () {
      var xhr = this;
      var finished = function () {
        xhr.removeEventListener("loadend", finished);
        try {
          var length = Number(xhr.getResponseHeader("content-length") || 0);
          if (length > api.LIMITS.bodyBytes) return;
          if (!xhr.responseType || xhr.responseType === "text") {
            var text = xhr.responseText;
            if (typeof text === "string" && text.length <= api.LIMITS.bodyBytes) receive(api.parseText(text, "xhr", title()));
          } else if (xhr.responseType === "json") {
            receive(api.parsePayload(xhr.response, "xhr", title()));
          }
        } catch (_) { /* Binary/opaque responses are not inspected. */ }
      };
      xhr.addEventListener("loadend", finished);
      try { return originalSend.apply(this, arguments); }
      catch (error) { xhr.removeEventListener("loadend", finished); throw error; }
    };
  }
  if (root.PerformanceObserver) {
    try {
      var performanceObserver = new root.PerformanceObserver(function (list) {
        var entries = list.getEntries();
        for (var i = 0; i < Math.min(entries.length, 300); i++) {
          var entry = entries[i];
          var hint = entry.initiatorType === "img" ? "image" : entry.initiatorType === "video" ? "video" : "";
          var url = api.normalizeMediaUrl(entry.name), type = api.mediaType(url, hint);
          if (url && type) receive([{ url: url, type: type, source: "performance", title: title() }]);
        }
      });
      performanceObserver.observe({ type: "resource", buffered: true });
    } catch (_) { /* Unsupported PerformanceObserver options are optional. */ }
  }
  function startDom() {
    scanDom();
    if (root.MutationObserver && document.documentElement) {
      new root.MutationObserver(scheduleScan).observe(document.documentElement, {
        subtree: true, childList: true, attributes: true, attributeFilter: ["src", "srcset", "poster"]
      });
    }
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", startDom, { once: true });
  else startDom();
})(typeof window === "undefined" ? null : window, function () {
  "use strict";
  var LIMITS = Object.freeze({ bodyBytes: 1048576, queue: 300, nodes: 5000, depth: 16, urlLength: 8192 });
  function isAllowedHost(hostname) {
    var host = String(hostname || "").toLowerCase().replace(/\.$/, "");
    return ["doubao.com", "dola.com", "qianwen.com", "jianying.com"].some(function (domain) { return host === domain || host.endsWith("." + domain); });
  }
  function normalizeMediaUrl(value) {
    if (typeof value !== "string" || value.length > LIMITS.urlLength) return "";
    var raw = value.trim();
    if (!/^https?:\/\//i.test(raw) || /[\u0000-\u0020\u007f]/.test(raw)) return "";
    try {
      var parsed = new URL(raw);
      if (!parsed.hostname || parsed.username || parsed.password || !/^https?:$/.test(parsed.protocol)) return "";
      return raw; // Do not decode, sort, strip, or otherwise corrupt signed query strings.
    } catch (_) { return ""; }
  }
  function mediaType(url, hint) {
    if (!url) return "";
    var path;
    try { path = new URL(url).pathname; } catch (_) { return ""; }
    // Prefer an explicit, recognizable file type over resource initiator hints:
    // browsers report a video element's poster request with initiatorType="video".
    if (/\.(?:png|jpe?g|webp|gif|avif|bmp|heic)(?:[~!].*)?$/i.test(path)) return "image";
    if (/\.(?:mp4|m4v|mov|webm|mkv|m3u8)(?:[~!].*)?$/i.test(path)) return "video";
    return hint === "image" || hint === "video" ? hint : "";
  }
  function parseSrcset(value) {
    if (typeof value !== "string" || value.length > LIMITS.bodyBytes) return [];
    var candidates = [], i = 0;
    while (i < value.length && candidates.length < 100) {
      while (i < value.length && /[\s,]/.test(value[i])) i++;
      if (i >= value.length) break;
      var start = i;
      while (i < value.length && !/\s/.test(value[i])) i++;
      var rawToken = value.slice(start, i);
      var endedCandidate = /,+$/.test(rawToken);
      var token = rawToken.replace(/,+$/, "");
      var url = normalizeMediaUrl(token);
      if (url) candidates.push(url);
      if (endedCandidate) continue;
      // Skip a width/density descriptor until the next candidate separator.
      while (i < value.length && value[i] !== ",") i++;
      if (value[i] === ",") i++;
    }
    return candidates;
  }
  function decodeMainUrl(value) {
    var direct = normalizeMediaUrl(value);
    if (direct) return direct;
    if (typeof value !== "string" || !value || value.length > LIMITS.urlLength * 2) return "";
    var base64 = value.trim().replace(/-/g, "+").replace(/_/g, "/");
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(base64) || base64.length % 4 === 1) return "";
    try {
      while (base64.length % 4) base64 += "=";
      var decoded = typeof atob === "function" ? atob(base64) : Buffer.from(base64, "base64").toString("latin1");
      return normalizeMediaUrl(decoded);
    } catch (_) { return ""; }
  }
  function createCollector() {
    var queue = [], seen = new Map();
    return {
      add: function (items) {
        if (!Array.isArray(items)) return;
        for (var i = 0; i < Math.min(items.length, LIMITS.queue); i++) {
          var item = items[i];
          if (!item || queue.length >= LIMITS.queue) break;
          var url = normalizeMediaUrl(item.url), type = item.type;
          if (!url || (type !== "image" && type !== "video")) continue;
          var key = type + ":" + url;
          var posterUrl = type === "video" ? normalizeMediaUrl(item.posterUrl) : "";
          if (seen.has(key)) {
            // A video may be observed before its poster attribute is populated.
            // Emit one metadata update without duplicating the media row.
            if (type === "video" && posterUrl && !seen.get(key)) {
              seen.set(key, posterUrl);
              queue.push({ url: url, type: type, posterUrl: posterUrl,
                source: String(item.source || "page").slice(0, 300), title: String(item.title || "").slice(0, 200) });
            }
            continue;
          }
          seen.set(key, posterUrl || "");
          if (seen.size > 3000) seen.delete(seen.keys().next().value);
          var normalized = { url: url, type: type, source: String(item.source || "page").slice(0, 300), title: String(item.title || "").slice(0, 200) };
          if (type === "video" && posterUrl) normalized.posterUrl = posterUrl;
          queue.push(normalized);
        }
      },
      snapshot: function () { return queue.slice(); },
      drain: function () { var result = queue; queue = []; return result; }
    };
  }
  function parsePayload(payload, source, title) {
    var collector = createCollector(), seen = new Set(), count = 0;
    function add(value, hint, key) {
      if (key === "fallback_api") return;
      var url = key === "main_url" || key === "play_url" ? decodeMainUrl(value) : normalizeMediaUrl(value);
      var type = mediaType(url, hint);
      if (url && type) collector.add([{ url: url, type: type, source: source || "json", title: title || "" }]);
    }
    function walk(value, depth, hint, key) {
      if (++count > LIMITS.nodes || depth > LIMITS.depth || key === "fallback_api") return;
      if (typeof value === "string") {
        if (value.length > LIMITS.bodyBytes) return;
        add(value, hint, key);
        var text = value.trim();
        if (/^[\[{\"]/.test(text)) {
          try { var inner = JSON.parse(text); if (inner !== value) walk(inner, depth + 1, hint, key); } catch (_) {}
        }
        return;
      }
      if (!value || typeof value !== "object" || seen.has(value)) return;
      seen.add(value);
      var keys = Object.keys(value);
      for (var i = 0; i < Math.min(keys.length, LIMITS.nodes - count); i++) {
        var childKey = keys[i], nextHint = hint;
        if (/^(?:image|images|image_ori_raw|image_url|origin_image|original_image|thumbnail_url|poster|cover_url)$/i.test(childKey)) nextHint = "image";
        if (/^(?:video|videos|video_list|video_url|play_url|main_url|download_url)$/i.test(childKey)) nextHint = "video";
        walk(value[childKey], depth + 1, nextHint, childKey);
      }
    }
    walk(payload, 0, "", "");
    return collector.drain();
  }
  function parseText(text, source, title) {
    if (typeof text !== "string" || !text || text.length > LIMITS.bodyBytes) return [];
    try { return parsePayload(JSON.parse(text), source, title); }
    catch (_) {
      // SSE/partial JSON: bounded URL discovery, requiring a media extension.
      var collector = createCollector(), regex = /https?:\\?\/\\?\/[^\s"<>\\]+(?:\\\/[^\s"<>\\]*)*/g, match, count = 0;
      while ((match = regex.exec(text)) && count++ < 1000) {
        var url = normalizeMediaUrl(match[0].replace(/\\\//g, "/"));
        var type = mediaType(url, "");
        if (url && type) collector.add([{ url: url, type: type, source: source || "text", title: title || "" }]);
      }
      return collector.drain();
    }
  }
  return { LIMITS: LIMITS, isAllowedHost: isAllowedHost, normalizeMediaUrl: normalizeMediaUrl,
    parseSrcset: parseSrcset,
    mediaType: mediaType, decodeMainUrl: decodeMainUrl, createCollector: createCollector,
    parsePayload: parsePayload, parseText: parseText };
});

