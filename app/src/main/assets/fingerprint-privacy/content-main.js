/* Doulala Fingerprint Privacy: local diagnostics and user-controlled API reduction. */
(function (root) {
  "use strict";
  if (!root || root.top !== root || root.__dolaFingerprintPrivacy) return;

  var defaults = { blockCanvasReadback: false, hideWebglDebugInfo: true, blockAudioFingerprint: false, blockWebRTC: false, blockDeviceEnumeration: false };
  var settings = { blockCanvasReadback: false, hideWebglDebugInfo: true, blockAudioFingerprint: false, blockWebRTC: false, blockDeviceEnumeration: false };
  var originals = {};

  function copy(value) {
    var out = {};
    Object.keys(value || {}).forEach(function (key) { out[key] = !!value[key]; });
    return out;
  }
  function securityError(message) {
    return new DOMException(message || "Fingerprint surface disabled by the user", "SecurityError");
  }
  function installCanvasBlock() {
    if (originals.canvasToDataURL) return;
    if (root.HTMLCanvasElement && root.HTMLCanvasElement.prototype) {
      originals.canvasToDataURL = root.HTMLCanvasElement.prototype.toDataURL;
      originals.canvasToBlob = root.HTMLCanvasElement.prototype.toBlob;
      root.HTMLCanvasElement.prototype.toDataURL = function () { throw securityError("Canvas readback disabled by the user"); };
      root.HTMLCanvasElement.prototype.toBlob = function () { throw securityError("Canvas readback disabled by the user"); };
    }
    if (root.CanvasRenderingContext2D && root.CanvasRenderingContext2D.prototype) {
      originals.getImageData = root.CanvasRenderingContext2D.prototype.getImageData;
      root.CanvasRenderingContext2D.prototype.getImageData = function () { throw securityError("Canvas readback disabled by the user"); };
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
    originals.canvasToDataURL = originals.canvasToBlob = originals.getImageData = null;
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
    originals.getExtension = originals.getExtension2 = null;
  }
  function installAudioBlock() {
    if (originals.offlineAudio) return;
    if (root.OfflineAudioContext) {
      originals.offlineAudio = root.OfflineAudioContext;
      root.OfflineAudioContext = function () { throw securityError("Offline audio fingerprinting disabled by the user"); };
    }
    if (root.webkitOfflineAudioContext) {
      originals.webkitOfflineAudio = root.webkitOfflineAudioContext;
      root.webkitOfflineAudioContext = function () { throw securityError("Offline audio fingerprinting disabled by the user"); };
    }
  }
  function removeAudioBlock() {
    if (originals.offlineAudio) root.OfflineAudioContext = originals.offlineAudio;
    if (originals.webkitOfflineAudio) root.webkitOfflineAudioContext = originals.webkitOfflineAudio;
    originals.offlineAudio = originals.webkitOfflineAudio = null;
  }
  function installWebrtcBlock() {
    if (originals.rtc) return;
    var ctor = root.RTCPeerConnection || root.webkitRTCPeerConnection;
    if (ctor) {
      originals.rtc = ctor;
      root.RTCPeerConnection = function () { throw securityError("WebRTC disabled by the user"); };
      if (root.webkitRTCPeerConnection) root.webkitRTCPeerConnection = root.RTCPeerConnection;
    }
  }
  function removeWebrtcBlock() {
    if (!originals.rtc) return;
    root.RTCPeerConnection = originals.rtc;
    if (root.webkitRTCPeerConnection) root.webkitRTCPeerConnection = originals.rtc;
    originals.rtc = null;
  }
  function installDeviceEnumerationBlock() {
    if (originals.enumerateDevices || !root.navigator || !root.navigator.mediaDevices) return;
    if (typeof root.navigator.mediaDevices.enumerateDevices !== "function") return;
    originals.enumerateDevices = root.navigator.mediaDevices.enumerateDevices;
    root.navigator.mediaDevices.enumerateDevices = function () { return Promise.resolve([]); };
  }
  function removeDeviceEnumerationBlock() {
    if (originals.enumerateDevices && root.navigator && root.navigator.mediaDevices) {
      root.navigator.mediaDevices.enumerateDevices = originals.enumerateDevices;
    }
    originals.enumerateDevices = null;
  }
  function apply(next) {
    settings = copy(Object.assign({}, defaults, next || {}));
    if (settings.blockCanvasReadback) installCanvasBlock(); else removeCanvasBlock();
    if (settings.hideWebglDebugInfo) installWebglReduction(); else removeWebglReduction();
    if (settings.blockAudioFingerprint) installAudioBlock(); else removeAudioBlock();
    if (settings.blockWebRTC) installWebrtcBlock(); else removeWebrtcBlock();
    if (settings.blockDeviceEnumeration) installDeviceEnumerationBlock(); else removeDeviceEnumerationBlock();
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
      audioContextAvailable: !!root.AudioContext,
      offlineAudioAvailable: !!root.OfflineAudioContext,
      webRtcAvailable: !!(root.RTCPeerConnection || root.webkitRTCPeerConnection),
      mediaDeviceEnumerationAvailable: !!(root.navigator && root.navigator.mediaDevices && root.navigator.mediaDevices.enumerateDevices),
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
