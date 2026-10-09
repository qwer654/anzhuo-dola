/* Doulala Fingerprint Privacy: local diagnostics and opt-in API reduction. */
(function (root) {
  "use strict";
  if (!root || root.top !== root || root.__dolaFingerprintPrivacy) return;

  var defaults = { blockCanvasReadback: false, hideWebglDebugInfo: false };
  var settings = { blockCanvasReadback: false, hideWebglDebugInfo: false };
  var originals = {};

  function copy(value) {
    var out = {};
    Object.keys(value || {}).forEach(function (key) { out[key] = !!value[key]; });
    return out;
  }
  function securityError() {
    return new DOMException("Canvas readback disabled by the user", "SecurityError");
  }
  function installCanvasBlock() {
    if (originals.canvasToDataURL) return;
    if (root.HTMLCanvasElement && root.HTMLCanvasElement.prototype) {
      originals.canvasToDataURL = root.HTMLCanvasElement.prototype.toDataURL;
      originals.canvasToBlob = root.HTMLCanvasElement.prototype.toBlob;
      root.HTMLCanvasElement.prototype.toDataURL = function () { throw securityError(); };
      root.HTMLCanvasElement.prototype.toBlob = function () { throw securityError(); };
    }
    if (root.CanvasRenderingContext2D && root.CanvasRenderingContext2D.prototype) {
      originals.getImageData = root.CanvasRenderingContext2D.prototype.getImageData;
      root.CanvasRenderingContext2D.prototype.getImageData = function () { throw securityError(); };
    }
  }
  function removeCanvasBlock() {
    if (root.HTMLCanvasElement && root.HTMLCanvasElement.prototype && originals.canvasToDataURL) {
      root.HTMLCanvasElement.prototype.toDataURL = originals.canvasToDataURL;
      root.HTMLCanvasElement.prototype.toBlob = originals.canvasToBlob;
    }
    if (root.CanvasRenderingContext2D && root.CanvasRenderingContext2D.prototype && originals.getImageData) {
      root.CanvasRenderingContext2D.prototype.getImageData = originals.getImageData;
    }
  }
  function installWebglReduction() {
    if (originals.getExtension) return;
    var proto = root.WebGLRenderingContext && root.WebGLRenderingContext.prototype;
    if (!proto) return;
    originals.getExtension = proto.getExtension;
    proto.getExtension = function (name) {
      if (String(name) === "WEBGL_debug_renderer_info") return null;
      return originals.getExtension.apply(this, arguments);
    };
    var proto2 = root.WebGL2RenderingContext && root.WebGL2RenderingContext.prototype;
    if (proto2) {
      originals.getExtension2 = proto2.getExtension;
      proto2.getExtension = function (name) {
        if (String(name) === "WEBGL_debug_renderer_info") return null;
        return originals.getExtension2.apply(this, arguments);
      };
    }
  }
  function removeWebglReduction() {
    var proto = root.WebGLRenderingContext && root.WebGLRenderingContext.prototype;
    if (proto && originals.getExtension) proto.getExtension = originals.getExtension;
    var proto2 = root.WebGL2RenderingContext && root.WebGL2RenderingContext.prototype;
    if (proto2 && originals.getExtension2) proto2.getExtension = originals.getExtension2;
  }
  function apply(next) {
    settings = copy(Object.assign({}, defaults, next || {}));
    if (settings.blockCanvasReadback) installCanvasBlock(); else removeCanvasBlock();
    if (settings.hideWebglDebugInfo) installWebglReduction(); else removeWebglReduction();
    root.dispatchEvent(new CustomEvent("dola-fingerprint-settings", { detail: copy(settings) }));
  }
  function webglReport() {
    var canvas = root.document.createElement("canvas");
    var gl = canvas.getContext("webgl") || canvas.getContext("experimental-webgl");
    if (!gl) return { available: false };
    var debug = gl.getExtension("WEBGL_debug_renderer_info");
    return {
      available: true,
      vendor: debug ? gl.getParameter(debug.UNMASKED_VENDOR_WEBGL) : null,
      renderer: debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : null,
      version: String(gl.getParameter(gl.VERSION) || "")
    };
  }
  function report() {
    var screen = root.screen || {};
    var locale = "";
    try { locale = Intl.DateTimeFormat().resolvedOptions().timeZone || ""; } catch (_) {}
    return {
      generatedAt: new Date().toISOString(),
      pageOrigin: String(root.location && root.location.origin || ""),
      userAgent: String(root.navigator && root.navigator.userAgent || ""),
      platform: String(root.navigator && root.navigator.platform || ""),
      language: String(root.navigator && root.navigator.language || ""),
      languages: root.navigator && root.navigator.languages ? Array.from(root.navigator.languages) : [],
      timezone: locale,
      screen: { width: Number(screen.width || 0), height: Number(screen.height || 0), pixelRatio: Number(root.devicePixelRatio || 1), colorDepth: Number(screen.colorDepth || 0) },
      hardwareConcurrency: Number(root.navigator && root.navigator.hardwareConcurrency || 0),
      deviceMemory: Number(root.navigator && root.navigator.deviceMemory || 0),
      canvasReadbackAvailable: !!(root.HTMLCanvasElement && root.HTMLCanvasElement.prototype && root.HTMLCanvasElement.prototype.toDataURL),
      webgl: webglReport(),
      protection: copy(settings),
      localOnly: true
    };
  }
  root.__dolaFingerprintPrivacy = Object.freeze({
    getSettings: function () { return copy(settings); },
    applySettings: apply,
    getReport: report
  });
  root.addEventListener("dola-fingerprint-apply", function (event) { apply(event.detail || {}); });
  root.addEventListener("dola-fingerprint-report", function () {
    root.postMessage({ source: "dola-fingerprint-privacy", type: "report", report: report() }, "*");
  });
  apply(defaults);
})(typeof window === "undefined" ? null : window);
